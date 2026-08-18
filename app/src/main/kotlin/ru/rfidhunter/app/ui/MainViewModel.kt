package ru.rfidhunter.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import ru.rfidhunter.app.RfidHunterApplication
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.FakeScenario
import ru.rfidhunter.app.domain.InventoryConfig
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.TagRead
import ru.rfidhunter.app.domain.TriggerEvent
import ru.rfidhunter.app.domain.SearchPowerPolicy
import ru.rfidhunter.app.domain.VoiceCommand
import ru.rfidhunter.app.domain.findBestItem
import ru.rfidhunter.app.domain.normalizeText
import ru.rfidhunter.app.domain.parseVoiceCommand
import ru.rfidhunter.app.hardware.FakeRfidGateway
import ru.rfidhunter.app.hardware.RfidGateway
import ru.rfidhunter.app.voice.FakeVoiceRecognizer
import ru.rfidhunter.app.voice.VoskVoiceRecognizer
import ru.rfidhunter.app.voice.VoiceModelInstaller

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RfidHunterApplication
    private val repository = app.itemRepository
    val items = repository.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val gateway: RfidGateway = app.rfidGateway
    val fakeGateway: FakeRfidGateway? = gateway as? FakeRfidGateway
    private val voice = VoskVoiceRecognizer(application)
    val fakeVoice = FakeVoiceRecognizer()

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
    private var captureReads = false
    private val readBuffer = mutableListOf<TagRead>()
    private val searchPowerPolicy = SearchPowerPolicy()

    val connectionState: StateFlow<ConnectionState> = gateway.connectionState

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
        // If a Russian Vosk model was bundled into assets, copy it once to the
        // app-private directory. With no model present the mock/service text
        // path remains available and no network access is attempted.
        viewModelScope.launch {
            // Copying and loading the model while the app is idle removes the
            // cold-start gap from the first spoken phrase.
            val installed = VoiceModelInstaller.installFromAssets(application)
            if (installed) voice.prepare()
        }
        viewModelScope.launch {
            gateway.tagReads.collectLatest { if (captureReads) synchronized(readBuffer) { readBuffer += it } }
        }
        viewModelScope.launch {
            gateway.triggerEvents.collectLatest { event ->
                when (event) {
                    TriggerEvent.PRESSED -> onTriggerPressed()
                    TriggerEvent.RELEASED -> onTriggerReleased()
                }
            }
        }
    }

    fun connectReader() = viewModelScope.launch {
        gateway.connect()
        emitEvent("Попытка подключения считывателя")
    }

    fun disconnectReader() = viewModelScope.launch { gateway.disconnect() }

    fun onTriggerPressed() {
        when (val current = _state.value) {
            AppState.Idle, is AppState.Catalog -> startInitialCheck()
            is AppState.SearchTarget -> startSearch(current.item)
            is AppState.RfidSearch -> Unit
            is AppState.WaitForNewTag -> startNewTagCheck(current.name, current.photoPath, current.replacingItem)
            is AppState.ItemCard -> emitEvent("Выберите действие на карточке")
            is AppState.VoiceListening -> listenVoice()
            AppState.Service -> startInitialCheck()
            else -> emitEvent("Сейчас курок не используется")
        }
    }

    fun onTriggerReleased() {
        if (_state.value is AppState.RfidSearch) {
            inventoryJob?.cancel()
            _state.value = AppState.SearchTarget((_state.value as AppState.RfidSearch).item)
            emitEvent("Поиск остановлен")
        }
    }

    private fun startInitialCheck() {
        if (inventoryJob?.isActive == true) return
        inventoryJob = viewModelScope.launch {
            _state.value = AppState.InitialChecking
            collectInventory(power = 5, duration = 850)
            val unique = synchronized(readBuffer) { readBuffer.distinctBy { it.epc.uppercase() } }
            when {
                unique.isEmpty() -> {
                    startVoiceInput(VoiceInputMode.SEARCH)
                }
                unique.size > 1 -> {
                    _state.value = AppState.MultipleTags()
                    emitEvent("Найдено несколько меток. Оставьте одну")
                }
                else -> {
                    val item = repository.getByEpc(unique.first().epc)
                    if (item == null) {
                        startVoiceInput(VoiceInputMode.ADD, pendingEpc = unique.first().epc)
                    } else {
                        _state.value = AppState.ItemCard(item)
                        emitEvent("Это ${item.name}. Открыта карточка вещи")
                    }
                }
            }
        }
    }

    private fun startNewTagCheck(name: String, photoPath: String?, replacingItem: Item? = null) {
        if (inventoryJob?.isActive == true) return
        inventoryJob = viewModelScope.launch {
            _state.value = AppState.InitialChecking
            collectInventory(power = 5, duration = 850)
            val unique = synchronized(readBuffer) { readBuffer.distinctBy { it.epc.uppercase() } }
            when {
                unique.size == 1 && repository.getByEpc(unique.first().epc) == null -> {
                    val newEpc = unique.first().epc
                    if (replacingItem == null) {
                        repository.insert(name, newEpc, photoPath)
                        _state.value = AppState.Idle
                        emitEvent("Вещь добавлена")
                    } else {
                        val updated = replacingItem.copy(epc = newEpc, name = name, photoPath = photoPath)
                        repository.update(updated)
                        _state.value = AppState.ItemCard(updated)
                        emitEvent("Метка изменена")
                    }
                }
                unique.size > 1 -> {
                    _state.value = AppState.WaitForNewTag(name, photoPath, replacingItem)
                    emitEvent("Рядом несколько меток. Оставьте одну")
                }
                else -> {
                    _state.value = AppState.WaitForNewTag(name, photoPath, replacingItem)
                    emitEvent("Нужна одна новая метка")
                }
            }
        }
    }

    private suspend fun collectInventory(power: Int, duration: Long) {
        synchronized(readBuffer) { readBuffer.clear() }
        captureReads = true
        gateway.setReadPower(power)
        gateway.startInventory(InventoryConfig(power, duration))
        delay(duration)
        gateway.stopInventory()
        captureReads = false
    }

    private fun startSearch(item: Item) {
        if (inventoryJob?.isActive == true) return
        inventoryJob = viewModelScope.launch {
            _state.value = AppState.RfidSearch(item, 30)
            var power = searchPowerPolicy.maxPower
            var found = false
            repeat(5) {
                if (!isActive) return@repeat
                synchronized(readBuffer) { readBuffer.clear() }
                captureReads = true
                gateway.setReadPower(power)
                gateway.startInventory(InventoryConfig(power, 500))
                delay(500)
                gateway.stopInventory()
                captureReads = false
                val reads = synchronized(readBuffer) { readBuffer.filter { it.epc.equals(item.epc, true) } }
                val strong = reads.map { it.rssi }.average().takeIf { !it.isNaN() }?.let { it > -55 } == true
                if (reads.isNotEmpty()) {
                    found = true
                    emitEvent(if (strong) "Метка в зоне. Уточняем расстояние" else "Метка найдена")
                    if (strong && power > searchPowerPolicy.minPower) {
                        power = searchPowerPolicy.nextPower(power, detected = true, strong = true)
                        _state.value = AppState.RfidSearch(item, power)
                    } else return@repeat
                } else if (power < searchPowerPolicy.maxPower) {
                    power = searchPowerPolicy.nextPower(power, detected = false, strong = false)
                }
            }
            _state.value = if (found) AppState.Found(item) else AppState.SearchTarget(item)
            if (found) emitEvent("Вещь найдена: ${item.name}") else emitEvent("Метка пока не найдена")
        }
    }

    fun submitVoice(text: String) {
        val value = normalizeText(text)
        when (val current = _state.value) {
            is AppState.VoiceListening -> handleRecognizedVoice(current, text)
            is AppState.VoiceConfirmation -> Unit
            is AppState.AddName -> if (value.isNotBlank()) {
                _state.value = AppState.VoiceConfirmation(
                    mode = VoiceInputMode.ADD,
                    text = text.trim(),
                    pendingEpc = current.pendingEpc,
                    renameItem = current.renameItem,
                )
            }
            AppState.AskSearchCommand -> handleSearchCommand(value)
            else -> emitEvent("Голосовая команда сейчас не ожидается")
        }
    }

    private fun handleSearchCommand(value: String) {
        handleRecognizedVoice(AppState.VoiceListening(VoiceInputMode.SEARCH), value)
    }

    fun beginAddByVoice() {
        startVoiceInput(VoiceInputMode.ADD)
    }

    fun openCatalog() { _state.value = AppState.Catalog(0) }

    fun catalogPage(delta: Int) {
        val current = _state.value as? AppState.Catalog ?: return
        val pageCount = (items.value.size + 2) / 3
        val page = (current.page + delta).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        _state.value = current.copy(page = page)
    }

    fun selectCatalogItem(item: Item) {
        _state.value = AppState.SearchTarget(item)
        emitEvent("Ищем ${item.name}. Удерживайте курок")
    }

    fun changeTag(item: Item) {
        _state.value = AppState.WaitForNewTag(item.name, item.photoPath, replacingItem = item)
        emitEvent("Поднесите одну новую метку и нажмите курок")
    }

    fun rename(item: Item) {
        startVoiceInput(VoiceInputMode.ADD, renameItem = item)
    }

    fun retakePhoto(item: Item) {
        _state.value = AppState.Camera(PhotoContext.EDIT_EXISTING, item = item)
        emitEvent("Сделайте новую фотографию кнопкой громкости")
    }

    fun delete(item: Item) {
        _state.value = AppState.Message("Удалить ${item.name}? Нажмите подтвердить", AppState.ItemCard(item))
        emitEvent("Удаление требует подтверждения")
    }

    fun confirmMessage(confirmed: Boolean) {
        val current = _state.value as? AppState.Message ?: return
        if (!confirmed) {
            _state.value = current.returnTo
            return
        }
        val item = (current.returnTo as? AppState.ItemCard)?.item
        if (item != null) viewModelScope.launch {
            repository.delete(item)
            _state.value = AppState.Idle
            emitEvent("Вещь удалена")
        }
    }

    fun onPhotoCaptured(path: String) {
        val current = _state.value as? AppState.Camera ?: return
        _state.value = AppState.PhotoReview(path, current.context, current.item, current.name, current.epc)
        emitEvent("Проверьте фотографию")
    }

    fun onPhotoCaptureFailed() {
        emitEvent("Не удалось сохранить фотографию. Повторите")
    }

    fun requestPhotoCapture() { _photoCapture.tryEmit(Unit) }

    fun requestPhotoCaptureWithFeedback() {
        _photoFeedback.tryEmit(Unit)
        _photoCapture.tryEmit(Unit)
    }

    fun redoPhoto() {
        val current = _state.value as? AppState.PhotoReview ?: return
        _state.value = AppState.Camera(current.context, current.item, current.name, current.epc)
    }

    fun acceptPhoto() {
        val current = _state.value as? AppState.PhotoReview ?: return
        when (current.context) {
            PhotoContext.NEW_WITH_TAG -> viewModelScope.launch {
                repository.insert(current.name.orEmpty(), current.epc.orEmpty(), current.path)
                _state.value = AppState.Idle
                emitEvent("Вещь добавлена")
            }
            PhotoContext.MANUAL_NEW -> _state.value = AppState.WaitForNewTag(current.name.orEmpty(), current.path)
            PhotoContext.EDIT_EXISTING -> viewModelScope.launch {
                current.item?.let { repository.update(it.copy(photoPath = current.path)) }
                _state.value = current.item?.let { AppState.ItemCard(it.copy(photoPath = current.path)) } ?: AppState.Idle
                emitEvent("Фотография обновлена")
            }
        }
    }

    fun onVolumeKey(delta: Int = 0) {
        when (_state.value) {
            is AppState.Camera -> requestPhotoCaptureWithFeedback()
            is AppState.Catalog -> catalogPage(delta)
            else -> Unit
        }
    }

    fun goBack() {
        when (val current = _state.value) {
            AppState.Idle -> Unit
            AppState.InitialChecking -> {
                inventoryJob?.cancel()
                captureReads = false
                viewModelScope.launch { gateway.stopInventory() }
                _state.value = AppState.Idle
            }
            is AppState.AddName -> _state.value = current.renameItem?.let { AppState.ItemCard(it) } ?: AppState.Idle
            is AppState.Camera -> _state.value = if (current.context == PhotoContext.EDIT_EXISTING) {
                current.item?.let { AppState.ItemCard(it) } ?: AppState.Idle
            } else AppState.Idle
            is AppState.PhotoReview -> _state.value = AppState.Camera(current.context, current.item, current.name, current.epc)
            is AppState.WaitForNewTag -> _state.value = current.replacingItem?.let { AppState.ItemCard(it) } ?: AppState.Idle
            is AppState.VoiceListening -> {
                voiceJob?.cancel()
                _state.value = current.renameItem?.let { AppState.ItemCard(it) } ?: AppState.Idle
            }
            is AppState.VoiceConfirmation -> {
                _state.value = current.renameItem?.let { AppState.ItemCard(it) } ?: AppState.Idle
            }
            AppState.AskSearchCommand -> _state.value = AppState.Idle
            is AppState.SearchTarget -> _state.value = AppState.Idle
            is AppState.RfidSearch -> {
                inventoryJob?.cancel()
                _state.value = AppState.SearchTarget(current.item)
            }
            is AppState.Found -> _state.value = AppState.Idle
            is AppState.ItemCard -> _state.value = AppState.Idle
            is AppState.Catalog -> _state.value = AppState.Idle
            is AppState.MultipleTags -> _state.value = AppState.Idle
            is AppState.Message -> _state.value = current.returnTo
            AppState.Service -> _state.value = AppState.Idle
            is AppState.Error -> _state.value = AppState.Idle
        }
    }

    fun openService() { _state.value = AppState.Service }
    fun closeService() { _state.value = AppState.Idle }

    fun setFakeScenario(scenario: FakeScenario) { fakeGateway?.scenario = scenario }
    fun setFakeCustomTag(epc: String, rssi: Int) { fakeGateway?.customTags = listOf(TagRead(epc, rssi)) }
    fun simulateTriggerPress() { onTriggerPressed() }
    fun simulateTriggerRelease() { onTriggerReleased() }
    fun simulateConnect() = connectReader()
    fun simulateDisconnect() = disconnectReader()
    fun readerAddress(): String = gateway.readerAddress
    fun setReaderAddress(address: String) { gateway.readerAddress = address.trim() }

    fun listenVoice(): Job {
        voiceJob?.cancel()
        val listening = _state.value as? AppState.VoiceListening
        if (listening == null) return viewModelScope.launch { }
        if (listening.isReady) _state.value = listening.copy(isReady = false)
        val job = viewModelScope.launch {
            val startedFor = _state.value as? AppState.VoiceListening ?: return@launch
            val installed = VoiceModelInstaller.installFromAssets(getApplication())
            if (!installed || !voice.isAvailable) {
                emitEvent("Русская модель Vosk не установлена")
                return@launch
            }
            val result = voice.listen {
                val latest = _state.value as? AppState.VoiceListening
                if (latest != null && latest.mode == startedFor.mode && latest.pendingEpc == startedFor.pendingEpc && latest.renameItem?.id == startedFor.renameItem?.id && latest.attempt == startedFor.attempt) {
                    _state.value = latest.copy(isReady = true)
                }
                emitEvent("Слушаю")
            }
            if (result != null) {
                if (_state.value is AppState.VoiceListening) submitVoice(result.text)
            } else {
                emitEvent("Не удалось распознать. Скажите ещё раз")
                delay(700)
                val latest = _state.value as? AppState.VoiceListening
                if (latest != null && latest.attempt < 2) {
                    _state.value = latest.copy(attempt = latest.attempt + 1, isReady = false)
                }
            }
        }
        voiceJob = job
        job.invokeOnCompletion { if (voiceJob === job) voiceJob = null }
        return job
    }

    fun submitServiceVoice(text: String) {
        fakeVoice.nextText = text
        if (_state.value is AppState.Service) {
            handleRecognizedVoice(AppState.VoiceListening(VoiceInputMode.SEARCH), text)
        } else {
            submitVoice(text)
        }
    }

    fun editVoiceConfirmation() {
        val current = _state.value as? AppState.VoiceConfirmation ?: return
        startVoiceInput(current.mode, current.pendingEpc, current.renameItem)
    }

    fun acceptVoiceConfirmation() {
        val current = _state.value as? AppState.VoiceConfirmation ?: return
        when (current.mode) {
            VoiceInputMode.SEARCH -> {
                val item = findBestItem(current.text, items.value)
                if (item == null) {
                    emitEvent("Не нашёл такую вещь. Скажите ещё раз")
                    startVoiceInput(VoiceInputMode.SEARCH)
                } else {
                    _state.value = AppState.SearchTarget(item)
                    emitEvent("Ищем ${item.name}. Удерживайте курок")
                }
            }
            VoiceInputMode.ADD -> {
                val name = current.text.trim()
                if (name.isBlank()) {
                    startVoiceInput(VoiceInputMode.ADD, current.pendingEpc, current.renameItem)
                } else if (current.renameItem != null) {
                    val item = current.renameItem
                    viewModelScope.launch {
                        val updated = item.copy(name = name)
                        repository.update(updated)
                        _state.value = AppState.ItemCard(updated)
                        emitEvent("Название изменено")
                    }
                } else {
                    val photoContext = if (current.pendingEpc == null) PhotoContext.MANUAL_NEW else PhotoContext.NEW_WITH_TAG
                    _state.value = AppState.Camera(photoContext, name = name, epc = current.pendingEpc)
                    emitEvent("Сделайте фотографию кнопкой громкости")
                }
            }
        }
    }

    private fun startVoiceInput(
        mode: VoiceInputMode,
        pendingEpc: String? = null,
        renameItem: Item? = null,
    ) {
        voiceJob?.cancel()
        _state.value = AppState.VoiceListening(mode, pendingEpc, renameItem)
        emitEvent(if (mode == VoiceInputMode.SEARCH) "Скажите, что найти?" else "Скажите, как назвать вещь?")
    }

    private fun handleRecognizedVoice(input: AppState.VoiceListening, text: String) {
        val raw = text.trim()
        val value = normalizeText(raw)
        if (value.isBlank()) {
            startVoiceInput(input.mode, input.pendingEpc, input.renameItem)
            return
        }
        when (input.mode) {
            VoiceInputMode.SEARCH -> when (val command = parseVoiceCommand(raw)) {
                is VoiceCommand.Search -> _state.value = AppState.VoiceConfirmation(VoiceInputMode.SEARCH, command.query)
                is VoiceCommand.Add -> {
                    if (command.name.isNullOrBlank()) startVoiceInput(VoiceInputMode.ADD)
                    else _state.value = AppState.VoiceConfirmation(VoiceInputMode.ADD, command.name)
                }
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

    private fun emitEvent(value: String) { _events.tryEmit(value) }

    override fun onCleared() {
        voiceJob?.cancel()
        voice.release()
        super.onCleared()
    }
}
