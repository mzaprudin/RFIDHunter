package ru.rfidhunter.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ru.rfidhunter.app.domain.normalizeText
import java.io.File
import java.io.FileOutputStream

data class VoiceResult(val text: String, val confidence: Float = 1f)

interface VoiceRecognizer {
    val isAvailable: Boolean
    suspend fun listen(): VoiceResult?
}

class FakeVoiceRecognizer : VoiceRecognizer {
    override val isAvailable: Boolean = true
    var nextText: String = ""
    override suspend fun listen(): VoiceResult? = nextText.takeIf { it.isNotBlank() }?.let { VoiceResult(it) }
}

/**
 * Lightweight Vosk wrapper. The model is optional during development: if it is not copied to
 * app files, the service screen can still drive all UI flows with FakeVoiceRecognizer.
 */
class VoskVoiceRecognizer(private val context: Context) : VoiceRecognizer {
    private val modelPath = File(context.filesDir, "vosk-model")
    override val isAvailable: Boolean
        get() = modelPath.exists() && File(modelPath, "am").exists()

    override suspend fun listen(): VoiceResult? = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        var model: org.vosk.Model? = null
        var recognizer: org.vosk.Recognizer? = null
        var record: AudioRecord? = null
        try {
            model = org.vosk.Model(modelPath.absolutePath)
            recognizer = org.vosk.Recognizer(model, 16_000f)
            val minBuffer = AudioRecord.getMinBufferSize(
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
            val buffer = ByteArray(minBuffer)
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            record.startRecording()
            val start = System.currentTimeMillis()
            var text: String? = null
            while (System.currentTimeMillis() - start < 5_000) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0 && recognizer.acceptWaveForm(buffer, read)) {
                    text = JSONObject(recognizer.result).optString("text").takeIf { it.isNotBlank() }
                    if (text != null) break
                }
            }
            if (text == null) {
                text = JSONObject(recognizer.finalResult).optString("text").takeIf { it.isNotBlank() }
            }
            text?.let { VoiceResult(normalizeText(it), 0.85f) }
        } catch (_: Throwable) {
            null
        } finally {
            record?.let {
                try { it.stop() } catch (_: Throwable) { }
                try { it.release() } catch (_: Throwable) { }
            }
            try { recognizer?.close() } catch (_: Throwable) { }
            try { model?.close() } catch (_: Throwable) { }
        }
    }
}

object VoiceModelInstaller {
    private val installLock = Any()

    suspend fun installFromAssets(context: Context, assetRoot: String = "vosk-model"): Boolean = withContext(Dispatchers.IO) {
        synchronized(installLock) {
            val destination = File(context.filesDir, "vosk-model")
            if (File(destination, "am").exists()) return@synchronized true
            val entries = try { context.assets.list(assetRoot).orEmpty() } catch (_: Throwable) { emptyArray() }
            if (entries.isEmpty()) return@synchronized false
            copyAssetTree(context, assetRoot, destination)
            File(destination, "am").exists()
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        destination.mkdirs()
        context.assets.list(assetPath).orEmpty().forEach { name ->
            val childAsset = "$assetPath/$name"
            val child = File(destination, name)
            val children = context.assets.list(childAsset).orEmpty()
            if (children.isEmpty()) {
                context.assets.open(childAsset).use { input -> FileOutputStream(child).use { output -> input.copyTo(output) } }
            } else {
                copyAssetTree(context, childAsset, child)
            }
        }
    }
}
