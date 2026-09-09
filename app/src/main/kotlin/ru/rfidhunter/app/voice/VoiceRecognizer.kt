package ru.rfidhunter.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.rfidhunter.app.domain.normalizeText
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

data class VoiceResult(val text: String, val confidence: Float = 1f)

interface VoiceRecognizer {
    val isAvailable: Boolean
    suspend fun prepare(): Boolean = isAvailable
    suspend fun listen(onReady: () -> Unit = {}): VoiceResult?
}

interface PcmVoiceRecognizer : VoiceRecognizer {
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int = AudioCaptureSession.SAMPLE_RATE): VoiceResult?
}

class FakeVoiceRecognizer : VoiceRecognizer {
    override val isAvailable: Boolean = true
    var nextText: String = ""
    override suspend fun listen(onReady: () -> Unit): VoiceResult? {
        onReady()
        return nextText.takeIf { it.isNotBlank() }?.let { VoiceResult(it) }
    }
}

data class CapturedAudio(
    val pcm: ShortArray,
    val sampleRate: Int,
    /** Untrimmed capture, retained for offline VAD diagnostics. */
    val rawPcm: ShortArray = pcm,
    val speechDetected: Boolean = pcm.isNotEmpty(),
)

/** Captures one phrase with a short ring-buffer pre-roll so the first syllable survives startup. */
class AudioCaptureSession(private val context: Context) {
    companion object {
        private const val TAG = "RfidVoice"
        const val SAMPLE_RATE = 16_000
        private const val MAX_DURATION_MS = 10_000L
    }

    suspend fun capture(onReady: () -> Unit = {}): CapturedAudio? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext null
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        // VOICE_RECOGNITION is routed through a vendor DSP on some Huawei
        // builds and may start successfully while returning no useful PCM.
        // MIC is the portable Android source and works with the same offline
        // recognizers (Whisper/Vosk).
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }
        // Process short frames instead of waiting for a vendor-sized buffer.
        // This keeps VAD decisions at ~20 ms even when AudioRecord's minimum
        // buffer is much larger on a particular handset.
        val frameBytes = AdaptiveVoiceActivityDetector(sampleRate = SAMPLE_RATE).frameSamples * 2
        val buffer = ByteArray(minBuffer.coerceAtLeast(frameBytes))
        val output = ArrayList<Short>(SAMPLE_RATE * 4)
        val vad = AdaptiveVoiceActivityDetector(SAMPLE_RATE)
        val pending = ArrayList<Short>(vad.frameSamples * 2)
        var processedSamples = 0
        var readySent = false
        val startedAt = System.currentTimeMillis()
        try {
            record.startRecording()
            Log.d(TAG, "capture started buffer=$minBuffer state=${record.recordingState}")
            var totalSamples = 0L
            var peakRms = 0.0
            var readErrors = 0
            var stopRequested = false
            captureLoop@ while (System.currentTimeMillis() - startedAt < MAX_DURATION_MS) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    readErrors++
                    if (readErrors == 1 || readErrors % 20 == 0) Log.w(TAG, "AudioRecord.read=$count")
                    continue
                }
                val samples = ShortArray(count / 2)
                for (i in samples.indices) {
                    samples[i] = ((buffer[i * 2].toInt() and 0xff) or (buffer[i * 2 + 1].toInt() shl 8)).toShort()
                }
                val now = System.currentTimeMillis()
                val rms = rms(samples)
                totalSamples += samples.size
                if (rms > peakRms) peakRms = rms
                output.addAll(samples.asList())
                pending.addAll(samples.asList())
                while (pending.size >= vad.frameSamples) {
                    val frame = pending.subList(0, vad.frameSamples).toShortArray()
                    pending.subList(0, vad.frameSamples).clear()
                    val decision = vad.process(frame, processedSamples)
                    processedSamples += frame.size
                    if (!readySent) {
                        readySent = true
                        onReady()
                        Log.d(TAG, "microphone ready noiseFloor=${"%.1f".format(java.util.Locale.US, decision.noiseFloorDbfs)} dBFS")
                    }
                    if (decision.speechStarted && decision.state == "ACTIVE" && decision.speechStartSample >= 0) {
                        Log.d(TAG, "speech active sample=${decision.speechStartSample} noise=${"%.1f".format(java.util.Locale.US, decision.noiseFloorDbfs)}dBFS start=${"%.1f".format(java.util.Locale.US, decision.startThresholdDbfs)}dBFS")
                    }
                    if (decision.ended) {
                        stopRequested = true
                        break
                    }
                }
                if (stopRequested) break@captureLoop
            }
            val segment = vad.finish(output.size)
            Log.d(TAG, "capture finished samples=$totalSamples speech=${segment.speechDetected} peakRms=${peakRms.toInt()} readErrors=$readErrors noiseFloor=${"%.1f".format(java.util.Locale.US, vad.noiseFloorDbfs())}dBFS")
            if (output.isEmpty()) null
            else if (!segment.speechDetected) {
                // Keep silence/noise captures for diagnostics, but never send
                // an empty segment to Whisper/Vosk (both can hallucinate text).
                CapturedAudio(ShortArray(0), SAMPLE_RATE, output.toShortArray(), false)
            } else {
                val from = segment.startSample.coerceAtLeast(0)
                val until = segment.endSample.coerceIn(from + 1, output.size)
                val trimmed = output.subList(from, until).toShortArray()
                Log.d(TAG, "captured speech window samples=${trimmed.size} from=$from until=$until quietTail=${vad.finish(output.size).endSample - (segment.endSample - AdaptiveVoiceActivityDetector.POST_ROLL_MS * SAMPLE_RATE / 1_000L)}")
                CapturedAudio(normalizeAmplitude(trimmed), SAMPLE_RATE, output.toShortArray(), true)
            }
        } finally {
            try { record.stop() } catch (_: Throwable) { }
            record.release()
        }
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        samples.forEach { value -> sum += value.toDouble() * value.toDouble() }
        return sqrt(sum / samples.size)
    }

    private fun normalizeAmplitude(samples: ShortArray): ShortArray {
        val peak = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: return samples
        if (peak <= 0 || peak >= 16_000) return samples
        val gain = (16_000.0 / peak).coerceAtMost(16.0)
        Log.d(TAG, "normalizing PCM peak=$peak gain=${"%.2f".format(java.util.Locale.US, gain)}")
        return ShortArray(samples.size) { index ->
            (samples[index] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/** Vosk decoder which can consume the shared PCM captured for Whisper. */
class VoskVoiceRecognizer(private val context: Context) : PcmVoiceRecognizer {
    private val modelPath = File(context.filesDir, "vosk-model")
    private val modelLock = Any()
    @Volatile private var loadedModel: org.vosk.Model? = null
    private val capture = AudioCaptureSession(context)

    override val isAvailable: Boolean
        get() = modelPath.exists() && File(modelPath, "am").exists()

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) { modelOrNull() != null }

    override suspend fun listen(onReady: () -> Unit): VoiceResult? = capture.capture(onReady)
        ?.takeIf { it.speechDetected && it.pcm.isNotEmpty() }
        ?.let { transcribe(it.pcm, it.sampleRate) }

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): VoiceResult? = withContext(Dispatchers.IO) {
        val model = modelOrNull() ?: return@withContext null
        Log.d("RfidVoice", "Vosk transcribe start samples=${pcm.size} rate=$sampleRate")
        try {
            val recognizer = org.vosk.Recognizer(model, sampleRate.toFloat())
            val bytes = ByteArray(pcm.size * 2)
            pcm.forEachIndexed { index, value ->
                bytes[index * 2] = (value.toInt() and 0xff).toByte()
                bytes[index * 2 + 1] = (value.toInt() shr 8 and 0xff).toByte()
            }
            recognizer.acceptWaveForm(bytes, bytes.size)
            val text = JSONObject(recognizer.finalResult).optString("text").takeIf { it.isNotBlank() }
                ?: JSONObject(recognizer.partialResult).optString("partial").takeIf { it.isNotBlank() }
            recognizer.close()
            Log.d("RfidVoice", "Vosk result=${text ?: "<empty>"}")
            text?.let { VoiceResult(normalizeText(it), 0.85f) }
        } catch (error: Throwable) {
            Log.e("RfidVoice", "Vosk transcription failed", error)
            null
        }
    }

    private fun modelOrNull(): org.vosk.Model? {
        loadedModel?.let { return it }
        if (!isAvailable) {
            Log.w("RfidVoice", "Vosk model files missing at ${modelPath.absolutePath}")
            return null
        }
        synchronized(modelLock) {
            loadedModel?.let { return it }
            return try {
                org.vosk.Model(modelPath.absolutePath).also {
                    loadedModel = it
                    Log.i("RfidVoice", "Vosk model ready at ${modelPath.absolutePath}")
                }
            } catch (error: Throwable) {
                Log.e("RfidVoice", "Vosk model initialization failed at ${modelPath.absolutePath}", error)
                null
            }
        }
    }

    fun release() {
        synchronized(modelLock) {
            loadedModel?.close()
            loadedModel = null
        }
    }
}

private object WhisperNative {
    private var loaded = false
    init { try { System.loadLibrary("whisper-jni"); loaded = true } catch (_: Throwable) { } }
    val isLoaded: Boolean get() = loaded
    external fun create(modelPath: String): Long
    external fun transcribe(handle: Long, pcm: ShortArray, sampleRate: Int): String?
    external fun release(handle: Long)
}

class WhisperVoiceRecognizer(private val context: Context, private val variant: String = "q5_1") : PcmVoiceRecognizer {
    private val modelPath = File(context.filesDir, "whisper-model/ggml-base-${variant}.bin")
    private val capture = AudioCaptureSession(context)
    private val prepareMutex = Mutex()
    private var handle: Long = 0L

    override val isAvailable: Boolean get() = WhisperNative.isLoaded && modelPath.isFile

    override suspend fun prepare(): Boolean = prepareMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isAvailable) {
                Log.w("RfidVoice", "Whisper unavailable loaded=${WhisperNative.isLoaded} model=${modelPath.absolutePath} exists=${modelPath.isFile}")
                return@withContext false
            }
            if (handle == 0L) {
                Log.d("RfidVoice", "Loading Whisper model ${modelPath.name} (${modelPath.length()} bytes)")
                handle = WhisperNative.create(modelPath.absolutePath)
                Log.d("RfidVoice", "Whisper model handle=$handle")
            }
            handle != 0L
        }
    }

    override suspend fun listen(onReady: () -> Unit): VoiceResult? = capture.capture(onReady)
        ?.takeIf { it.speechDetected && it.pcm.isNotEmpty() }
        ?.let { transcribe(it.pcm, it.sampleRate) }

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): VoiceResult? = withContext(Dispatchers.Default) {
        if (!prepare() || handle == 0L) return@withContext null
        try {
            val startedAt = System.currentTimeMillis()
            val text = WhisperNative.transcribe(handle, pcm, sampleRate)?.trim()?.takeIf { it.isNotBlank() }
            Log.d("RfidVoice", "Whisper result=${text ?: "<empty>"} durationMs=${System.currentTimeMillis() - startedAt}")
            text?.let { VoiceResult(normalizeText(it), .9f) }
        } catch (error: Throwable) {
            Log.e("RfidVoice", "Whisper transcription failed", error)
            null
        }
    }

    fun release() {
        if (handle != 0L) {
            try { WhisperNative.release(handle) } catch (_: Throwable) { }
            handle = 0L
        }
    }
}

class OfflineVoiceEngine(
    private val context: Context,
    private val whisper: WhisperVoiceRecognizer,
    private val vosk: VoskVoiceRecognizer,
) {
    private val capture = AudioCaptureSession(context)
    private val diagnostics = VoiceDiagnosticStore(File(context.filesDir, "voice-diagnostics"))
    /** Vosk is the production offline recognizer; Whisper is service-only diagnostics. */
    var backend: VoiceBackend = VoiceBackend.VOSK
    var diagnosticsEnabled: Boolean = false
    val diagnosticStore: VoiceDiagnosticStore get() = diagnostics

    suspend fun prepare(): Boolean {
        return when (backend) {
            VoiceBackend.WHISPER -> whisper.prepare()
            VoiceBackend.VOSK -> vosk.prepare()
        }
    }

    suspend fun listen(onReady: () -> Unit = {}, onProcessing: () -> Unit = {}): VoiceResult? {
        val audio = capture.capture(onReady) ?: run {
            Log.w("RfidVoice", "No speech audio captured")
            return null
        }
        if (diagnosticsEnabled) {
            runCatching { diagnostics.save(audio.rawPcm, audio.sampleRate) }
                .onFailure { Log.w("RfidVoice", "Unable to save diagnostic WAV", it) }
        }
        if (!audio.speechDetected || audio.pcm.isEmpty()) {
            Log.d("RfidVoice", "No speech detected; diagnostic capture retained without transcription")
            return null
        }
        onProcessing()
        Log.d("RfidVoice", "Transcribing ${audio.pcm.size} samples whisperAvailable=${whisper.isAvailable} voskAvailable=${vosk.isAvailable}")
        return when (backend) {
            VoiceBackend.WHISPER -> whisper.transcribe(audio.pcm, audio.sampleRate)
            VoiceBackend.VOSK -> vosk.transcribe(audio.pcm, audio.sampleRate)
        }
    }

    suspend fun transcribeDiagnostic(file: File): VoiceResult? = withContext(Dispatchers.Default) {
        val audio = runCatching { diagnostics.read(file) }.getOrNull() ?: return@withContext null
        when (backend) {
            VoiceBackend.WHISPER -> whisper.transcribe(audio.pcm, audio.sampleRate)
            VoiceBackend.VOSK -> vosk.transcribe(audio.pcm, audio.sampleRate)
        }
    }

    fun release() {
        whisper.release()
        vosk.release()
    }
}

enum class VoiceBackend { WHISPER, VOSK }

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

    suspend fun installWhisperFromAssets(context: Context, variant: String = "q5_1"): Boolean = withContext(Dispatchers.IO) {
        synchronized(installLock) {
            val destination = File(context.filesDir, "whisper-model")
            val normalized = if (variant == "q8_0") "q8_0" else "q5_1"
            val fileName = "ggml-base-$normalized.bin"
            val model = File(destination, fileName)
            if (model.isFile && model.length() > 0) return@synchronized true
            destination.mkdirs()
            val temporary = File(destination, "$fileName.copying")
            return@synchronized try {
                context.assets.open("whisper-model/$fileName").use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                }
                if (model.exists()) model.delete()
                temporary.renameTo(model) && model.length() > 0
            } catch (_: Throwable) {
                temporary.delete()
                false
            }
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
            } else copyAssetTree(context, childAsset, child)
        }
    }
}
