package ru.rfidhunter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfidDecisionEngineTest {
    private val policy = RfidDecisionPolicy(minimumLeadMarginDb = 8)

    @Test
    fun noReadsStartsSearchScenario() {
        assertEquals(InitialRfidDecision.NoTag, decideInitialTag(emptyList(), policy))
    }

    @Test
    fun oneTagWithoutRssiFallsBackToSearch() {
        val result = decideInitialTag(listOf(TagRead("epc-one", 0)), policy)

        assertEquals(InitialRfidDecision.NoTag, result)
    }

    @Test
    fun oneWeakTagFallsBackToSearch() {
        val result = decideInitialTag(listOf(TagRead("epc-one", -55)), policy)

        assertEquals(InitialRfidDecision.NoTag, result)
    }

    @Test
    fun clearlyStrongerTagWinsOverNearbyNoise() {
        val result = decideInitialTag(
            listOf(
                TagRead("near", -31),
                TagRead("far-1", -67),
                TagRead("far-2", -72),
            ),
            policy,
        )

        assertEquals(
            InitialRfidDecision.Selected(TagRead("near", -31), observations = 1),
            result,
        )
    }

    @Test
    fun strongLeaderIsStillRejectedWhenAllTagsAreTooWeak() {
        val result = decideInitialTag(
            listOf(
                TagRead("far-1", -52),
                TagRead("far-2", -63),
            ),
            policy,
        )

        assertEquals(InitialRfidDecision.NoTag, result)
    }

    @Test
    fun duplicateReadsUseBestRssiAndKeepObservationCount() {
        val result = decideInitialTag(
            listOf(
                TagRead("near", -45),
                TagRead("NEAR", -30),
                TagRead("far", -50),
            ),
            policy,
        )

        assertEquals(
            InitialRfidDecision.Selected(TagRead("near", -30), observations = 2),
            result,
        )
    }

    @Test
    fun insufficientLeadMarginFallsBackToSearchScenario() {
        val result = decideInitialTag(
            listOf(TagRead("one", -42), TagRead("two", -47)),
            policy,
        )

        assertEquals(InitialRfidDecision.Ambiguous, result)
    }

    @Test
    fun equalStrengthTagsAreAmbiguous() {
        val result = decideInitialTag(
            listOf(TagRead("one", -45), TagRead("two", -45), TagRead("three", -44)),
            policy,
        )

        assertTrue(result is InitialRfidDecision.Ambiguous)
    }

    @Test
    fun missingRssiOnOneOfSeveralTagsIsNotTreatedAsAWinningSignal() {
        val result = decideInitialTag(
            listOf(TagRead("one", -30), TagRead("unknown-signal", 0)),
            policy,
        )

        assertEquals(InitialRfidDecision.Ambiguous, result)
    }
}
