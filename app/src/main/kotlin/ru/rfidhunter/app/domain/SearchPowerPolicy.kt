package ru.rfidhunter.app.domain

/**
 * Search starts wide and then narrows only after a strong target signal. If
 * the tag disappears after narrowing, the next step widens the zone again.
 */
data class SearchPowerPolicy(
    val maxPower: Int = 30,
    val minPower: Int = 5,
    val step: Int = 5,
) {
    init {
        require(maxPower >= minPower)
        require(step > 0)
    }

    fun nextPower(current: Int, detected: Boolean, strong: Boolean): Int = when {
        detected && strong -> (current - step).coerceAtLeast(minPower)
        !detected -> (current + step).coerceAtMost(maxPower)
        else -> current
    }
}
