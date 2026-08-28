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

/** Current visible stage while the app readies an IH25 for a chosen item. */
enum class ReaderPreparationStage {
    SEARCHING,
    CONNECTING,
    PREPARING,
}

/** Explains why a new RFID label must be scanned again. */
sealed interface NewTagWaitReason {
    data object Initial : NewTagWaitReason
    data object NoTag : NewTagWaitReason
    data object MultipleTags : NewTagWaitReason
    data class AlreadyAssigned(val item: Item) : NewTagWaitReason
}

sealed interface AppState {
    data object Idle : AppState
    data object InitialChecking : AppState
    data class AddName(
        val pendingEpc: String? = null,
        val renameItem: Item? = null,
    ) : AppState
    data class Camera(
        val context: PhotoContext,
        val item: Item? = null,
        val name: String? = null,
        val epc: String? = null,
        /** True when this camera was reopened from an existing photo review. */
        val returnToReview: Boolean = false,
        val previousPhotoPath: String? = null,
    ) : AppState
    data class PhotoReview(val path: String, val context: PhotoContext, val item: Item? = null, val name: String? = null, val epc: String? = null) : AppState
    data class WaitForNewTag(
        val name: String,
        val photoPath: String?,
        val replacingItem: Item? = null,
        val reason: NewTagWaitReason = NewTagWaitReason.Initial,
    ) : AppState
    /** Brief completion acknowledgement before returning to the home screen. */
    data class AddSuccess(val name: String) : AppState
    /** The app is actively recording a short offline voice phrase. */
    data class VoiceListening(
        val mode: VoiceInputMode,
        val pendingEpc: String? = null,
        val renameItem: Item? = null,
        val attempt: Int = 0,
        val isReady: Boolean = false,
        val isProcessing: Boolean = false,
        val instruction: String? = null,
        val missingSearchQuery: String? = null,
        val sessionId: Long = 0L,
    ) : AppState
    /** A recognized phrase must be confirmed before the next action starts. */
    data class VoiceConfirmation(
        val mode: VoiceInputMode,
        val text: String,
        val pendingEpc: String? = null,
        val renameItem: Item? = null,
        val suggestedItem: Item? = null,
    ) : AppState
    data object AskSearchCommand : AppState
    /** The selected item is retained while Bluetooth discovery/connection is retried. */
    data class ReaderPreparation(
        val item: Item,
        val stage: ReaderPreparationStage = ReaderPreparationStage.SEARCHING,
    ) : AppState
    data class SearchTarget(val item: Item) : AppState
    data class RfidSearch(
        val item: Item,
        val rssi: Int? = null,
        val signalStrength: Float = 0f,
    ) : AppState
    data class SearchPaused(
        val item: Item,
        val lastDetection: Boolean = false,
        val lastRssi: Int? = null,
        val signalStrength: Float = 0f,
    ) : AppState
    data class ItemCard(val item: Item) : AppState
    data class Catalog(val selectedIndex: Int = 0) : AppState
    data class MultipleTags(val message: String = "Найдено несколько меток") : AppState
    data class Message(
        val text: String,
        val returnTo: AppState = Idle,
        /** Optional title rendered separately for destructive confirmations. */
        val highlightedTitle: String? = null,
    ) : AppState
    data object Service : AppState
    data class Error(val text: String) : AppState
}
