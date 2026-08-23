package ru.rfidhunter.app.domain

import kotlin.math.roundToInt

/** Keeps a responsive, lightly smoothed target RSSI for continuous search. */
class SearchSignalTracker(
    private val staleAfterMs: Long = 550L,
    private val smoothingFactor: Double = 0.45,
) {
    private var smoothedRssi: Double? = null
    private var lastSeenAtMs: Long = 0L
    private var strongestSinceReset: Int? = null

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
        strongestSinceReset = maxOf(strongestSinceReset ?: rssi, rssi)
    }

    fun currentRssi(nowMs: Long): Int? {
        val value = smoothedRssi ?: return null
        return value.roundToInt().takeIf { nowMs - lastSeenAtMs <= staleAfterMs }
    }

    fun takeStrongestObservation(): Int? = strongestSinceReset.also { strongestSinceReset = null }
}
