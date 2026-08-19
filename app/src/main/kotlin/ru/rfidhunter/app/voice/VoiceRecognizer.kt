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

data class CapturedAudio(val pcm: ShortArray, val sampleRate: Int)

/** Captures one phrase with a short ring-buffer pre-roll so the first syllable survives startup. */
class AudioCaptureSession(private val context: Context) {
    companion object {
        private const val TAG = "RfidVoice"
        const val SAMPLE_RATE = 16_000
        // Once speech has actually started, this is the quiet tail needed to
        // decide that the phrase is over. Silence before speech does not use
        // this timer at all and keeps listening until the 10-second timeout.
        private const val SILENCE_MS = 1_100L
        private const val NOISE_REACTIVATION_MS = 250L
        private const val MAX_DURATION_MS = 10_000L
        private const val SPEECH_START_BUFFERS = 3
        private const val NOISE_ADAPTATION = .08
        private const val PRE_ROLL_MS = 280L
        private const val POST_ROLL_MS = 160L
        // Some Huawei Android 9 builds deliver PCM at a fraction of the level
        // seen on reference devices. Keep a small absolute floor, but make
        // the actual decision relative to the continuously measured noise.
        private const val MIN_SPEECH_RMS = 24.0
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
        val buffer = ByteArray(minBuffer)
        val output = ArrayList<Short>(SAMPLE_RATE * 4)
        var speechStarted = false
        var lastSpeechAt = 0L
        var noiseRms = 0.0
        var noiseSamples = 0
        var consecutiveSpeechBuffers = 0
        var speechStartSample = -1
        var lastSpeechSample = -1
        var quietSince = -1L
        var requiringLoudConfirmation = false
        var loudConfirmationBuffers = 0
        var readySent = false
        val startedAt = System.currentTimeMillis()
        try {
            record.startRecording()
            Log.d(TAG, "capture started buffer=$minBuffer state=${record.recordingState}")
            var totalSamples = 0L
            var peakRms = 0.0
            var readErrors = 0
            while (System.currentTimeMillis() - startedAt < MAX_DURATION_MS) {
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
                // Keep the complete PCM stream. The Huawei microphone can be
                // very quiet, so a hard VAD gate must not discard a valid
                // phrase before Vosk/Whisper get a chance to decode it.
                output.addAll(samples.asList())
                // Establish a floor from the first samples, then keep it
                // slowly tracking quiet environmental changes. This means a
                // steady fan/room hum does not become speech just because it
                // is louder than the level measured at startup.
                if (noiseSamples == 0) {
                    noiseRms = rms
                    noiseSamples = 1
                } else if (!speechStarted) {
                    val candidateThreshold = maxOf(MIN_SPEECH_RMS, noiseRms * 1.9)
                    if (rms <= candidateThreshold) {
                        noiseRms = noiseRms * (1.0 - NOISE_ADAPTATION) + rms * NOISE_ADAPTATION
                    }
                    noiseSamples++
                } else if (rms < noiseRms * 1.2) {
                    noiseRms = noiseRms * (1.0 - NOISE_ADAPTATION * .35) + rms * (NOISE_ADAPTATION * .35)
                }
                if (!readySent && noiseSamples >= 2) {
                    readySent = true
                    onReady()
                    Log.d(TAG, "microphone ready noiseRms=${noiseRms.toInt()}")
                }
                // Do not start from one transient. Speech must stay above the
                // current noise floor for several consecutive buffers. There
                // is intentionally no fixed "start after N milliseconds"
                // delay: a user can begin immediately, while silence keeps
                // listening until the 10-second safety timeout.
                val speechThreshold = maxOf(MIN_SPEECH_RMS, noiseRms * 2.0)
                if (!speechStarted) {
                    if (readySent && rms >= speechThreshold) {
                        consecutiveSpeechBuffers++
                        if (consecutiveSpeechBuffers >= SPEECH_START_BUFFERS) {
                            speechStarted = true
                            lastSpeechAt = now
                            speechStartSample = (output.size - samples.size * SPEECH_START_BUFFERS).coerceAtLeast(0)
                            lastSpeechSample = output.size
                            quietSince = -1L
                            requiringLoudConfirmation = false
                            loudConfirmationBuffers = 0
                            Log.d(TAG, "speech started sample=$speechStartSample rms=${rms.toInt()} threshold=${speechThreshold.toInt()}")
                        }
                    } else {
                        consecutiveSpeechBuffers = 0
                    }
                } else {
                    if (rms >= speechThreshold) {
                        val longQuiet = quietSince >= 0L && now - quietSince >= NOISE_REACTIVATION_MS
                        if (requiringLoudConfirmation) {
                            loudConfirmationBuffers++
                            if (loudConfirmationBuffers >= 2) {
                                lastSpeechAt = now
                                lastSpeechSample = output.size
                                requiringLoudConfirmation = false
                                loudConfirmationBuffers = 0
                            }
                        } else if (longQuiet) {
                            // A lone environmental click after a long pause
                            // must not postpone transcription. Require two
                            // consecutive loud buffers before resuming speech.
                            requiringLoudConfirmation = true
                            loudConfirmationBuffers = 1
                        } else {
                            lastSpeechAt = now
                            lastSpeechSample = output.size
                        }
                        quietSince = -1L
                    } else {
                        if (quietSince < 0L) quietSince = now
                        loudConfirmationBuffers = 0
                        if (now - lastSpeechAt >= SILENCE_MS) break
                    }
                }
            }
            Log.d(TAG, "capture finished samples=$totalSamples speech=$speechStarted peakRms=${peakRms.toInt()} readErrors=$readErrors")
            if (!speechStarted || output.isEmpty()) null
            else {
                val preRollSamples = (SAMPLE_RATE * PRE_ROLL_MS / 1_000L).toInt()
                val postRollSamples = (SAMPLE_RATE * POST_ROLL_MS / 1_000L).toInt()
                val from = (speechStartSample - preRollSamples).coerceAtLeast(0)
                val until = (lastSpeechSample + postRollSamples).coerceIn(from + 1, output.size)
                val trimmed = output.subList(from, until).toShortArray()
                Log.d(TAG, "captured speech window samples=${trimmed.size} from=$from until=$until")
                CapturedAudio(normalizeAmplitude(trimmed), SAMPLE_RATE)
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

    override suspend fun listen(onReady: () -> Unit): VoiceResult? = capture.capture(onReady)?.let { transcribe(it.pcm, it.sampleRate) }

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
        if (!isAvailable) return null
        synchronized(modelLock) {
            loadedModel?.let { return it }
            return try { org.vosk.Model(modelPath.absolutePath).also { loadedModel = it } } catch (_: Throwable) { null }
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

    override suspend fun listen(onReady: () -> Unit): VoiceResult? = capture.capture(onReady)?.let { transcribe(it.pcm, it.sampleRate) }

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
    var backend: VoiceBackend = VoiceBackend.WHISPER

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
        onProcessing()
        Log.d("RfidVoice", "Transcribing ${audio.pcm.size} samples whisperAvailable=${whisper.isAvailable} voskAvailable=${vosk.isAvailable}")
        return when (backend) {
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
