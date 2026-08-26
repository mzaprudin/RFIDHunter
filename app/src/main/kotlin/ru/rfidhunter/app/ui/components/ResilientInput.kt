package ru.rfidhunter.app.ui.components

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

internal val LocalScreenTouchEnabled = staticCompositionLocalOf { true }

internal fun Modifier.resilientButtonInput(
    onClick: () -> Unit,
    enabled: Boolean,
    screenTouchEnabled: Boolean,
): Modifier =
    pointerInput(enabled, screenTouchEnabled, onClick) {
        awaitPointerEventScope {
            var lastActionAt = 0L
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                var multiTouch = false
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes
                        .filter { it.id != down.id && it.pressed }
                        .forEach {
                            multiTouch = true
                            it.consume()
                        }
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    primary?.consume()
                    if (primary?.pressed == false || primary == null) break
                }
                val now = System.currentTimeMillis()
                if (enabled && screenTouchEnabled && !multiTouch && now - lastActionAt >= 500L) {
                    lastActionAt = now
                    onClick()
                }
            }
        }
    }.semantics {
        onClick {
            if (!enabled) return@onClick false
            if (screenTouchEnabled) onClick()
            true
        }
    }

/**
 * Lets LazyColumn keep its normal drag gesture. A touch opens a card only if
 * it ends without travelling past the system touch-slop threshold.
 */
internal fun Modifier.catalogCardTapInput(
    onClick: () -> Unit,
    screenTouchEnabled: Boolean,
): Modifier = pointerInput(onClick, screenTouchEnabled) {
    awaitPointerEventScope {
        var lastActionAt = 0L
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved = false
            var multiTouch = false
            while (true) {
                val event = awaitPointerEvent()
                event.changes.filter { it.id != down.id && it.pressed }.forEach { multiTouch = true }
                val primary = event.changes.firstOrNull { it.id == down.id }
                if (primary != null && (primary.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (primary?.pressed == false || primary == null) break
            }
            val now = System.currentTimeMillis()
            if (screenTouchEnabled && !moved && !multiTouch && now - lastActionAt >= 500L) {
                lastActionAt = now
                onClick()
            }
        }
    }
}.semantics {
    onClick {
        if (screenTouchEnabled) onClick()
        true
    }
}
