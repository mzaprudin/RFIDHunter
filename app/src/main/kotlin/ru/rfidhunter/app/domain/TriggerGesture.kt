package ru.rfidhunter.app.domain

/** Pure gesture classification shared by the physical and on-screen trigger. */
enum class TriggerGesture {
    SHORT,
    LONG,
}

class TriggerGestureClassifier(
    private val longPressMs: Long = 1_000L,
) {
    init { require(longPressMs > 0) }

    fun classify(pressedAtMs: Long, releasedAtMs: Long): TriggerGesture =
        if (releasedAtMs - pressedAtMs >= longPressMs) TriggerGesture.LONG else TriggerGesture.SHORT
}

