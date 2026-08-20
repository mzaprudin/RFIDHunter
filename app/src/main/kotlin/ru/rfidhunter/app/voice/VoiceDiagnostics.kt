package ru.rfidhunter.app.voice

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DiagnosticAudio(val pcm: ShortArray, val sampleRate: Int)

/** Stores raw microphone captures so VAD/recognizer changes can be replayed. */
class VoiceDiagnosticStore(private val root: File) {
    init { root.mkdirs() }

    fun save(pcm: ShortArray, sampleRate: Int): File {
        val file = File(root, "capture_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { output ->
            val dataBytes = pcm.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataBytes)
            header.put("WAVEfmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataBytes)
            output.write(header.array())
            val bytes = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach(bytes::putShort)
            output.write(bytes.array())
        }
        return file
    }

    fun latest(): File? = root.listFiles { file -> file.extension.equals("wav", true) }
        ?.maxByOrNull { it.lastModified() }

    fun list(): List<File> = root.listFiles { file -> file.extension.equals("wav", true) }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    fun read(file: File): DiagnosticAudio {
        require(file.isFile) { "Diagnostic recording does not exist: ${file.absolutePath}" }
        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            require(input.read(header) == 44) { "Invalid WAV header" }
            val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") { "Unsupported WAV file" }
            val channels = view.getShort(22).toInt()
            val sampleRate = view.getInt(24)
            val bits = view.getShort(34).toInt()
            require(channels == 1 && bits == 16) { "Only mono PCM16 WAV is supported" }
            val data = input.readBytes()
            val pcm = ShortArray(data.size / 2)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            return DiagnosticAudio(pcm, sampleRate)
        }
    }
}
