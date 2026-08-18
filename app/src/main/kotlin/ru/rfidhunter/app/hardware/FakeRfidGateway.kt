package ru.rfidhunter.app.hardware

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.FakeScenario
import ru.rfidhunter.app.domain.InventoryConfig
import ru.rfidhunter.app.domain.TagRead
import ru.rfidhunter.app.domain.TriggerEvent

class FakeRfidGateway : RfidGateway {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _triggerEvents = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 16)
    private val _tagReads = MutableSharedFlow<TagRead>(extraBufferCapacity = 128)

    override val connectionState: StateFlow<ConnectionState> = _connectionState
    override val triggerEvents: SharedFlow<TriggerEvent> = _triggerEvents
    override val tagReads: SharedFlow<TagRead> = _tagReads

    var scenario: FakeScenario = FakeScenario.NONE
    var customTags: List<TagRead> = emptyList()
    var currentPower: Int = 30
        private set

    override suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        delay(120)
        _connectionState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun startInventory(config: InventoryConfig) {
        currentPower = config.power
        val tags = when (scenario) {
            FakeScenario.NONE -> emptyList()
            FakeScenario.UNKNOWN -> listOf(TagRead("E2000017221101441890DEMO", -48))
            FakeScenario.KNOWN -> listOf(TagRead("E2000017221101441890AB01", -42))
            FakeScenario.MULTIPLE -> listOf(
                TagRead("E2000017221101441890DEMO", -44),
                TagRead("E2000017221101441890DE02", -47),
            )
            FakeScenario.SEARCH_FOUND -> customTags.ifEmpty {
                listOf(TagRead("E2000017221101441890AB01", -38))
            }
        }
        val repeats = if (config.power >= 20) 3 else 2
        repeat(repeats) {
            tags.forEach { _tagReads.emit(it) }
            delay(35)
        }
        if (config.durationMs > 120) delay((config.durationMs - 120).coerceAtMost(400))
    }

    override suspend fun stopInventory() = Unit

    override suspend fun setReadPower(power: Int) {
        currentPower = power
    }

    override suspend fun setBeeper(enabled: Boolean, frequency: Int, volume: Int) = Unit

    fun simulateTriggerPressed() {
        _triggerEvents.tryEmit(TriggerEvent.PRESSED)
    }

    fun simulateTriggerReleased() {
        _triggerEvents.tryEmit(TriggerEvent.RELEASED)
    }

    fun emitTag(epc: String, rssi: Int) {
        _tagReads.tryEmit(TagRead(epc, rssi))
    }
}
