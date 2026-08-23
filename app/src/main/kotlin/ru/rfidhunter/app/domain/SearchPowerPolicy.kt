package ru.rfidhunter.app.domain

/** Continuous target search deliberately remains at one stable reader power. */
object SearchInventoryPolicy {
    const val FIXED_POWER_DBM = 30

    fun continuousConfig(durationMs: Long = 0L): InventoryConfig =
        InventoryConfig(power = FIXED_POWER_DBM, durationMs = durationMs)
}
