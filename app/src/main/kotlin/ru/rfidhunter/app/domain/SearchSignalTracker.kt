package ru.rfidhunter.app.domain

import kotlin.math.roundToInt

/** Keeps a power-normalized, smoothed target RSSI for continuous search. */
class SearchSignalTracker(
    private val staleAfterMs: Long = 900L,
    private val smoothingFactor: Double = 0.24,
    private val referencePower: Int = 30,
    private val powerCompensationFactor: Double = 0.4,
) {
    private var smoothedReferenceRssi: Double? = null
    private var lastSeenAtMs: Long = 0L
    private var strongestSinceReset: Int? = null

    init {
        require(staleAfterMs > 0L)
        require(smoothingFactor in 0.0..1.0)
        require(powerCompensationFactor >= 0.0)
    }

    fun observe(rssi: Int, nowMs: Long) = observe(rssi, referencePower, nowMs)

    /**
     * Converts the observation to the RSSI expected at [referencePower]. This
     * keeps the visible scale continuous while adaptive search changes power.
     */
    fun observe(rssi: Int, power: Int, nowMs: Long) {
        if (rssi == 0) return
        val referenceRssi = rssi + (referencePower - power) * powerCompensationFactor
        val previous = smoothedReferenceRssi
        smoothedReferenceRssi = if (previous == null || nowMs - lastSeenAtMs > staleAfterMs) {
            referenceRssi.toDouble()
        } else {
            previous + (referenceRssi - previous) * smoothingFactor
        }
        lastSeenAtMs = nowMs
        strongestSinceReset = maxOf(strongestSinceReset ?: rssi, rssi)
    }

    fun currentRssi(nowMs: Long): Int? {
        val value = smoothedReferenceRssi ?: return null
        return value.roundToInt().takeIf { nowMs - lastSeenAtMs <= staleAfterMs }
    }

    fun takeStrongestObservation(): Int? = strongestSinceReset.also { strongestSinceReset = null }
}
