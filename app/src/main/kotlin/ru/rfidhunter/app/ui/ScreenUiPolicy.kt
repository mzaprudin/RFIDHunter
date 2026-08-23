package ru.rfidhunter.app.ui

import kotlin.math.roundToInt

/** Screen touches are ignored briefly after every logical screen change. */
internal const val TOUCH_GATE_DELAY_MS = 1_000L

/**
 * Identifies logical screens for the touch gate.
 *
 * Deliberately excludes frequently changing values such as search RSSI,
 * voice progress flags and the selected catalogue row.
 */
internal fun touchGateKey(state: AppState): String = when (state) {
    AppState.Idle -> "idle"
    AppState.InitialChecking -> "initial-check"
    is AppState.AddName -> "add-name-${state.renameItem?.id ?: "new"}"
    is AppState.Camera -> "camera-${state.context}-${state.item?.id ?: "new"}"
    is AppState.PhotoReview -> "photo-review-${state.context}-${state.item?.id ?: "new"}"
    is AppState.WaitForNewTag -> "wait-for-tag-${state.replacingItem?.id ?: "new"}"
    is AppState.AddSuccess -> "add-success"
    is AppState.VoiceListening -> "voice-listening-${state.mode}-${state.renameItem?.id ?: "new"}"
    is AppState.VoiceConfirmation -> "voice-confirmation-${state.mode}-${state.renameItem?.id ?: "new"}"
    AppState.AskSearchCommand -> "ask-search"
    is AppState.ReaderPreparation -> "reader-preparation-${state.item.id}"
    is AppState.SearchTarget -> "search-target-${state.item.id}"
    is AppState.RfidSearch -> "rfid-search-${state.item.id}"
    is AppState.SearchPaused -> "search-paused-${state.item.id}"
    is AppState.ItemCard -> "item-card-${state.item.id}"
    is AppState.Catalog -> "catalog"
    is AppState.MultipleTags -> "multiple-tags"
    is AppState.Message -> "message-${state.highlightedTitle ?: state.text}"
    AppState.Service -> "service"
    is AppState.Error -> "error"
}

/** The manual search path waits for a real reader, except in the mock flavor. */
internal fun manualSearchDestination(item: ru.rfidhunter.app.domain.Item, readerUsable: Boolean): AppState =
    if (readerUsable) AppState.SearchTarget(item) else AppState.ReaderPreparation(item)

/** A retry is meaningful only while its dedicated preparation screen remains open. */
internal fun shouldRetryReaderPreparation(state: AppState, readerReady: Boolean): Boolean =
    state is AppState.ReaderPreparation && !readerReady

/** Continue setup after an unexpected disconnect even when the user is on Idle. */
internal fun shouldContinueReaderSetup(
    state: AppState,
    readerReady: Boolean,
    recoveringDisconnectedReader: Boolean,
): Boolean = !readerReady && (state is AppState.ReaderPreparation || recoveringDisconnectedReader)

/** Reader readiness advances only the preparation state for its selected item. */
internal fun preparedSearchTarget(state: AppState, readerReady: Boolean): AppState.SearchTarget? =
    (state as? AppState.ReaderPreparation)
        ?.takeIf { readerReady }
        ?.let { AppState.SearchTarget(it.item) }

/** Inventory must never be requested from hardware before its reader is ready. */
internal fun canStartInventory(readerReady: Boolean, isMock: Boolean): Boolean = readerReady || isMock

/** These screens treat the physical trigger as a fresh contextual RFID check. */
internal fun triggerStartsInitialCheck(state: AppState): Boolean =
    state == AppState.Idle || state is AppState.Catalog || state is AppState.ItemCard

/** Converts the normalized visual signal into the primary 0–100% scale. */
internal fun normalizedSignalStrengthPercent(strength: Float): Int =
    (strength.coerceIn(0f, 1f) * 100f).roundToInt()

/** User-facing RSSI value, with a typographic minus for readability. */
internal fun formatSearchRssi(rssi: Int?): String = when {
    rssi == null || rssi == 0 -> "— dBm"
    rssi < 0 -> "\u2212${-rssi} dBm"
    else -> "$rssi dBm"
}
