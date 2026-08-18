package ru.rfidhunter.app.hardware

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.InventoryConfig
import ru.rfidhunter.app.domain.TagRead
import ru.rfidhunter.app.domain.TriggerEvent

interface RfidGateway {
    val connectionState: StateFlow<ConnectionState>
    val triggerEvents: Flow<TriggerEvent>
    val tagReads: Flow<TagRead>

    /** Optional Bluetooth address used by the hardware adapter; empty for the mock gateway. */
    var readerAddress: String
        get() = ""
        set(_) {}

    suspend fun connect()
    suspend fun disconnect()
    suspend fun startInventory(config: InventoryConfig)
    suspend fun stopInventory()
    suspend fun setReadPower(power: Int)
    suspend fun setBeeper(enabled: Boolean, frequency: Int, volume: Int)
}
