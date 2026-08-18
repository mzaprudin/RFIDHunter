package ru.rfidhunter.app.voice

import android.content.Context
import android.media.MediaPlayer
import ru.rfidhunter.app.domain.normalizeText

/**
 * Plays only bundled, prerecorded prompts. There is deliberately no runtime
 * network TTS and no fallback to Android's often poor voice on old phones.
 * The files are generated during development with the ElevenLabs helper
 * script and are optional: if a prompt is absent, the large on-screen text
 * remains the only output.
 */
class OfflinePromptPlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(announcement: String) {
        val asset = assetFor(announcement) ?: return
        val next = try {
            val descriptor = context.assets.openFd(asset)
            MediaPlayer().apply {
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                descriptor.close()
                setOnCompletionListener { completed ->
                    completed.release()
                    if (player === completed) player = null
                }
                setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (player === failed) player = null
                    true
                }
                prepare()
            }
        } catch (_: Throwable) {
            return
        }
        player?.runCatching { stop() }?.getOrNull()
        player?.release()
        player = next
        next.start()
    }

    fun release() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    private fun assetFor(text: String): String? {
        val value = normalizeText(text)
        return when {
            value == "что найти" -> "voice/what_to_find.mp3"
            value == "говорите" -> "voice/listen_now.mp3"
            value.contains("новая метка") && value.contains("как назвать") -> "voice/new_tag_name.mp3"
            value == "как назвать вещь" || value.contains("назвать вещь заново") -> "voice/name_item.mp3"
            value.contains("сделайте фотографию") -> "voice/take_photo.mp3"
            value == "проверьте фотографию" -> "voice/check_photo.mp3"
            value.contains("поднесите одну новую метку") -> "voice/bring_new_tag.mp3"
            value.contains("удерживайте курок") -> "voice/hold_trigger.mp3"
            value.startsWith("вещь найдена") -> "voice/item_found.mp3"
            value == "вещь добавлена" -> "voice/item_added.mp3"
            value == "метка изменена" -> "voice/tag_changed.mp3"
            value == "фотография обновлена" -> "voice/photo_updated.mp3"
            value == "вещь удалена" -> "voice/item_deleted.mp3"
            else -> null
        }
    }
}
