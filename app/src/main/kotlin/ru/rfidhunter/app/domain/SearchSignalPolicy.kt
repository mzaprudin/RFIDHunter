package ru.rfidhunter.app.domain

import kotlin.math.pow

/**
 * Converts the RSSI of the target tag into user-facing feedback.
 *
 * A detected tag always produces a small visible deflection. A missing or
 * invalid RSSI produces exactly zero so audio feedback can stop immediately.
 */
data class SearchSignalPolicy(
    val weakestRssi: Int = -82,
    val strongestRssi: Int = -42,
    val minimumVisibleStrength: Float = 0.08f,
    val slowestClickIntervalMs: Long = 950L,
    val fastestClickIntervalMs: Long = 90L,
) {
    init {
        require(strongestRssi > weakestRssi)
        require(minimumVisibleStrength in 0f..1f)
        require(slowestClickIntervalMs >= fastestClickIntervalMs)
        require(fastestClickIntervalMs > 0L)
    }

    fun strength(rssi: Int?): Float {
        if (rssi == null || rssi == 0) return 0f
        val normalized = ((rssi - weakestRssi).toFloat() / (strongestRssi - weakestRssi))
            .coerceIn(0f, 1f)
        return minimumVisibleStrength + normalized * (1f - minimumVisibleStrength)
    }

    fun clickIntervalMs(strength: Float): Long {
        val normalized = strength.coerceIn(0f, 1f)
        val acceleration = normalized.toDouble().pow(1.35).toFloat()
        return (slowestClickIntervalMs -
            (slowestClickIntervalMs - fastestClickIntervalMs) * acceleration).toLong()
    }
}
