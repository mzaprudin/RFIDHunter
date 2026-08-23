package ru.rfidhunter.app.domain

import kotlin.math.roundToInt

/** Keeps a smoothed raw target RSSI for continuous search at a fixed power. */
class SearchSignalTracker(
    private val staleAfterMs: Long = 900L,
    private val smoothingFactor: Double = 0.24,
) {
    private var smoothedRssi: Double? = null
    private var lastSeenAtMs: Long = 0L

    init {
        require(staleAfterMs > 0L)
        require(smoothingFactor in 0.0..1.0)
    }

    fun observe(rssi: Int, nowMs: Long) {
        if (rssi == 0) return
        val previous = smoothedRssi
        smoothedRssi = if (previous == null || nowMs - lastSeenAtMs > staleAfterMs) {
            rssi.toDouble()
        } else {
            previous + (rssi - previous) * smoothingFactor
        }
        lastSeenAtMs = nowMs
    }

    fun currentRssi(nowMs: Long): Int? {
        val value = smoothedRssi ?: return null
        return value.roundToInt().takeIf { nowMs - lastSeenAtMs <= staleAfterMs }
    }
}
