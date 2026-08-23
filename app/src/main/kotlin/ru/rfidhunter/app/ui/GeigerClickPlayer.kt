package ru.rfidhunter.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.random.Random

/** A short dry click played by the phone during RFID direction finding. */
class GeigerClickPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var loaded = false
    private val soundId: Int

    init {
        val soundFile = File(context.cacheDir, "rfid_geiger_click_v1.wav")
        if (!soundFile.isFile) writeClickWav(soundFile)
        soundPool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        soundId = soundPool.load(soundFile.absolutePath, 1)
    }

    fun play(): Boolean = loaded && soundPool.play(soundId, 1f, 1f, 1, 0, 1f) != 0

    fun release() = soundPool.release()

    private fun writeClickWav(file: File) {
        val sampleRate = 16_000
        val sampleCount = 320
        val pcm = ShortArray(sampleCount)
        val random = Random(0x52464944)
        for (index in pcm.indices) {
            val envelope = exp(-index / 42.0)
            val impulse = if (index < 3) (1.0 - index * 0.22) else 0.0
            val noise = random.nextDouble(-0.72, 0.72)
            pcm[index] = ((impulse + noise) * envelope * Short.MAX_VALUE * 0.72)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        val dataSize = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        pcm.forEach(buffer::putShort)
        file.outputStream().use { it.write(buffer.array()) }
    }
}
