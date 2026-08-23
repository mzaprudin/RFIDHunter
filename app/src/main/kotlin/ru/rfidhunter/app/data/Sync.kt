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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ru.rfidhunter.app.RfidHunterApplication
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class SyncSettingsState(
    val endpoint: String = "",
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)

/** Local configuration only; credentials are intentionally not embedded in the APK. */
class SyncSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val deviceIdValue = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(KEY_DEVICE_ID, it).apply()
    }
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

    private fun readState() = SyncSettingsState(
        endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
        lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastError = preferences.getString(KEY_LAST_ERROR, null),
    )

    private companion object {
        const val PREFERENCES = "rfidhunter_sync"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
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

    private companion object {
        const val WORK_NAME = "rfidhunter-incremental-sync"
    }
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
        if (!file.isFile) throw IOException("Фото не найдено: ${file.name}")
        val url = URL("$endpoint/v1/files/${Uri.encode(hash)}")
        val connection = open(url, "PUT", "application/octet-stream")
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
        val connection = open(URL("$endpoint/v1/sync/events"), "POST", "application/json")
        try {
            connection.setRequestProperty("X-RFID-Operation-Id", event.operationId)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_CONFLICT) checkResponse(code, connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL, method: String, contentType: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("X-RFID-Device-Id", app.syncSettings.deviceId)
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
