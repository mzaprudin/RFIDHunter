package ru.rfidhunter.app.domain

/** Decides whether the app has been idle for long enough to return home. */
data class InactivityPolicy(val timeoutMs: Long = 60 * 60 * 1_000L) {
    init { require(timeoutMs > 0) }

    fun isExpired(lastActivityMs: Long, nowMs: Long): Boolean =
        nowMs - lastActivityMs >= timeoutMs
}

