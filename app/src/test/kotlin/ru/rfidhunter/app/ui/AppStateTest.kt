package ru.rfidhunter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rfidhunter.app.domain.Item

class AppStateTest {
    @Test
    fun occupiedLabelWaitStateRetainsTheAssignedItem() {
        val assigned = Item(7, "Ключи", "EPC-7")
        val state = AppState.WaitForNewTag(
            name = "Очки",
            photoPath = null,
            reason = NewTagWaitReason.AlreadyAssigned(assigned),
        )

        assertEquals(assigned, (state.reason as NewTagWaitReason.AlreadyAssigned).item)
    }

    @Test
    fun multipleLabelAndSuccessStatesAreExplicit() {
        val waiting = AppState.WaitForNewTag("Очки", null, reason = NewTagWaitReason.MultipleTags)
        val success = AppState.AddSuccess("Очки")

        assertTrue(waiting.reason is NewTagWaitReason.MultipleTags)
        assertEquals("Очки", success.name)
    }

    @Test
    fun searchSignalFormatUsesTypographicMinusAndDashForMissingSignal() {
        assertEquals("−67 dBm", formatSearchRssi(-67))
        assertEquals("— dBm", formatSearchRssi(null))
        assertEquals("— dBm", formatSearchRssi(0))
    }

    @Test
    fun normalizedSignalStrengthFormatsAsWholePercent() {
        assertEquals(0, normalizedSignalStrengthPercent(0f))
        assertEquals(48, normalizedSignalStrengthPercent(0.475f))
        assertEquals(100, normalizedSignalStrengthPercent(1f))
    }

    @Test
    fun touchGateKeyChangesBetweenCatalogueAndCard() {
        val item = Item(7, "Ключи", "EPC-7")

        assertTrue(touchGateKey(AppState.Catalog()) != touchGateKey(AppState.ItemCard(item)))
    }

    @Test
    fun touchGateKeyIgnoresSearchRssiAndCatalogueSelection() {
        val item = Item(7, "Ключи", "EPC-7")
        val weakSignal = AppState.RfidSearch(item, rssi = -78, signalStrength = 0.2f)
        val strongSignal = AppState.RfidSearch(item, rssi = -45, signalStrength = 0.9f)

        assertEquals(touchGateKey(weakSignal), touchGateKey(strongSignal))
        assertEquals(touchGateKey(AppState.Catalog(0)), touchGateKey(AppState.Catalog(4)))
    }

    @Test
    fun touchGateDelayIsOneSecond() {
        assertEquals(1_000L, TOUCH_GATE_DELAY_MS)
    }

    @Test
    fun manualSearchWithoutReadyReaderOpensPreparation() {
        val item = Item(7, "Ключи", "EPC-7")

        assertTrue(manualSearchDestination(item, readerUsable = false) is AppState.ReaderPreparation)
    }

    @Test
    fun readyReaderAdvancesPreparationToSelectedTarget() {
        val item = Item(7, "Ключи", "EPC-7")
        val target = preparedSearchTarget(AppState.ReaderPreparation(item), readerReady = true)

        assertEquals(AppState.SearchTarget(item), target)
    }

    @Test
    fun readyReaderAndMockKeepFastSearchPath() {
        val item = Item(7, "Ключи", "EPC-7")

        assertEquals(AppState.SearchTarget(item), manualSearchDestination(item, readerUsable = true))
        assertTrue(canStartInventory(readerReady = false, isMock = true))
    }

    @Test
    fun leavingPreparationStopsRetryAndInventoryNeedsReader() {
        val item = Item(7, "Ключи", "EPC-7")

        assertTrue(shouldRetryReaderPreparation(AppState.ReaderPreparation(item), readerReady = false))
        assertTrue(!shouldRetryReaderPreparation(AppState.Idle, readerReady = false))
        assertTrue(!canStartInventory(readerReady = false, isMock = false))
    }
}
