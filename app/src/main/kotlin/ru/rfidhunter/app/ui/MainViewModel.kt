package ru.rfidhunter.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.rfidhunter.app.RfidHunterApplication
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.ReaderScanState
import ru.rfidhunter.app.domain.RfidDevice
import ru.rfidhunter.app.domain.moveCatalogIndex
import ru.rfidhunter.app.domain.FakeScenario
import ru.rfidhunter.app.domain.InactivityPolicy
import ru.rfidhunter.app.domain.InventoryConfig
import ru.rfidhunter.app.domain.InitialRfidDecision
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.decideInitialTag
import ru.rfidhunter.app.domain.SearchSignalPolicy
import ru.rfidhunter.app.domain.SearchSignalTracker
import ru.rfidhunter.app.domain.SearchInventoryPolicy
import ru.rfidhunter.app.ui.NewTagWaitReason.AlreadyAssigned
import ru.rfidhunter.app.ui.NewTagWaitReason.MultipleTags
import ru.rfidhunter.app.ui.NewTagWaitReason.NoTag
import ru.rfidhunter.app.domain.TagRead
import ru.rfidhunter.app.domain.TriggerEvent
import ru.rfidhunter.app.domain.VoiceCommand
import ru.rfidhunter.app.domain.CatalogMatchKind
import ru.rfidhunter.app.domain.findBestItemMatch
import ru.rfidhunter.app.domain.normalizeText
import ru.rfidhunter.app.domain.parseVoiceCommand
import ru.rfidhunter.app.hardware.FakeRfidGateway
import ru.rfidhunter.app.hardware.RfidGateway
import ru.rfidhunter.app.voice.FakeVoiceRecognizer
import ru.rfidhunter.app.voice.OfflineVoiceEngine
import ru.rfidhunter.app.voice.VoiceBackend
import ru.rfidhunter.app.voice.VoiceModelInstaller
import ru.rfidhunter.app.voice.VoskVoiceRecognizer
import ru.rfidhunter.app.voice.WhisperVoiceRecognizer
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RfidHunterApplication
    private val repository = app.itemRepository
    val items = repository.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingSyncCount = repository.pendingSyncCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val syncSettings = app.syncSettings.state
    val gateway: RfidGateway = app.rfidGateway
    val fakeGateway: FakeRfidGateway? = gateway as? FakeRfidGateway
    val fakeVoice = FakeVoiceRecognizer()

    private var whisperVariant = "q5_1"
    private var whisper = WhisperVoiceRecognizer(application, whisperVariant)
    private val vosk = VoskVoiceRecognizer(application)
    private var voiceEngine = OfflineVoiceEngine(application, whisper, vosk).apply {
        backend = VoiceBackend.VOSK
    }
    private val _voiceBackend = MutableStateFlow(VoiceBackend.VOSK)
    val voiceBackend: StateFlow<VoiceBackend> = _voiceBackend.asStateFlow()

    private val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val _photoCapture = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val photoCapture = _photoCapture.asSharedFlow()
    private val _photoFeedback = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val photoFeedback = _photoFeedback.asSharedFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()

    private var inventoryJob: Job? = null
    private var voiceJob: Job? = null
    private var addSuccessJob: Job? = null
    private var voiceSessionId = 0L
    private var captureReads = false
    private var photoCaptureInProgress = false
    private val readBuffer = mutableListOf<TagRead>()
    private val searchSignalPolicy = SearchSignalPolicy()
    private var triggerPressedAt = 0L
    private var searchResumeJob: Job? = null
    private var lastHardwareTriggerPressedAt = 0L
    private val inactivityPolicy = InactivityPolicy()
    @Volatile private var lastActivityAt = System.currentTimeMillis()

    val connectionState: StateFlow<ConnectionState> = gateway.connectionState
    val discoveredDevices: StateFlow<List<RfidDevice>> = gateway.discoveredDevices
    val readerScanState: StateFlow<ReaderScanState> = gateway.scanState
    val readerDiagnostic: StateFlow<String?> = gateway.diagnosticMessage
    val readerReady: StateFlow<Boolean> = gateway.readerReady
    private val _recentReads = MutableStateFlow<List<TagRead>>(emptyList())
    val recentReads: StateFlow<List<TagRead>> = _recentReads.asStateFlow()
    private var scanJob: Job? = null
    private var automaticSetupJob: Job? = null
    private var readerHasBeenReady = false
    private var recoveringDisconnectedReader = false
    private var automaticRecoverySuppressed = false

    init {
        app.syncScheduler.enqueue()
        viewModelScope.launch { repository.seedIfEmpty() }
        viewModelScope.launch {
            VoiceModelInstaller.installFromAssets(application)
            voiceEngine.prepare()
        }
        viewModelScope.launch {
            gateway.tagReads.collectLatest {
                val merged = LinkedHashMap<String, TagRead>()
                (_recentReads.value + it).forEach { read ->
                    val key = read.epc.uppercase()
                    val previous = merged[key]
                    if (previous == null || (read.rssi != 0 && (previous.rssi == 0 || read.rssi > previous.rssi))) {
                        merged[key] = read
                    }
                }
                _recentReads.value = merged.values.toList().takeLast(64)
                if (captureReads) synchronized(readBuffer) { readBuffer += it }
            }
        }
        viewModelScope.launch {
            gateway.triggerEvents.collectLatest { event ->
                when (event) {
                    TriggerEvent.PRESSED -> {
                        lastHardwareTriggerPressedAt = SystemClock.elapsedRealtime()
                        onTriggerPressed()
                    }
                    TriggerEvent.RELEASED -> onTriggerReleased()
                }
            }
        }
        viewModelScope.launch {
            readerReady.collect { ready ->
                if (ready) {
                    readerHasBeenReady = true
                    recoveringDisconnectedReader = false
                    automaticRecoverySuppressed = false
                    completeReaderPreparation()
                }
            }
        }
        viewModelScope.launch {
            connectionState.collect { connection ->
                if (
                    connection == ConnectionState.DISCONNECTED &&
                    readerHasBeenReady &&
                    !automaticRecoverySuppressed
                ) {
                    recoveringDisconnectedReader = true
                    startAutomaticReaderSetup()
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (inactivityPolicy.isExpired(lastActivityAt, System.currentTimeMillis())) expireInactivity()
            }
        }
    }

    fun connectReader() = viewModelScope.launch {
        automaticSetupJob?.cancel()
        automaticRecoverySuppressed = false
        markActivity()
        emitEvent("Подключаю считыватель")
        gateway.connect()
    }

    /** Start a five-second BLE scan; selecting a row persists its MAC. */
    fun scanReaders(timeoutMs: Long = 5_000L) {
        if (scanJob?.isActive == true || automaticSetupJob?.isActive == true) return
        markActivity()
        scanJob = viewModelScope.launch {
            try {
                gateway.scanDevices(timeoutMs)
            } catch (error: Throwable) {
                emitEvent("Сканирование не удалось: ${error.message ?: error.javaClass.simpleName}")
            }
        }.also { job -> job.invokeOnCompletion { if (scanJob === job) scanJob = null } }
    }

    fun selectReader(device: RfidDevice) {
        markActivity()
        gateway.readerAddress = device.macAddress
        emitEvent("Выбран ${device.name} (${device.macAddress})")
    }

    /**
     * The one BLE discovery/connection workflow shared by startup and the
     * selected-item preparation screen.  Startup makes a single attempt;
     * while [AppState.ReaderPreparation] is visible the same job retries.
     */
    fun startAutomaticReaderSetup() {
        if (fakeGateway != null || automaticSetupJob?.isActive == true) return
        if (readerReady.value && connectionState.value == ConnectionState.CONNECTED) return
        // A manual Service scan must not overlap the automatic scan/connect
        // sequence. It is safe to cancel because this call is a newer request.
        scanJob?.cancel()
        automaticSetupJob = viewModelScope.launch {
            markActivity()
            do {
                val ready = automaticReaderSetupAttempt()
                if (ready || !shouldContinueReaderSetup()) break
                delay(READER_SETUP_RETRY_DELAY_MS)
            } while (isActive && !readerReady.value)
        }.also { job ->
            job.invokeOnCompletion { if (automaticSetupJob === job) automaticSetupJob = null }
        }
    }

    private suspend fun automaticReaderSetupAttempt(): Boolean {
        if (readerReady.value) {
            completeReaderPreparation()
            return true
        }
        updateReaderPreparationStage(ReaderPreparationStage.SEARCHING)
        val savedAddress = gateway.readerAddress
        val devices = runCatching { gateway.scanDevices(READER_SCAN_TIMEOUT_MS) }.getOrElse {
            reportReaderSetupIssue("Не удалось найти считыватель")
            return false
        }
        val likely = devices.filter { it.likelyHoneywell }
        val savedCandidate = savedAddress.takeIf { it.isNotBlank() }
            ?.let { address -> devices.firstOrNull { it.macAddress.equals(address, ignoreCase = true) } }
        val candidate = when {
            savedCandidate != null -> savedCandidate
            // A stored address is still worth trying: an IH25 can be slower
            // than Android's scan callback when waking from sleep.
            savedAddress.isNotBlank() -> RfidDevice("Saved IH25", savedAddress, 0, true)
            likely.size == 1 -> likely.single()
            likely.isEmpty() && devices.size == 1 -> devices.single()
            else -> null
        }
        if (candidate == null) {
            reportReaderSetupIssue(
                if (likely.size > 1 || devices.size > 1) {
                    "Рядом несколько устройств. Откройте сервис для выбора считывателя"
                } else {
                    "Считыватель не найден. Включите IH25 и поднесите его ближе"
                },
            )
            return false
        }

        gateway.readerAddress = candidate.macAddress
        updateReaderPreparationStage(ReaderPreparationStage.CONNECTING)
        emitReaderSetupProgress("Найден ${candidate.name}. Подключаю")
        gateway.connect()
        updateReaderPreparationStage(ReaderPreparationStage.PREPARING)
        val ready = withTimeoutOrNull(READER_READY_TIMEOUT_MS) {
            while (!readerReady.value && connectionState.value != ConnectionState.ERROR) delay(100)
            readerReady.value
        } == true
        if (ready) {
            completeReaderPreparation()
            return true
        }
        // Reset the SDK session before the next five-second retry. Do not do
        // this on coroutine cancellation: Back only cancels preparation.
        runCatching { gateway.disconnect() }
        reportReaderSetupIssue("Считыватель не ответил. Повторяю поиск")
        return false
    }

    private fun shouldContinueReaderSetup(): Boolean = shouldContinueReaderSetup(
        state = _state.value,
        readerReady = readerReady.value,
        recoveringDisconnectedReader = recoveringDisconnectedReader,
    )

    private fun updateReaderPreparationStage(stage: ReaderPreparationStage) {
        val current = _state.value as? AppState.ReaderPreparation ?: return
        if (current.stage != stage) _state.value = current.copy(stage = stage)
    }

    private fun completeReaderPreparation() {
        val target = preparedSearchTarget(_state.value, readerReady.value) ?: return
        _state.value = target
        emitEvent("Ищем ${target.item.name}. Удерживайте курок")
    }

    private fun emitReaderSetupProgress(text: String) {
        // The preparation screen renders its own stage. Avoid speaking the
        // same status every retry, but keep startup feedback for the home UI.
        if (!shouldContinueReaderSetup()) emitEvent(text)
    }

    private fun reportReaderSetupIssue(text: String) {
        if (!shouldContinueReaderSetup()) emitEvent(text)
    }

    /** Backwards-compatible entry point used by the activity after permissions. */
    fun autoConnectSavedReader() = startAutomaticReaderSetup()

    fun retryAutomaticReaderSetup() {
        automaticSetupJob?.cancel()
        automaticRecoverySuppressed = false
        startAutomaticReaderSetup()
    }

    private fun cancelReaderPreparationAndGoIdle() {
        if (!recoveringDisconnectedReader) {
            automaticSetupJob?.cancel()
            automaticSetupJob = null
        }
        _state.value = AppState.Idle
    }

    fun disconnectReader() = viewModelScope.launch {
        automaticRecoverySuppressed = true
        recoveringDisconnectedReader = false
        automaticSetupJob?.cancel()
        markActivity()
        gateway.disconnect()
    }

    /** Hardware-only service action used to verify EPC/RSSI reception. */
    fun startDiagnosticInventory(durationMs: Long = 5_000L) = viewModelScope.launch {
        if (!readerReady.value) {
            emitEvent("Считыватель ещё не готов")
            return@launch
        }
        markActivity()
        _recentReads.value = emptyList()
        emitEvent("Проверяю метки")
        try {
            startInventoryOffMain(InventoryConfig(power = SearchInventoryPolicy.FIXED_POWER_DBM, durationMs = durationMs))
            delay(durationMs)
        } catch (error: Throwable) {
            emitEvent("Ошибка чтения меток: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { stopInventoryOffMain() }
            emitEvent("Проверка меток завершена")
        }
    }

    fun onTriggerPressed() {
        markActivity()
        triggerPressedAt = SystemClock.elapsedRealtime()
        val current = _state.value
        if (triggerStartsInitialCheck(current)) {
            startInitialCheck()
            return
        }
        when (current) {
            is AppState.SearchTarget -> startSearch(current.item)
            is AppState.RfidSearch -> Unit
            is AppState.SearchPaused -> scheduleSearchResume(current)
            is AppState.WaitForNewTag -> startNewTagCheck(current.name, current.photoPath, current.replacingItem)
            is AppState.VoiceListening -> listenVoice()
            AppState.Service -> startInitialCheck()
            else -> emitEvent("Сейчас курок не используется")
        }
    }

    fun onTriggerReleased() {
        markActivity()
        triggerPressedAt = 0L
        searchResumeJob?.cancel()
        searchResumeJob = null
        when (val current = _state.value) {
            is AppState.RfidSearch -> pauseSearch(current)
            is AppState.SearchPaused -> startInitialCheck()
            else -> Unit
        }
    }

    /** Avoid handling the same physical press again after the wake trampoline. */
    fun onTriggerWakeIntent() {
        val now = SystemClock.elapsedRealtime()
        if (lastHardwareTriggerPressedAt == 0L || now - lastHardwareTriggerPressedAt > TRIGGER_WAKE_DEDUP_MS) {
            onTriggerPressed()
        }
    }

    private fun scheduleSearchResume(current: AppState.SearchPaused) {
        searchResumeJob?.cancel()
        val pressedAt = triggerPressedAt
        searchResumeJob = viewModelScope.launch {
            delay(SEARCH_RESUME_HOLD_MS)
            val paused = _state.value as? AppState.SearchPaused
            if (triggerPressedAt == pressedAt && paused?.item?.id == current.item.id) {
                startSearch(current.item)
            }
        }
    }

    private fun pauseSearch(current: AppState.RfidSearch) {
        inventoryJob?.cancel()
        inventoryJob = null
        captureReads = false
        val detected = current.rssi != null
        _state.value = AppState.SearchPaused(
            current.item,
            detected,
            current.rssi,
            current.signalStrength,
        )
        emitEvent("Поиск приостановлен. Удерживайте курок для продолжения")
    }

    private fun startInitialCheck() {
        if (!canStartInventory()) {
            emitEvent("Считыватель отключён")
            return
        }
        if (inventoryJob?.isActive == true) return
        inventoryJob = viewModelScope.launch {
            _state.value = AppState.InitialChecking
            collectInventory(INITIAL_CHECK_POWER_DBM, INITIAL_CHECK_DURATION_MS)
            val reads = synchronized(readBuffer) { readBuffer.toList() }
            when (val decision = decideInitialTag(reads)) {
                InitialRfidDecision.NoTag -> startVoiceInput(VoiceInputMode.SEARCH)
                InitialRfidDecision.Ambiguous -> startVoiceInput(
                    VoiceInputMode.SEARCH,
                    instruction = "Рядом несколько меток. Скажите, какую вещь найти?",
                )
                is InitialRfidDecision.Selected -> {
                    val item = repository.getByEpc(decision.tag.epc)
                    if (item == null) startVoiceInput(VoiceInputMode.ADD, pendingEpc = decision.tag.epc)
                    else {
                        _state.value = AppState.ItemCard(item)
                        emitEvent("Это ${item.name}. Открыта карточка вещи")
                    }
                }
            }
        }
    }

    private fun startNewTagCheck(name: String, photoPath: String?, replacingItem: Item? = null) {
        if (!canStartInventory()) {
            emitEvent("Считыватель отключён")
            return
        }
        if (inventoryJob?.isActive == true) return
        inventoryJob = viewModelScope.launch {
            _state.value = AppState.InitialChecking
            collectInventory(INITIAL_CHECK_POWER_DBM, INITIAL_CHECK_DURATION_MS)
            val reads = synchronized(readBuffer) { readBuffer.toList() }
            when (val decision = decideInitialTag(reads)) {
                is InitialRfidDecision.Selected -> if (repository.getByEpc(decision.tag.epc) == null) {
                    val newEpc = decision.tag.epc
                    if (replacingItem == null) {
                        repository.insert(name, newEpc, photoPath)
                        showAddSuccess(name)
                        emitEvent("Вещь добавлена")
                    } else {
                        val updated = replacingItem.copy(epc = newEpc, name = name, photoPath = photoPath)
                        repository.update(updated)
                        _state.value = AppState.ItemCard(updated)
                        emitEvent("Метка изменена")
                    }
                } else {
                    val assignedItem = repository.getByEpc(decision.tag.epc) ?: return@launch
                    _state.value = AppState.WaitForNewTag(name, photoPath, replacingItem, AlreadyAssigned(assignedItem))
                    emitEvent("Эта метка уже привязана к вещи ${assignedItem.name}. Считайте другую метку")
                }
                InitialRfidDecision.Ambiguous -> {
                    _state.value = AppState.WaitForNewTag(name, photoPath, replacingItem, MultipleTags)
                    emitEvent("Считалось несколько меток. Отойдите вместе с вещью в сторону и попробуйте снова")
                }
                InitialRfidDecision.NoTag -> {
                    _state.value = AppState.WaitForNewTag(name, photoPath, replacingItem, NoTag)
                    emitEvent("Нужна одна новая метка")
                }
            }
        }
    }

    private suspend fun collectInventory(power: Int, duration: Long) {
        synchronized(readBuffer) { readBuffer.clear() }
        captureReads = true
        try {
            startInventoryOffMain(InventoryConfig(power, duration))
            delay(duration)
        } finally {
            try { stopInventoryOffMain() } catch (_: Throwable) { }
            captureReads = false
        }
    }

    private fun startSearch(item: Item) {
        if (!canStartInventory()) {
            emitEvent("Считыватель отключён")
            return
        }
        if (inventoryJob?.isActive == true) return
        _state.value = AppState.RfidSearch(item)
        inventoryJob = viewModelScope.launch {
            val tracker = SearchSignalTracker()
            try {
                val observationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    gateway.tagObservations.collect { read ->
                        if (read.epc.equals(item.epc, ignoreCase = true)) {
                            tracker.observe(read.rssi, SystemClock.elapsedRealtime())
                        }
                    }
                }
                try {
                startInventoryOffMain(SearchInventoryPolicy.continuousConfig(SEARCH_CONTINUOUS_DURATION_MS))
                    while (isActive) {
                        val rssi = tracker.currentRssi(SystemClock.elapsedRealtime())
                        _state.value = AppState.RfidSearch(
                            item = item,
                            rssi = rssi,
                            signalStrength = searchSignalPolicy.strength(rssi),
                        )
                        delay(SEARCH_UI_UPDATE_INTERVAL_MS)
                    }
                } finally { observationJob.cancel() }
            } finally {
                captureReads = false
                try { stopInventoryOffMain() } catch (_: Throwable) { }
            }
        }
    }

    fun finishSearch() {
        markActivity()
        if (_state.value !is AppState.RfidSearch && _state.value !is AppState.SearchPaused) return
        searchResumeJob?.cancel()
        searchResumeJob = null
        inventoryJob?.cancel()
        inventoryJob = null
        _state.value = AppState.Idle
    }

    fun startSearching(item: Item) {
        markActivity()
        when (val destination = manualSearchDestination(item, canStartInventory())) {
            is AppState.SearchTarget -> {
                _state.value = destination
                emitEvent("Ищем ${item.name}. Удерживайте курок")
            }
            is AppState.ReaderPreparation -> {
                _state.value = destination
                startAutomaticReaderSetup()
            }
            else -> error("Unexpected manual-search destination: $destination")
        }
    }

    fun submitVoice(text: String) {
        markActivity()
        val value = normalizeText(text)
        when (val current = _state.value) {
            is AppState.VoiceListening -> handleRecognizedVoice(current, text)
            is AppState.VoiceConfirmation -> Unit
            is AppState.AddName -> if (value.isNotBlank()) {
                _state.value = AppState.VoiceConfirmation(VoiceInputMode.ADD, text.trim(), current.pendingEpc, current.renameItem)
            }
            AppState.AskSearchCommand -> handleRecognizedVoice(AppState.VoiceListening(VoiceInputMode.SEARCH), value)
            else -> emitEvent("Голосовая команда сейчас не ожидается")
        }
    }

    fun beginAddByVoice() {
        markActivity()
        startVoiceInput(VoiceInputMode.ADD)
    }

    fun openCatalog() {
        markActivity()
        voiceJob?.cancel()
        voiceJob = null
        _state.value = AppState.Catalog(0)
    }

    fun catalogPage(delta: Int) {
        val current = _state.value as? AppState.Catalog ?: return
        val selected = moveCatalogIndex(current.selectedIndex, delta, items.value.size)
        _state.value = current.copy(selectedIndex = selected)
        markActivity()
    }

    fun selectCatalogItem(item: Item) {
        markActivity()
        _state.value = AppState.ItemCard(item)
        emitEvent("Открыта карточка: ${item.name}")
    }

    fun changeTag(item: Item) {
        markActivity()
        _state.value = AppState.WaitForNewTag(item.name, item.photoPath, replacingItem = item)
        emitEvent("Поднесите одну новую метку и нажмите курок")
    }

    fun rename(item: Item) {
        markActivity()
        startVoiceInput(VoiceInputMode.ADD, renameItem = item)
    }

    fun retakePhoto(item: Item) {
        markActivity()
        _state.value = AppState.Camera(PhotoContext.EDIT_EXISTING, item = item)
        emitEvent("Сделайте новую фотографию кнопкой громкости")
    }

    fun delete(item: Item) {
        markActivity()
        _state.value = AppState.Message(
            text = "Удалить эту вещь?",
            returnTo = AppState.ItemCard(item),
            highlightedTitle = item.name,
        )
        emitEvent("Удаление требует подтверждения")
    }

    fun confirmMessage(confirmed: Boolean) {
        markActivity()
        val current = _state.value as? AppState.Message ?: return
        if (!confirmed) {
            _state.value = current.returnTo
            return
        }
        if (current.returnTo is AppState.VoiceListening) {
            _state.value = current.returnTo
            return
        }
        val item = (current.returnTo as? AppState.ItemCard)?.item ?: return
        viewModelScope.launch {
            repository.delete(item)
            _state.value = AppState.Idle
            emitEvent("Вещь удалена")
        }
    }

    fun onPhotoCaptured(path: String) {
        photoCaptureInProgress = false
        markActivity()
        val current = _state.value as? AppState.Camera ?: return
        _state.value = AppState.PhotoReview(path, current.context, current.item, current.name, current.epc)
        emitEvent("Проверьте фотографию")
    }

    fun onPhotoCaptureFailed() {
        photoCaptureInProgress = false
        markActivity()
        emitEvent("Не удалось сохранить фотографию. Повторите")
    }

    fun requestPhotoCapture() {
        photoCaptureInProgress = true
        markActivity()
        _photoCapture.tryEmit(Unit)
    }

    fun requestPhotoCaptureWithFeedback() {
        photoCaptureInProgress = true
        markActivity()
        _photoFeedback.tryEmit(Unit)
        _photoCapture.tryEmit(Unit)
    }

    fun redoPhoto() {
        markActivity()
        val current = _state.value as? AppState.PhotoReview ?: return
        _state.value = AppState.Camera(current.context, current.item, current.name, current.epc)
    }

    fun acceptPhoto() {
        markActivity()
        val current = _state.value as? AppState.PhotoReview ?: return
        when (current.context) {
            PhotoContext.NEW_WITH_TAG -> viewModelScope.launch {
                val name = current.name.orEmpty()
                repository.insert(name, current.epc.orEmpty(), current.path)
                showAddSuccess(name)
                emitEvent("Вещь добавлена")
            }
            PhotoContext.MANUAL_NEW -> _state.value = AppState.WaitForNewTag(current.name.orEmpty(), current.path)
            PhotoContext.EDIT_EXISTING -> viewModelScope.launch {
                val updated = current.item?.copy(photoPath = current.path)
                if (updated != null) repository.update(updated)
                _state.value = updated?.let(AppState::ItemCard) ?: AppState.Idle
                emitEvent("Фотография обновлена")
            }
        }
    }

    fun onVolumeKey(delta: Int = 0) {
        markActivity()
        when (_state.value) {
            is AppState.Camera -> requestPhotoCaptureWithFeedback()
            is AppState.Catalog -> catalogPage(delta)
            else -> Unit
        }
    }

    fun goBack() {
        markActivity()
        when (val current = _state.value) {
            AppState.Idle -> Unit
            AppState.InitialChecking -> cancelInventoryAndGoIdle()
            is AppState.AddName -> _state.value = current.renameItem?.let(AppState::ItemCard) ?: AppState.Idle
            is AppState.Camera -> _state.value = if (current.context == PhotoContext.EDIT_EXISTING) current.item?.let(AppState::ItemCard) ?: AppState.Idle else AppState.Idle
            is AppState.PhotoReview -> _state.value = AppState.Camera(current.context, current.item, current.name, current.epc)
            is AppState.WaitForNewTag -> _state.value = current.replacingItem?.let(AppState::ItemCard) ?: AppState.Idle
            is AppState.AddSuccess -> {
                addSuccessJob?.cancel()
                _state.value = AppState.Idle
            }
            is AppState.VoiceListening -> {
                voiceJob?.cancel()
                _state.value = current.renameItem?.let(AppState::ItemCard) ?: AppState.Idle
            }
            is AppState.VoiceConfirmation -> _state.value = current.renameItem?.let(AppState::ItemCard) ?: AppState.Idle
            is AppState.ReaderPreparation -> cancelReaderPreparationAndGoIdle()
            AppState.AskSearchCommand, is AppState.SearchTarget, is AppState.SearchPaused, is AppState.RfidSearch -> cancelInventoryAndGoIdle()
            is AppState.ItemCard, is AppState.Catalog, is AppState.MultipleTags -> _state.value = AppState.Idle
            is AppState.Message -> _state.value = current.returnTo
            AppState.Service, is AppState.Error -> _state.value = AppState.Idle
        }
    }

    fun openService() {
        markActivity()
        voiceJob?.cancel()
        inventoryJob?.cancel()
        captureReads = false
        _state.value = AppState.Service
    }
    fun closeService() { markActivity(); _state.value = AppState.Idle }

    fun setBackupEndpoint(endpoint: String) {
        markActivity()
        app.syncSettings.setEndpoint(endpoint)
        app.syncScheduler.enqueue()
        emitEvent(if (endpoint.trim().isBlank()) "Адрес синхронизации очищен" else "Адрес синхронизации сохранён")
    }

    fun syncNow() {
        markActivity()
        app.syncScheduler.enqueue()
        emitEvent("Синхронизация поставлена в очередь")
    }

    fun setFakeScenario(scenario: FakeScenario) { markActivity(); fakeGateway?.scenario = scenario }
    fun setFakeCustomTag(epc: String, rssi: Int) { markActivity(); fakeGateway?.customTags = listOf(TagRead(epc, rssi)) }
    fun simulateTriggerPress() = onTriggerPressed()
    fun simulateTriggerRelease() = onTriggerReleased()
    fun simulateConnect() = connectReader()
    fun simulateDisconnect() = disconnectReader()
    fun readerAddress(): String = gateway.readerAddress
    fun setReaderAddress(address: String) { markActivity(); gateway.readerAddress = address.trim() }

    fun currentWhisperVariant(): String = whisperVariant
    fun currentVoiceBackend(): VoiceBackend = voiceEngine.backend

    fun diagnosticsEnabled(): Boolean = voiceEngine.diagnosticsEnabled

    fun setDiagnosticsEnabled(enabled: Boolean) {
        voiceEngine.diagnosticsEnabled = enabled
        emitEvent(if (enabled) "Диагностическая запись включена" else "Диагностическая запись выключена")
    }

    fun diagnosticRecordings(): List<File> = voiceEngine.diagnosticStore.list()

    fun transcribeDiagnostic(file: File) = viewModelScope.launch {
        emitEvent("Повторно распознаю ${file.name}")
        val result = voiceEngine.transcribeDiagnostic(file)
        emitEvent(result?.text?.let { "Результат: $it" } ?: "Не удалось распознать запись")
    }

    fun setVoiceBackend(backend: VoiceBackend) {
        if (voiceEngine.backend == backend) return
        markActivity()
        voiceJob?.cancel()
        voiceEngine.backend = backend
        _voiceBackend.value = backend
        viewModelScope.launch {
            when (backend) {
                VoiceBackend.WHISPER -> VoiceModelInstaller.installWhisperFromAssets(getApplication(), whisperVariant)
                VoiceBackend.VOSK -> VoiceModelInstaller.installFromAssets(getApplication())
            }
            voiceEngine.prepare()
        }
        emitEvent(if (backend == VoiceBackend.WHISPER) "Распознавание: Whisper base q5_1" else "Распознавание: Vosk")
    }

    fun listenVoice(): Job {
        voiceJob?.cancel()
        val listening = _state.value as? AppState.VoiceListening ?: return viewModelScope.launch { }
        if (listening.isReady) _state.value = listening.copy(isReady = false)
        val job = viewModelScope.launch {
            val startedFor = _state.value as? AppState.VoiceListening ?: return@launch
            when (voiceEngine.backend) {
                VoiceBackend.WHISPER -> VoiceModelInstaller.installWhisperFromAssets(getApplication(), whisperVariant)
                VoiceBackend.VOSK -> VoiceModelInstaller.installFromAssets(getApplication())
            }
            if (!voiceEngine.prepare()) {
                emitEvent("Выбранная офлайн-модель недоступна")
                return@launch
            }
            val result = voiceEngine.listen(
                onReady = {
                    val latest = _state.value as? AppState.VoiceListening
                    if (latest != null && latest.sessionId == startedFor.sessionId && latest.mode == startedFor.mode && latest.pendingEpc == startedFor.pendingEpc && latest.renameItem?.id == startedFor.renameItem?.id && latest.attempt == startedFor.attempt) {
                        _state.value = latest.copy(isReady = true, isProcessing = false)
                    }
                    emitEvent("Слушаю")
                },
                onProcessing = {
                    val latest = _state.value as? AppState.VoiceListening
                    if (latest != null && latest.sessionId == startedFor.sessionId && latest.mode == startedFor.mode && latest.attempt == startedFor.attempt) {
                        _state.value = latest.copy(isReady = false, isProcessing = true)
                    }
                    emitEvent("Распознаю речь")
                },
            )
            if (result != null) {
                val latest = _state.value as? AppState.VoiceListening
                if (latest?.sessionId == startedFor.sessionId) submitVoice(result.text)
            } else {
                emitEvent("Не удалось распознать. Скажите ещё раз")
                delay(700)
                val latest = _state.value as? AppState.VoiceListening
                if (latest != null && latest.sessionId == startedFor.sessionId && latest.attempt < 2) {
                    _state.value = latest.copy(attempt = latest.attempt + 1, isReady = false, isProcessing = false)
                } else if (latest != null && latest.sessionId == startedFor.sessionId) {
                    _state.value = AppState.Message(
                        "Речь не распознана. Повторить?",
                        latest.copy(attempt = 0, isReady = false, isProcessing = false),
                    )
                }
            }
        }
        voiceJob = job
        job.invokeOnCompletion { if (voiceJob === job) voiceJob = null }
        return job
    }

    fun submitServiceVoice(text: String) {
        fakeVoice.nextText = text
        if (_state.value is AppState.Service) handleRecognizedVoice(AppState.VoiceListening(VoiceInputMode.SEARCH), text)
        else submitVoice(text)
    }

    fun editVoiceConfirmation() {
        markActivity()
        val current = _state.value as? AppState.VoiceConfirmation ?: return
        startVoiceInput(current.mode, current.pendingEpc, current.renameItem)
    }

    fun acceptVoiceConfirmation() {
        markActivity()
        val current = _state.value as? AppState.VoiceConfirmation ?: return
        when (current.mode) {
            VoiceInputMode.SEARCH -> {
                if (current.suggestedItem != null) {
                    startSearching(current.suggestedItem)
                    return
                }
                val cachedItems = items.value
                if (cachedItems.isNotEmpty()) {
                    openRecognizedSearch(current.text, cachedItems)
                } else viewModelScope.launch {
                    openRecognizedSearch(current.text, repository.items.first())
                }
            }
            VoiceInputMode.ADD -> {
                val name = current.text.trim()
                if (name.isBlank()) startVoiceInput(VoiceInputMode.ADD, current.pendingEpc, current.renameItem)
                else if (current.renameItem != null) {
                    viewModelScope.launch {
                        val updated = current.renameItem.copy(name = name)
                        repository.update(updated)
                        _state.value = AppState.ItemCard(updated)
                        emitEvent("Название изменено")
                    }
                } else {
                    val context = if (current.pendingEpc == null) PhotoContext.MANUAL_NEW else PhotoContext.NEW_WITH_TAG
                    _state.value = AppState.Camera(context, name = name, epc = current.pendingEpc)
                    emitEvent("Сделайте фотографию кнопкой громкости")
                }
            }
        }
    }

    private fun openRecognizedSearch(text: String, candidates: List<Item>) {
        val match = findBestItemMatch(text, candidates)
        if (match == null) {
            val missingQuery = text.trim()
            startVoiceInput(
                mode = VoiceInputMode.SEARCH,
                instruction = "В каталоге отсутствует вещь «$missingQuery». Назовите другую вещь.",
                missingSearchQuery = missingQuery,
            )
        } else if (match.kind == CatalogMatchKind.FUZZY) {
            _state.value = AppState.VoiceConfirmation(
                mode = VoiceInputMode.SEARCH,
                text = text.trim(),
                suggestedItem = match.item,
            )
            emitEvent("Я услышал «${text.trim()}». Возможно, вы имели в виду «${match.item.name}»?")
        } else {
            startSearching(match.item)
        }
    }

    private fun startVoiceInput(
        mode: VoiceInputMode,
        pendingEpc: String? = null,
        renameItem: Item? = null,
        instruction: String? = null,
        missingSearchQuery: String? = null,
    ) {
        voiceJob?.cancel()
        _state.value = AppState.VoiceListening(
            mode = mode,
            pendingEpc = pendingEpc,
            renameItem = renameItem,
            instruction = instruction,
            missingSearchQuery = missingSearchQuery,
            sessionId = ++voiceSessionId,
        )
        emitEvent(
            instruction ?: when {
                mode == VoiceInputMode.ADD && renameItem == null ->
                    "Добавляем новую вещь. Скажите, как ее назвать?"
                mode == VoiceInputMode.SEARCH -> "Скажите, что найти?"
                else -> "Изменяем название вещи «${renameItem?.name}». Скажите новое название."
            },
        )
    }

    private fun handleRecognizedVoice(input: AppState.VoiceListening, text: String) {
        val raw = text.trim()
        if (normalizeText(raw).isBlank()) {
            startVoiceInput(input.mode, input.pendingEpc, input.renameItem)
            return
        }
        when (input.mode) {
            VoiceInputMode.SEARCH -> when (val command = parseVoiceCommand(raw)) {
                is VoiceCommand.Search -> {
                    val cachedItems = items.value
                    if (cachedItems.isNotEmpty()) {
                        openRecognizedSearch(command.query, cachedItems)
                    } else viewModelScope.launch {
                        openRecognizedSearch(command.query, repository.items.first())
                    }
                }
                is VoiceCommand.Add -> if (command.name.isNullOrBlank()) startVoiceInput(VoiceInputMode.ADD)
                else _state.value = AppState.VoiceConfirmation(VoiceInputMode.ADD, command.name)
                null -> startVoiceInput(VoiceInputMode.SEARCH)
            }
            VoiceInputMode.ADD -> {
                val name = when (val command = parseVoiceCommand(raw)) {
                    is VoiceCommand.Add -> command.name
                    else -> raw
                }
                if (name.isNullOrBlank()) startVoiceInput(VoiceInputMode.ADD, input.pendingEpc, input.renameItem)
                else _state.value = AppState.VoiceConfirmation(VoiceInputMode.ADD, name, input.pendingEpc, input.renameItem)
            }
        }
    }

    private fun markActivity() { lastActivityAt = System.currentTimeMillis() }

    private fun showAddSuccess(name: String) {
        addSuccessJob?.cancel()
        val success = AppState.AddSuccess(name)
        _state.value = success
        addSuccessJob = viewModelScope.launch {
            delay(ADD_SUCCESS_DURATION_MS)
            if (_state.value == success) _state.value = AppState.Idle
        }
    }

    private fun expireInactivity() {
        if (photoCaptureInProgress || _state.value is AppState.ReaderPreparation) {
            markActivity()
            return
        }
        inventoryJob?.cancel()
        searchResumeJob?.cancel()
        voiceJob?.cancel()
        addSuccessJob?.cancel()
        captureReads = false
        viewModelScope.launch { try { stopInventoryOffMain() } catch (_: Throwable) { } }
        _state.value = AppState.Idle
        markActivity()
    }

    private fun cancelInventoryAndGoIdle() {
        inventoryJob?.cancel()
        inventoryJob = null
        searchResumeJob?.cancel()
        searchResumeJob = null
        captureReads = false
        viewModelScope.launch { try { stopInventoryOffMain() } catch (_: Throwable) { } }
        _state.value = AppState.Idle
    }

    private fun canStartInventory(): Boolean = canStartInventory(readerReady.value, fakeGateway != null)

    private suspend fun startInventoryOffMain(config: InventoryConfig) {
        withContext(Dispatchers.IO) { gateway.startInventory(config) }
    }

    private suspend fun stopInventoryOffMain() {
        withContext(NonCancellable + Dispatchers.IO) { gateway.stopInventory() }
    }

    private fun emitEvent(value: String) { _events.tryEmit(value) }

    override fun onCleared() {
        scanJob?.cancel()
        automaticSetupJob?.cancel()
        voiceJob?.cancel()
        addSuccessJob?.cancel()
        inventoryJob?.cancel()
        searchResumeJob?.cancel()
        voiceEngine.release()
        super.onCleared()
    }

    private companion object {
        const val SEARCH_RESUME_HOLD_MS = 1_000L
        const val TRIGGER_WAKE_DEDUP_MS = 750L
        const val INITIAL_CHECK_POWER_DBM = 5
        const val INITIAL_CHECK_DURATION_MS = 850L
        const val SEARCH_CONTINUOUS_DURATION_MS = 0L
        const val SEARCH_UI_UPDATE_INTERVAL_MS = 50L
        const val ADD_SUCCESS_DURATION_MS = 3_000L
        const val READER_SCAN_TIMEOUT_MS = 5_000L
        const val READER_READY_TIMEOUT_MS = 30_000L
        const val READER_SETUP_RETRY_DELAY_MS = 5_000L
    }
}
