package ru.rfidhunter.app.hardware

import android.content.Context
import com.honeywell.rfidservice.EventListener
import com.honeywell.rfidservice.RfidManager
import com.honeywell.rfidservice.TriggerMode
import com.honeywell.rfidservice.rfid.OnTagReadListener
import com.honeywell.rfidservice.rfid.RfidReader
import com.honeywell.rfidservice.rfid.TagAdditionData
import com.honeywell.rfidservice.rfid.TagReadData
import com.honeywell.rfidservice.rfid.TagReadOption
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.InventoryConfig
import ru.rfidhunter.app.domain.TagRead
import ru.rfidhunter.app.domain.TriggerEvent

/** Thin adapter around the proprietary IH25 SDK. It is compiled only by the hardware flavor. */
class HoneywellRfidGateway(private val context: Context) : RfidGateway {
    private val manager = RfidManager.getInstance(context)
    private val preferences = context.getSharedPreferences("rfid_hardware", Context.MODE_PRIVATE)
    private var reader: RfidReader? = null
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _triggerEvents = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 16)
    private val _tagReads = MutableSharedFlow<TagRead>(extraBufferCapacity = 128)

    override val connectionState: StateFlow<ConnectionState> = _connectionState
    override val triggerEvents: SharedFlow<TriggerEvent> = _triggerEvents
    override val tagReads: SharedFlow<TagRead> = _tagReads
    override var readerAddress: String
        get() = preferences.getString(KEY_READER_ADDRESS, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_READER_ADDRESS, value.trim()).apply() }

    private val listener = object : EventListener {
        override fun onDeviceConnected(data: Any?) {
            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onDeviceDisconnected(data: Any?) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onReaderCreated(success: Boolean, rfidReader: RfidReader?) {
            if (success) reader = rfidReader
        }

        override fun onRfidTriggered(trigger: Boolean) {
            _triggerEvents.tryEmit(if (trigger) TriggerEvent.PRESSED else TriggerEvent.RELEASED)
        }

        override fun onTriggerModeSwitched(currentMode: TriggerMode?) = Unit
    }

    init {
        manager.setAutoReconnect(true)
        manager.addEventListener(listener)
        manager.setTriggerMode(TriggerMode.RFID)
    }

    override suspend fun connect() {
        val address = readerAddress
        if (address.isBlank()) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        val connected = manager.connect(address)
        if (connected) manager.createReader() else _connectionState.value = ConnectionState.ERROR
    }

    override suspend fun disconnect() {
        manager.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun startInventory(config: InventoryConfig) {
        val current = reader ?: return
        if (!current.available()) return
        current.setOnTagReadListener(tagListener)
        current.read(TagAdditionData.get("None"), TagReadOption())
    }

    override suspend fun stopInventory() {
        reader?.stopRead()
        reader?.removeOnTagReadListener(tagListener)
    }

    override suspend fun setReadPower(power: Int) {
        val current = reader ?: return
        val antennas = current.getAntennaPower()
        antennas.forEach { it.setReadPower(power) }
        current.setAntennaPower(antennas)
    }

    override suspend fun setBeeper(enabled: Boolean, frequency: Int, volume: Int) {
        manager.setBeeper(enabled, frequency, volume)
    }

    private val tagListener = OnTagReadListener { tags: Array<TagReadData> ->
        tags.forEach { tag -> _tagReads.tryEmit(TagRead(tag.getEpcHexStr(), tag.getRssi())) }
    }

    private companion object {
        const val KEY_READER_ADDRESS = "reader_mac"
    }
}
