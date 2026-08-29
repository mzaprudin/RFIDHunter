package ru.rfidhunter.app.hardware

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.InventoryConfig
import ru.rfidhunter.app.domain.ReaderScanState
import ru.rfidhunter.app.domain.RfidDevice
import ru.rfidhunter.app.domain.TagRead
import ru.rfidhunter.app.domain.TriggerEvent

interface RfidGateway {
    val connectionState: StateFlow<ConnectionState>
    val triggerEvents: Flow<TriggerEvent>
    val tagReads: Flow<TagRead>

    /** Every RSSI observation, including weaker repeats needed by live search. */
    val tagObservations: Flow<TagRead> get() = tagReads

    /** Devices found by the most recent Bluetooth scan. */
    val discoveredDevices: StateFlow<List<RfidDevice>> get() = EMPTY_DEVICES

    /** Progress of the most recent Bluetooth scan. */
    val scanState: StateFlow<ReaderScanState> get() = EMPTY_SCAN_STATE

    /** Last recoverable hardware diagnostic, if any. */
    val diagnosticMessage: StateFlow<String?> get() = EMPTY_DIAGNOSTIC
    val readerReady: StateFlow<Boolean> get() = EMPTY_READER_READY
    /** Configuration used for the currently active/last inventory session. */
    var lastInventoryConfig: InventoryConfig?
        get() = null
        set(_) {}

    /** Optional Bluetooth address used by the hardware adapter; empty for the mock gateway. */
    var readerAddress: String
        get() = ""
        set(_) {}

    suspend fun connect()
    suspend fun disconnect()
    suspend fun scanDevices(timeoutMs: Long = 5_000L): List<RfidDevice> = discoveredDevices.value
    suspend fun startInventory(config: InventoryConfig)
    suspend fun stopInventory()
    suspend fun setReadPower(power: Int)
    suspend fun setBeeper(enabled: Boolean, frequency: Int, volume: Int)

    /** Enables/disables vendor reconnect attempts for the current runtime policy. */
    fun setAutoReconnect(enabled: Boolean) = Unit

    /** Release SDK listeners/resources. Safe to call more than once. */
    fun release() = Unit

    private companion object {
        val EMPTY_DEVICES = MutableStateFlow<List<RfidDevice>>(emptyList())
        val EMPTY_SCAN_STATE = MutableStateFlow(ReaderScanState.IDLE)
        val EMPTY_DIAGNOSTIC = MutableStateFlow<String?>(null)
        val EMPTY_READER_READY = MutableStateFlow(false)
    }
}
