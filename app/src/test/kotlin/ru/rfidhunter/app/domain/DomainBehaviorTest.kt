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
    fun normalizationIsRussianOfflineSafe() {
        assertEquals("елка", normalizeText("Ёлка!"))
        assertTrue(normalizedTokens("пульт от ТВ").contains("тв"))
    }
}
