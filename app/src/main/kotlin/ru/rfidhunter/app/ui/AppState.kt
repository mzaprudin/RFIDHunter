package ru.rfidhunter.app.ui

import ru.rfidhunter.app.domain.Item

enum class PhotoContext {
    NEW_WITH_TAG,
    MANUAL_NEW,
    EDIT_EXISTING,
}

enum class VoiceInputMode {
    SEARCH,
    ADD,
}

sealed interface AppState {
    data object Idle : AppState
    data object InitialChecking : AppState
    data class AddName(
        val pendingEpc: String? = null,
        val renameItem: Item? = null,
    ) : AppState
    data class Camera(val context: PhotoContext, val item: Item? = null, val name: String? = null, val epc: String? = null) : AppState
    data class PhotoReview(val path: String, val context: PhotoContext, val item: Item? = null, val name: String? = null, val epc: String? = null) : AppState
    data class WaitForNewTag(
        val name: String,
        val photoPath: String?,
        val replacingItem: Item? = null,
    ) : AppState
    /** The app is actively recording a short offline voice phrase. */
    data class VoiceListening(
        val mode: VoiceInputMode,
        val pendingEpc: String? = null,
        val renameItem: Item? = null,
        val attempt: Int = 0,
        val isReady: Boolean = false,
    ) : AppState
    /** A recognized phrase must be confirmed before the next action starts. */
    data class VoiceConfirmation(
        val mode: VoiceInputMode,
        val text: String,
        val pendingEpc: String? = null,
        val renameItem: Item? = null,
    ) : AppState
    data object AskSearchCommand : AppState
    data class SearchTarget(val item: Item) : AppState
    data class RfidSearch(val item: Item, val power: Int = 30) : AppState
    data class Found(val item: Item) : AppState
    data class ItemCard(val item: Item) : AppState
    data class Catalog(val page: Int = 0) : AppState
    data class MultipleTags(val message: String = "Найдено несколько меток") : AppState
    data class Message(val text: String, val returnTo: AppState = Idle) : AppState
    data object Service : AppState
    data class Error(val text: String) : AppState
}
