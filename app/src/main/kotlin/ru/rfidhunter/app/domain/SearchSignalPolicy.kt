package ru.rfidhunter.app.domain

import kotlin.math.pow

/**
 * Converts the RSSI of the target tag into user-facing feedback.
 *
 * A detected tag always produces a small visible deflection. A missing or
 * invalid RSSI produces exactly zero for the resting needle; the audio layer
 * maps that value to its slowest background cadence.
 */
data class SearchSignalPolicy(
    val weakestRssi: Int = -82,
    val strongestRssi: Int = -42,
    val minimumVisibleStrength: Float = 0.08f,
    val slowestClickIntervalMs: Long = 1_100L,
    val fastestClickIntervalMs: Long = 85L,
    val cadenceExponent: Double = 0.55,
) {
    init {
        require(strongestRssi > weakestRssi)
        require(minimumVisibleStrength in 0f..1f)
        require(slowestClickIntervalMs >= fastestClickIntervalMs)
        require(fastestClickIntervalMs > 0L)
        require(cadenceExponent > 0.0)
    }

    fun strength(rssi: Int?): Float {
        if (rssi == null || rssi == 0) return 0f
        val normalized = ((rssi - weakestRssi).toFloat() / (strongestRssi - weakestRssi))
            .coerceIn(0f, 1f)
        return minimumVisibleStrength + normalized * (1f - minimumVisibleStrength)
    }

    fun clickIntervalMs(strength: Float): Long {
        val normalized = strength.coerceIn(0f, 1f)
        // Expand the weak-signal end of the scale so a useful direction
        // change is audible before the user is already next to the item.
        val acceleration = normalized.toDouble().pow(cadenceExponent).toFloat()
        return (slowestClickIntervalMs -
            (slowestClickIntervalMs - fastestClickIntervalMs) * acceleration).toLong()
    }
}
