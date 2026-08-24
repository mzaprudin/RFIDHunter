package ru.rfidhunter.app.data

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ru.rfidhunter.app.RfidHunterApplication
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class SyncSettingsState(
    val endpoint: String = "",
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val registered: Boolean = false,
    val keyId: String? = null,
    val scopes: Set<String> = emptySet(),
    val accountId: String? = null,
)

/** Local configuration only; credentials are intentionally not embedded in the APK. */
class SyncSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val deviceIdValue = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(KEY_DEVICE_ID, it).apply()
    }
    val identity: DeviceIdentity by lazy { DeviceIdentity(deviceIdValue) }
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SyncSettingsState> = _state.asStateFlow()
    val deviceId: String get() = deviceIdValue

    fun setEndpoint(value: String) {
        val endpoint = value.trim().trimEnd('/')
        preferences.edit().putString(KEY_ENDPOINT, endpoint).remove(KEY_LAST_ERROR).apply()
        _state.value = readState()
    }

    fun recordSuccess() {
        preferences.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply()
        _state.value = readState()
    }

    fun recordError(error: String) {
        preferences.edit().putString(KEY_LAST_ERROR, error.take(500)).apply()
        _state.value = readState()
    }

    fun markRegistered(keyId: String, scopes: Set<String>, accountId: String? = null) {
        preferences.edit()
            .putString(KEY_KEY_ID, keyId)
            .putStringSet(KEY_SCOPES, scopes)
            .putBoolean(KEY_REGISTERED, true)
            .apply {
                if (accountId != null) putString(KEY_ACCOUNT_ID, accountId)
            }
            .remove(KEY_LAST_ERROR)
            .apply()
        _state.value = readState()
    }

    fun registrationError(error: String) {
        preferences.edit().putString(KEY_LAST_ERROR, error.take(500)).apply()
        _state.value = readState()
    }

    fun clearRegistration() {
        preferences.edit().putBoolean(KEY_REGISTERED, false).remove(KEY_KEY_ID).remove(KEY_SCOPES).remove(KEY_ACCOUNT_ID).apply()
        _state.value = readState()
    }

    /** Creates a new server-side catalog account. The password is sent only for this TLS request. */
    fun registerAccount(endpoint: String, password: String, displayName: String? = null) {
        accountAccess(endpoint, "/v1/accounts/register", password, displayName)
    }

    /** Links this fresh installation to an existing catalog and lets the caller queue restore. */
    fun restoreAccount(endpoint: String, password: String, displayName: String? = null) {
        accountAccess(endpoint, "/v1/accounts/restore", password, displayName)
    }

    private fun accountAccess(endpoint: String, path: String, password: String, displayName: String?) {
        val normalized = endpoint.trim().trimEnd('/')
        val body = JSONObject().apply {
            put("password", password)
            put("deviceId", deviceIdValue)
            put("keyId", identity.keyId())
            put("publicKey", identity.publicKeyBase64())
            displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL("$normalized$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code ${connection.responseMessage.orEmpty()}")
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .let(::JSONObject)
            val responseDeviceId = json.getString("deviceId")
            val responseKeyId = json.getString("keyId")
            if (responseDeviceId != deviceIdValue || responseKeyId != identity.keyId()) {
                throw IOException("server returned a different device identity")
            }
            markRegistered(
                responseKeyId,
                json.getJSONArray("scopes").let { array -> buildSet { repeat(array.length()) { add(array.getString(it)) } } },
                json.optString("accountId").takeIf { it.isNotBlank() },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readState() = SyncSettingsState(
        endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
        lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastError = preferences.getString(KEY_LAST_ERROR, null),
        registered = preferences.getBoolean(KEY_REGISTERED, false),
        keyId = preferences.getString(KEY_KEY_ID, null),
        scopes = preferences.getStringSet(KEY_SCOPES, emptySet()).orEmpty(),
        accountId = preferences.getString(KEY_ACCOUNT_ID, null),
    )

    private companion object {
        const val PREFERENCES = "rfidhunter_sync"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_REGISTERED = "registered"
        const val KEY_KEY_ID = "key_id"
        const val KEY_SCOPES = "scopes"
        const val KEY_ACCOUNT_ID = "account_id"
    }
}

class SyncScheduler(private val context: Context) {
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<IncrementalSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueRestore() {
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RESTORE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "rfidhunter-incremental-sync"
        const val RESTORE_WORK_NAME = "rfidhunter-catalog-restore"
    }
}

internal fun signedHeaders(
    endpoint: String,
    url: URL,
    method: String,
    bodyHash: String,
    identity: DeviceIdentity,
    deviceId: String,
): Map<String, String> {
    val timestamp = (System.currentTimeMillis() / 1000L).toString()
    val nonce = UUID.randomUUID().toString()
    val endpointPath = URL(endpoint).path.trimEnd('/').ifBlank { "/" }
    val fullPath = url.path
    val path = if (fullPath.startsWith(endpointPath)) fullPath.removePrefix(endpointPath).ifBlank { "/" } else fullPath
    val query = url.query.orEmpty()
    val canonical = listOf(method.uppercase(), path, query, deviceId, timestamp, nonce, bodyHash).joinToString("\n").toByteArray(Charsets.UTF_8)
    return mapOf(
        "X-RFID-Device-Id" to deviceId,
        "X-RFID-Key-Id" to identity.keyId(),
        "X-RFID-Timestamp" to timestamp,
        "X-RFID-Nonce" to nonce,
        "X-RFID-Signature" to identity.sign(canonical),
    )
}

class IncrementalSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val app = appContext.applicationContext as RfidHunterApplication

    override suspend fun doWork(): Result {
        val endpoint = app.syncSettings.state.value.endpoint.trim().trimEnd('/')
        if (endpoint.isBlank()) return Result.success()
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            app.syncSettings.recordError("Адрес сервера должен начинаться с https://")
            return Result.failure()
        }
        val settings = app.syncSettings.state.value
        if (!settings.registered || settings.keyId == null) {
            // The endpoint can be configured before the owner opts into
            // backup. Offline catalog use must remain silent and successful.
            return Result.success()
        }
        val events = app.database.syncOutboxDao().pending(BATCH_SIZE)
        if (events.isEmpty()) return Result.success()
        return try {
            for (event in events) {
                try {
                    uploadPhotoIfNeeded(endpoint, event)
                    postEvent(endpoint, event)
                    app.database.syncOutboxDao().delete(event.operationId)
                    app.syncSettings.recordSuccess()
                } catch (error: Throwable) {
                    app.database.syncOutboxDao().recordFailure(event.operationId, error.message ?: error.javaClass.simpleName)
                    throw error
                }
            }
            if (app.database.syncOutboxDao().pending(BATCH_SIZE).isNotEmpty()) {
                app.syncScheduler.enqueue()
            }
            Result.success()
        } catch (error: RetryableSyncException) {
            app.syncSettings.recordError(error.message ?: "Сервер временно недоступен")
            Result.retry()
        } catch (error: PermanentSyncException) {
            app.syncSettings.recordError(error.message ?: "РЎРµСЂРІРµСЂ РѕС‚РєР»РѕРЅРёР» РґР°РЅРЅС‹Рµ")
            Result.failure()
        } catch (error: IOException) {
            app.syncSettings.recordError(error.message ?: "Сеть недоступна")
            Result.retry()
        } catch (error: Throwable) {
            app.syncSettings.recordError(error.message ?: error.javaClass.simpleName)
            Result.failure()
        }
    }

    private fun uploadPhotoIfNeeded(endpoint: String, event: SyncOutboxEntity) {
        val hash = event.photoSha256 ?: return
        val path = event.photoPath ?: return
        val file = java.io.File(path)
        if (sha256File(path) != hash) throw IOException("photo changed before upload")
        if (!file.isFile) throw IOException("Фото не найдено: ${file.name}")
        val url = URL("$endpoint/v1/files/${Uri.encode(hash)}")
        val connection = open(url, "PUT", "application/octet-stream", endpoint, event.photoSha256)
        try {
            file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
            val code = connection.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_CONFLICT) {
                checkResponse(code, connection)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun postEvent(endpoint: String, event: SyncOutboxEntity) {
        val body = JSONObject().apply {
            put("operationId", event.operationId)
            put("deviceId", app.syncSettings.deviceId)
            put("operation", event.operation)
            put("syncId", event.syncId)
            put("itemId", event.itemId ?: JSONObject.NULL)
            put("name", event.name ?: JSONObject.NULL)
            put("epc", event.epc ?: JSONObject.NULL)
            put("photoSha256", event.photoSha256 ?: JSONObject.NULL)
            put("updatedAt", event.updatedAt)
        }.toString()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val connection = open(URL("$endpoint/v1/sync/events"), "POST", "application/json", endpoint, sha256Bytes(bodyBytes))
        try {
            connection.setRequestProperty("X-RFID-Operation-Id", event.operationId)
            connection.outputStream.use { it.write(bodyBytes) }
            val code = connection.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_CONFLICT) checkResponse(code, connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL, method: String, contentType: String, endpoint: String, bodyHash: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            signedHeaders(endpoint, url, method, bodyHash, app.syncSettings.identity, app.syncSettings.deviceId)
                .forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun checkResponse(code: Int, connection: HttpURLConnection) {
        val message = "HTTP $code ${connection.responseMessage.orEmpty()}"
        if (code == 408 || code == 425 || code == 429 || code >= 500) throw RetryableSyncException(message)
        throw PermanentSyncException(message)
    }

    private class RetryableSyncException(message: String) : IOException(message)
    private class PermanentSyncException(message: String) : IOException(message)

    private companion object {
        const val BATCH_SIZE = 16
    }
}

private fun sha256Bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it) }

/** Pulls the authenticated device's own server snapshot and merges it locally. */
class RestoreWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val app = appContext.applicationContext as RfidHunterApplication

    override suspend fun doWork(): Result {
        val settings = app.syncSettings.state.value
        val endpoint = settings.endpoint.trim().trimEnd('/')
        if (endpoint.isBlank() || !settings.registered || !settings.scopes.contains("RESTORE")) return Result.failure()
        return try {
            val url = URL("$endpoint/v1/sync/items")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                doInput = true
                signedHeaders(endpoint, url, "GET", sha256Bytes(ByteArray(0)), app.syncSettings.identity, app.syncSettings.deviceId)
                    .forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val body = try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    if (code >= 500 || code == 408 || code == 429) throw IOException("HTTP $code")
                    throw PermanentRestoreException("HTTP $code ${connection.responseMessage.orEmpty()}")
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
            mergeSnapshot(org.json.JSONArray(body))
            app.syncSettings.recordSuccess()
            Result.success()
        } catch (error: PermanentRestoreException) {
            app.syncSettings.registrationError(error.message ?: "restore failed")
            Result.failure()
        } catch (error: IOException) {
            app.syncSettings.registrationError(error.message ?: "restore temporarily unavailable")
            Result.retry()
        } catch (error: Throwable) {
            app.syncSettings.registrationError(error.message ?: "restore failed")
            Result.failure()
        }
    }

    private suspend fun mergeSnapshot(rows: org.json.JSONArray) {
        val restored = ArrayList<ItemEntity>()
        val deleted = ArrayList<Pair<String, Long>>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val syncId = row.getString("sync_id")
            val operation = row.getString("operation")
            val updatedAt = row.getLong("updated_at")
            if (operation == "DELETE") {
                deleted += syncId to updatedAt
                continue
            }
            val photoSha = row.optString("photo_sha256").takeIf { it.isNotBlank() && it != "null" }
            val photoPath = photoSha?.let { downloadPhoto(it) }
            restored += ItemEntity(
                id = 0,
                syncId = syncId,
                name = row.optString("name"),
                normalizedName = ru.rfidhunter.app.domain.normalizeText(row.optString("name")),
                epc = row.optString("epc"),
                photoPath = photoPath,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        }
        app.database.withTransaction {
            val items = app.database.itemDao()
            val outbox = app.database.syncOutboxDao()
            restored.forEach { incoming ->
                if (outbox.hasPending(incoming.syncId)) return@forEach
                val existing = items.getBySyncId(incoming.syncId)
                if (existing == null || incoming.updatedAt > existing.updatedAt) {
                    items.upsertRestored(incoming.copy(id = existing?.id ?: 0))
                }
            }
            deleted.forEach { (syncId, updatedAt) ->
                if (outbox.hasPending(syncId)) return@forEach
                val existing = items.getBySyncId(syncId)
                if (existing != null && updatedAt >= existing.updatedAt) items.deleteBySyncId(syncId)
            }
        }
    }

    private fun downloadPhoto(sha256: String): String {
        val directory = java.io.File(applicationContext.filesDir, "photos").apply { mkdirs() }
        val destination = java.io.File(directory, "$sha256.jpg")
        if (destination.isFile) return destination.absolutePath
        val endpoint = app.syncSettings.state.value.endpoint.trim().trimEnd('/')
        val url = URL("$endpoint/v1/files/${Uri.encode(sha256)}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            signedHeaders(endpoint, url, "GET", sha256Bytes(ByteArray(0)), app.syncSettings.identity, app.syncSettings.deviceId)
                .forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val temporary = java.io.File(directory, ".${destination.name}.tmp")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("photo download HTTP $code")
            connection.inputStream.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            if (sha256File(temporary.absolutePath) != sha256) throw IOException("photo hash mismatch")
            if (!temporary.renameTo(destination)) throw IOException("cannot store restored photo")
            return destination.absolutePath
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private class PermanentRestoreException(message: String) : IOException(message)
}
