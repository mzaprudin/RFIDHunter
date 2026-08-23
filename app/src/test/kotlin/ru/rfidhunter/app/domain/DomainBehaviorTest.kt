package ru.rfidhunter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBehaviorTest {
    private val catalogue = listOf(
        Item(1, "Очки", "EPC-1"),
        Item(2, "Ключи от квартиры", "EPC-2"),
        Item(3, "Пульт от телевизора", "EPC-3"),
    )

    @Test
    fun parsesBareAndPrefixedSearchCommands() {
        assertEquals(VoiceCommand.Search("очки"), parseVoiceCommand("очки"))
        assertEquals(VoiceCommand.Search("очки"), parseVoiceCommand("НАЙДИ очки"))
        assertEquals(VoiceCommand.Search("ключи"), parseVoiceCommand("поиск ключи"))
        assertNull(parseVoiceCommand("найди"))
    }

    @Test
    fun parsesAddWithAndWithoutName() {
        assertEquals(VoiceCommand.Add("новые очки"), parseVoiceCommand("добавь новые очки"))
        assertEquals(VoiceCommand.Add(null), parseVoiceCommand("добавить"))
    }

    @Test
    fun matchesInflectedAndSmallRecognitionErrors() {
        assertEquals(catalogue[0], findBestItem("очков", catalogue))
        assertEquals(catalogue[1], findBestItem("ключи квартир", catalogue))
        assertEquals(catalogue[2], findBestItem("пулт от телевизора", catalogue))
        assertEquals(CatalogMatchKind.FUZZY, findBestItemMatch("а чки", catalogue)?.kind)
        assertEquals(catalogue[0], findBestItemMatch("а чки", catalogue)?.item)
        assertNull(findBestItem("несуществующая вещь", catalogue))
        assertNull(findBestItem("кувалда", catalogue))
    }

    @Test
    fun searchPowerStartsAtMaximumAndNarrowsOnlyOnStrongSignal() {
        val policy = SearchPowerPolicy(maxPower = 30, minPower = 5, step = 5)
        assertEquals(30, policy.maxPower)
        assertEquals(25, policy.nextPower(30, detected = true, strong = true))
        assertEquals(30, policy.nextPower(25, detected = false, strong = false))
        assertEquals(5, policy.nextPower(5, detected = true, strong = true))
        assertEquals(20, policy.nextPower(20, detected = true, strong = false))
    }

    @Test
    fun searchSignalMapsOnlyValidRssiToVisibleFeedback() {
        val policy = SearchSignalPolicy()
        assertEquals(0f, policy.strength(null))
        assertEquals(0f, policy.strength(0))
        assertTrue(policy.strength(-90) > 0f)
        assertTrue(policy.strength(-62) > policy.strength(-82))
        assertEquals(1f, policy.strength(-35))
    }

    @Test
    fun geigerClicksAccelerateAsTargetSignalGetsStronger() {
        val policy = SearchSignalPolicy()
        assertTrue(policy.clickIntervalMs(0.2f) > policy.clickIntervalMs(0.6f))
        assertTrue(policy.clickIntervalMs(0.6f) > policy.clickIntervalMs(1f))
        assertEquals(policy.slowestClickIntervalMs, policy.clickIntervalMs(0f))
        assertEquals(policy.fastestClickIntervalMs, policy.clickIntervalMs(1f))
    }

    @Test
    fun searchSignalTrackerSmoothsUpdatesAndExpiresStaleSignal() {
        val tracker = SearchSignalTracker(staleAfterMs = 500L, smoothingFactor = 0.5)
        tracker.observe(-70, 1_000L)
        tracker.observe(-50, 1_100L)

        assertEquals(-60, tracker.currentRssi(1_200L))
        assertEquals(-50, tracker.takeStrongestObservation())
        assertNull(tracker.takeStrongestObservation())
        assertNull(tracker.currentRssi(1_601L))
    }

    @Test
    fun searchSignalTrackerRestartsAfterAReadingGap() {
        val tracker = SearchSignalTracker(staleAfterMs = 500L, smoothingFactor = 0.5)
        tracker.observe(-80, 1_000L)
        tracker.observe(-45, 1_600L)

        assertEquals(-45, tracker.currentRssi(1_600L))
    }

    @Test
    fun normalizationIsRussianOfflineSafe() {
        assertEquals("елка", normalizeText("Ёлка!"))
        assertTrue(normalizedTokens("пульт от ТВ").contains("тв"))
    }
}
