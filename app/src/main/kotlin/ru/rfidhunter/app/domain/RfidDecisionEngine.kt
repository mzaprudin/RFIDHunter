package ru.rfidhunter.app.domain

/**
 * Tunable thresholds for the short, low-power RFID check. RSSI is expressed
 * in dBm, so a larger (less negative) value means a stronger signal.
 */
data class RfidDecisionPolicy(
    val minimumLeadMarginDb: Int = 8,
    /**
     * A low-power check may see a distant tag through reflections.  A tag is
     * eligible for an automatic card/add flow only when its best RSSI is at
     * least this strong. RSSI is negative dBm, so -48 is stronger than -55.
     */
    val minimumRssiDbm: Int = -48,
) {
    init {
        require(minimumLeadMarginDb > 0) { "minimumLeadMarginDb must be positive" }
    }
}

/** Result of choosing a target from the tags seen during one short check. */
sealed interface InitialRfidDecision {
    data object NoTag : InitialRfidDecision
    data object Ambiguous : InitialRfidDecision
    data class Selected(
        val tag: TagRead,
        val observations: Int,
    ) : InitialRfidDecision
}

private data class TagSignal(
    val firstTag: TagRead,
    val observations: Int,
    val bestRssi: Int?,
)

/**
 * Selects a nearby tag without making the user resolve a technical
 * "multiple tags" screen. Duplicate EPC reads are folded together and the
 * strongest observed RSSI for each EPC is used for comparison.
 *
 * A single EPC is selected only when RSSI is available and strong enough for
 * the short-range check. With multiple EPCs, RSSI must be available for the
 * leader and the leader must both clear the proximity threshold and lead the
 * runner-up by the configured margin.
 */
fun decideInitialTag(
    reads: List<TagRead>,
    policy: RfidDecisionPolicy = RfidDecisionPolicy(),
): InitialRfidDecision {
    if (reads.isEmpty()) return InitialRfidDecision.NoTag

    val grouped = LinkedHashMap<String, TagSignal>()
    reads.forEach { read ->
        val epc = read.epc.trim()
        if (epc.isBlank()) return@forEach
        val key = epc.uppercase()
        val previous = grouped[key]
        val validRssi = read.rssi.takeIf { it != 0 }
        if (previous == null) {
            grouped[key] = TagSignal(read.copy(epc = epc), 1, validRssi)
        } else {
            grouped[key] = previous.copy(
                observations = previous.observations + 1,
                bestRssi = when {
                    previous.bestRssi == null -> validRssi
                    validRssi == null -> previous.bestRssi
                    else -> maxOf(previous.bestRssi, validRssi)
                },
            )
        }
    }

    if (grouped.isEmpty()) return InitialRfidDecision.NoTag
    if (grouped.size == 1) {
        val only = grouped.values.single()
        val onlyRssi = only.bestRssi ?: return InitialRfidDecision.NoTag
        if (onlyRssi < policy.minimumRssiDbm) return InitialRfidDecision.NoTag
        return InitialRfidDecision.Selected(
            tag = only.firstTag.copy(rssi = onlyRssi),
            observations = only.observations,
        )
    }

    val ranked = grouped.values
        .filter { it.bestRssi != null }
        .sortedByDescending { it.bestRssi }
    if (ranked.size < 2) return InitialRfidDecision.Ambiguous

    val leader = ranked[0]
    val runnerUp = ranked[1]
    val leaderRssi = leader.bestRssi ?: return InitialRfidDecision.Ambiguous
    val runnerUpRssi = runnerUp.bestRssi ?: return InitialRfidDecision.Ambiguous
    if (leaderRssi < policy.minimumRssiDbm) return InitialRfidDecision.NoTag
    if (leaderRssi - runnerUpRssi < policy.minimumLeadMarginDb) {
        return InitialRfidDecision.Ambiguous
    }

    return InitialRfidDecision.Selected(
        tag = leader.firstTag.copy(rssi = leaderRssi),
        observations = leader.observations,
    )
}

/** Named façade used by higher-level workflows and diagnostics. */
object RfidDecisionEngine {
    fun decide(
        reads: List<TagRead>,
        policy: RfidDecisionPolicy = RfidDecisionPolicy(),
    ): InitialRfidDecision = decideInitialTag(reads, policy)
}
