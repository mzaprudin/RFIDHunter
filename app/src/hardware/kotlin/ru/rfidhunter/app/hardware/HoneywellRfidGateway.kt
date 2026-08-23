package ru.rfidhunter.app.hardware

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.honeywell.rfidservice.EventListener
import com.honeywell.rfidservice.RfidManager
import com.honeywell.rfidservice.TriggerMode
import com.honeywell.rfidservice.rfid.OnTagReadListener
import com.honeywell.rfidservice.rfid.RfidReader
import com.honeywell.rfidservice.rfid.TagAdditionData
import com.honeywell.rfidservice.rfid.TagReadData
import com.honeywell.rfidservice.rfid.TagReadOption
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.InventoryConfig
import ru.rfidhunter.app.domain.ReaderScanState
import ru.rfidhunter.app.domain.RfidDevice
import ru.rfidhunter.app.domain.TagRead
import ru.rfidhunter.app.domain.TriggerEvent
import kotlin.coroutines.resume

/** Thin, defensive adapter around the proprietary Honeywell IH25 SDK. */
class HoneywellRfidGateway(private val context: Context) : RfidGateway {
    private val manager = RfidManager.getInstance(context)
    private val preferences = context.getSharedPreferences("rfid_hardware", Context.MODE_PRIVATE)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _triggerEvents = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 16)
    private val _tagReads = MutableSharedFlow<TagRead>(extraBufferCapacity = 128)
    private val _tagObservations = MutableSharedFlow<TagRead>(extraBufferCapacity = 512)
    private val _discoveredDevices = MutableStateFlow<List<RfidDevice>>(emptyList())
    private val _scanState = MutableStateFlow(ReaderScanState.IDLE)
    private val _diagnosticMessage = MutableStateFlow<String?>(null)
    private val _readerReady = MutableStateFlow(false)
    private var reader: RfidReader? = null
    private var inventoryActive = false
    private var appliedReadPower: Int? = null
    override var lastInventoryConfig: InventoryConfig? = null
    private var released = false
    private val sessionTags = LinkedHashMap<String, TagRead>()
    private val scanHandler = Handler(Looper.getMainLooper())

    override val connectionState: StateFlow<ConnectionState> = _connectionState
    override val triggerEvents: SharedFlow<TriggerEvent> = _triggerEvents
    override val tagReads: SharedFlow<TagRead> = _tagReads
    override val tagObservations: SharedFlow<TagRead> = _tagObservations
    override val discoveredDevices: StateFlow<List<RfidDevice>> = _discoveredDevices
    override val scanState: StateFlow<ReaderScanState> = _scanState
    override val diagnosticMessage: StateFlow<String?> = _diagnosticMessage
    override val readerReady: StateFlow<Boolean> = _readerReady

    override var readerAddress: String
        get() = preferences.getString(KEY_READER_ADDRESS, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_READER_ADDRESS, value.trim().uppercase()).apply()
        }

    private val listener = object : EventListener {
        override fun onDeviceConnected(data: Any?) {
            if (released) return
            Log.i(TAG, "IH25 device connected: $data")
            (data as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { readerAddress = it }
            _connectionState.value = ConnectionState.CONNECTED
            _readerReady.value = false
            _diagnosticMessage.value = "Подключено, создаю reader"
            try {
                manager.createReader()
            } catch (error: Throwable) {
                reportError("Не удалось создать reader: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        override fun onDeviceDisconnected(data: Any?) {
            if (released) return
            Log.i(TAG, "IH25 device disconnected: $data")
            stopInventorySafely()
            reader = null
            appliedReadPower = null
            _readerReady.value = false
            _connectionState.value = ConnectionState.DISCONNECTED
            _diagnosticMessage.value = "Считыватель отключён"
        }

        override fun onReaderCreated(success: Boolean, rfidReader: RfidReader?) {
            if (released) return
            Log.i(TAG, "IH25 reader created success=$success reader=$rfidReader")
            if (success && rfidReader != null) {
                reader = rfidReader
                val available = runCatching { rfidReader.available() }.getOrDefault(false)
                _readerReady.value = available
                if (available) {
                    runCatching { rfidReader.setRegion("Russian") }
                        .onSuccess { Log.i(TAG, "RFID region set to Russian") }
                        .onFailure { Log.w(TAG, "Could not set RFID region: ${it.message ?: it.javaClass.simpleName}") }
                    _connectionState.value = ConnectionState.CONNECTED
                    _diagnosticMessage.value = "Reader готов"
                } else {
                    reportError("Reader создан, но пока недоступен")
                }
            } else {
                reader = null
                _readerReady.value = false
                reportError("SDK не создал reader")
            }
        }

        override fun onRfidTriggered(trigger: Boolean) {
            Log.i(TAG, "IH25 physical trigger=${if (trigger) "PRESSED" else "RELEASED"}")
            if (!released) _triggerEvents.tryEmit(if (trigger) TriggerEvent.PRESSED else TriggerEvent.RELEASED)
        }

        override fun onTriggerModeSwitched(currentMode: TriggerMode?) = Unit
    }

    init {
        runCatching {
            manager.setAutoReconnect(true)
            manager.addEventListener(listener)
            manager.setTriggerMode(TriggerMode.RFID)
        }.onFailure { reportError("Ошибка инициализации SDK: ${it.message ?: it.javaClass.simpleName}") }
    }

    override suspend fun scanDevices(timeoutMs: Long): List<RfidDevice> {
        val timeout = timeoutMs.coerceIn(250L, 30_000L)
        if (!hasBluetoothScanPermission()) {
            val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Нужны разрешения Bluetooth scan/connect"
            } else {
                "Нужно разрешение геолокации для Bluetooth-сканирования"
            }
            _scanState.value = ReaderScanState.ERROR
            _diagnosticMessage.value = message
            return emptyList()
        }
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull()
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            _scanState.value = ReaderScanState.ERROR
            _diagnosticMessage.value = "Bluetooth LE недоступен"
            return emptyList()
        }

        _scanState.value = ReaderScanState.SCANNING
        Log.i(TAG, "BLE scan started timeoutMs=$timeout")
        _diagnosticMessage.value = "Сканирую устройства (${timeout / 1_000} сек)"
        val found = LinkedHashMap<String, RfidDevice>()
        return suspendCancellableCoroutine { continuation ->
            lateinit var callback: ScanCallback
            var finished = false
            val finish: () -> Unit = {
                if (!finished) {
                    finished = true
                    runCatching { scanner.stopScan(callback) }
                    val devices = found.values.sortedWith(compareByDescending<RfidDevice> { it.likelyHoneywell }.thenByDescending { it.rssi })
                    _discoveredDevices.value = devices
                _scanState.value = ReaderScanState.COMPLETE
                _diagnosticMessage.value = "Найдено устройств: ${devices.size}"
                Log.i(TAG, "BLE scan complete devices=${devices.size}")
                    if (continuation.isActive) continuation.resume(devices)
                }
            }
            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val address = runCatching { device.address }.getOrNull().orEmpty()
                    if (address.isBlank()) return
                    val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Без имени" }
                    val item = RfidDevice(name, address, result.rssi, isHoneywell(name))
                    val previous = found[address]
                    if (previous == null || item.rssi > previous.rssi || (item.likelyHoneywell && !previous.likelyHoneywell)) found[address] = item
                }

                override fun onScanFailed(errorCode: Int) {
                    if (finished) return
                    finished = true
                    _scanState.value = ReaderScanState.ERROR
                    _diagnosticMessage.value = "Bluetooth scan error: $errorCode"
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }
            try {
                scanner.startScan(callback)
            } catch (error: Throwable) {
                finished = true
                _scanState.value = ReaderScanState.ERROR
                _diagnosticMessage.value = "Не удалось начать сканирование: ${error.message ?: error.javaClass.simpleName}"
                if (continuation.isActive) continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val timeoutRunnable = Runnable { finish() }
            scanHandler.postDelayed(timeoutRunnable, timeout)
            continuation.invokeOnCancellation {
                scanHandler.removeCallbacks(timeoutRunnable)
                if (!finished) {
                    finished = true
                    runCatching { scanner.stopScan(callback) }
                    _scanState.value = ReaderScanState.IDLE
                }
            }
        }
    }

    override suspend fun connect() {
        val address = readerAddress
        if (address.isBlank()) {
            reportError("Не выбран Bluetooth-считыватель")
            return
        }
        if (released) return
        _connectionState.value = ConnectionState.CONNECTING
        _readerReady.value = false
        _diagnosticMessage.value = "Подключаюсь к $address"
        Log.i(TAG, "Connecting to IH25 address=$address")
        try {
            // The SDK can recreate its internal service while the application
            // starts. Re-register immediately before connect (as in the
            // vendor sample) so the callback list cannot be lost or doubled.
            runCatching { manager.removeEventListener(listener) }
            manager.addEventListener(listener)
            // Honeywell's sample deliberately ignores the Boolean return value:
            // connect() starts an asynchronous operation and the definitive
            // result arrives through EventListener.onDeviceConnected (or
            // onDeviceDisconnected).  Treating a transient `false` as an
            // immediate error races the automatic fallback scan and causes a
            // duplicate connection attempt.
            val accepted = manager.connect(address)
            Log.i(TAG, "IH25 connect request accepted=$accepted address=$address")
            _diagnosticMessage.value = "Запрос подключения отправлен"
        } catch (error: Throwable) {
            reportError("Ошибка подключения: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override suspend fun disconnect() {
        stopInventorySafely()
        reader = null
        appliedReadPower = null
        _readerReady.value = false
        runCatching { manager.disconnect() }.onFailure { _diagnosticMessage.value = "Ошибка отключения: ${it.message}" }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun startInventory(config: InventoryConfig) {
        val current = reader
        if (current == null || !_readerReady.value || !runCatching { current.available() }.getOrDefault(false)) {
            _diagnosticMessage.value = "Инвентаризация недоступна: reader ещё не готов"
            return
        }
        try {
            lastInventoryConfig = config
            if (appliedReadPower != config.power) setReadPower(config.power)
            if (appliedReadPower != config.power) {
                _diagnosticMessage.value = "SDK не принял мощность ${config.power} dBm"
                return
            }
            sessionTags.clear()
            current.removeOnTagReadListener(tagListener)
            current.setOnTagReadListener(tagListener)
            // Request only the metadata used by live search. Keeping the
            // payload to EPC + RSSI and disabling the inter-round pause gives
            // the IH25 the highest practical observation cadence.
            val option = TagReadOption().apply {
                setRssi(true)
                setStopPercent(0)
            }
            if (!current.read(TagAdditionData.get("None"), option)) {
                _diagnosticMessage.value = "SDK не запустил инвентаризацию"
                current.removeOnTagReadListener(tagListener)
                return
            }
            inventoryActive = true
            _diagnosticMessage.value = "Инвентаризация запущена"
            Log.i(TAG, "Inventory started power=${config.power} durationMs=${config.durationMs}")
        } catch (error: Throwable) {
            _diagnosticMessage.value = "Ошибка инвентаризации: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    override suspend fun stopInventory() = stopInventorySafely()

    override suspend fun setReadPower(power: Int) {
        val current = reader ?: return
        try {
            val sdkPower = power * SDK_POWER_UNITS_PER_DBM
            val antennas = current.getAntennaPower()
            antennas.forEach { it.setReadPower(sdkPower) }
            current.setAntennaPower(antennas)
            appliedReadPower = power
            Log.i(TAG, "Antenna power set to $power dBm (SDK value $sdkPower)")
        } catch (error: Throwable) {
            _diagnosticMessage.value = "Ошибка мощности антенны: ${error.message ?: error.javaClass.simpleName}"
            Log.w(TAG, "Could not set antenna power to $power", error)
        }
    }

    override suspend fun setBeeper(enabled: Boolean, frequency: Int, volume: Int) {
        runCatching { manager.setBeeper(enabled, frequency, volume) }
            .onFailure { _diagnosticMessage.value = "Ошибка beeper: ${it.message ?: it.javaClass.simpleName}" }
    }

    override fun release() {
        if (released) return
        released = true
        stopInventorySafely()
        runCatching { manager.removeEventListener(listener) }
        reader = null
        appliedReadPower = null
        _readerReady.value = false
    }

    private fun stopInventorySafely() {
        val current = reader ?: return
        // Honeywell's stopRead is idempotent; invoke it even when the local
        // flag was lost during cancellation so foreground-service teardown
        // cannot leave an SDK read running.
        runCatching { current.stopRead() }
        runCatching { current.removeOnTagReadListener(tagListener) }
        inventoryActive = false
    }

    private fun reportError(message: String) {
        _connectionState.value = ConnectionState.ERROR
        _readerReady.value = false
        _diagnosticMessage.value = message
    }

    private fun hasBluetoothScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isHoneywell(name: String): Boolean {
        val normalized = name.replace("-", "").replace(" ", "").uppercase()
        return normalized.contains("HONEYWELL") || normalized.contains("IH25") || normalized.startsWith("IH")
    }

    private val tagListener = OnTagReadListener { tags: Array<TagReadData> ->
        if (released) return@OnTagReadListener
        tags.forEach { tag ->
            val epc = runCatching { tag.getEpcHexStr() }.getOrNull()?.trim().orEmpty()
            if (epc.isBlank()) return@forEach
            val read = TagRead(epc, runCatching { tag.getRssi() }.getOrDefault(0))
            Log.i(TAG, "RFID tag read epc=$epc rssi=${read.rssi}")
            _tagObservations.tryEmit(read)
            val key = epc.uppercase()
            val previous = sessionTags[key]
            if (previous == null || isBetterRssi(read.rssi, previous.rssi)) {
                sessionTags[key] = read
                // Emit the first read and later improvements. This preserves the
                // best signal for the short-check decision without flooding the
                // app with every duplicate EPC callback.
                _tagReads.tryEmit(read)
            }
        }
    }

    private fun isBetterRssi(candidate: Int, previous: Int): Boolean = when {
        previous == 0 -> candidate != 0
        candidate == 0 -> false
        else -> candidate > previous
    }

    private companion object {
        const val TAG = "HoneywellRfidGateway"
        const val KEY_READER_ADDRESS = "reader_mac"
        const val SDK_POWER_UNITS_PER_DBM = 100
    }
}
