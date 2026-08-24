package ru.rfidhunter.app.ui

import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.BuildConfig
import ru.rfidhunter.app.RfidHunterApplication
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.FakeScenario
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.ReaderScanState
import ru.rfidhunter.app.domain.SearchSignalPolicy
import ru.rfidhunter.app.voice.OfflinePromptPlayer
import ru.rfidhunter.app.voice.VoiceBackend
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RfidHunterApp(
    viewModel: MainViewModel,
    cameraPermission: Boolean,
    microphonePermission: Boolean,
    triggerOverlayAvailable: Boolean,
    requestCameraPermission: () -> Unit,
    requestMicrophonePermission: () -> Unit,
    requestTriggerOverlayPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val items by viewModel.items.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val readerScanState by viewModel.readerScanState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val readerDiagnostic by viewModel.readerDiagnostic.collectAsState()
    val readerReady by viewModel.readerReady.collectAsState()
    val recentReads by viewModel.recentReads.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()
    val syncSettings by viewModel.syncSettings.collectAsState()
    val voiceBackend by viewModel.voiceBackend.collectAsState()
    var announcement by remember { mutableStateOf("") }
    var announcementKey by remember { mutableStateOf("") }
    val screenKey = feedbackKey(state)
    val currentTouchGateKey = touchGateKey(state)
    var screenTouchEnabled by remember(currentTouchGateKey) { mutableStateOf(false) }
    val latestScreenKey by rememberUpdatedState(screenKey)
    val context = LocalContext.current
    val promptPlayer = remember(context) { OfflinePromptPlayer(context) }
    DisposableEffect(promptPlayer) { onDispose { promptPlayer.release() } }
    BackHandler(enabled = state !is AppState.Idle) { viewModel.goBack() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            announcement = it
            announcementKey = latestScreenKey
            promptPlayer.play(it)
        }
    }
    LaunchedEffect(screenKey) {
        if (announcementKey != screenKey) {
            announcement = ""
            announcementKey = screenKey
        }
    }
    LaunchedEffect(currentTouchGateKey) {
        delay(TOUCH_GATE_DELAY_MS)
        screenTouchEnabled = true
    }

    val voiceState = state as? AppState.VoiceListening
    LaunchedEffect(
        voiceState?.sessionId,
        voiceState?.mode,
        voiceState?.pendingEpc,
        voiceState?.renameItem?.id,
        voiceState?.attempt,
        voiceState?.instruction,
        voiceState?.missingSearchQuery,
        microphonePermission,
    ) {
        if (voiceState != null) {
            if (microphonePermission) viewModel.listenVoice() else requestMicrophonePermission()
        }
    }

    val roomyTypography = remember { Typography().withRoomyLineHeight() }
    CompositionLocalProvider(LocalScreenTouchEnabled provides screenTouchEnabled) {
        MaterialTheme(typography = roomyTypography) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F7F2)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Idle has one central status slot of its own. Keeping the
                // event banner there would duplicate connection text and push
                // the trigger hint down as the reader changes state.
                val hideAnnouncement =
                        state == AppState.Idle ||
                        state is AppState.ReaderPreparation ||
                        state is AppState.SearchTarget ||
                        state is AppState.RfidSearch ||
                        state is AppState.SearchPaused ||
                        (state is AppState.VoiceListening && announcement in VOICE_INLINE_STATUSES)
                if (announcement.isNotBlank() && !hideAnnouncement) {
                    Text(announcement, modifier = Modifier.fillMaxWidth().background(Color(0xFFE8F5E9)).padding(16.dp), fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
                Box(Modifier.weight(1f)) {
                    if (state is AppState.Service) {
                        ServiceScreen(viewModel, connection, readerScanState, discoveredDevices, readerDiagnostic, readerReady, recentReads, pendingSyncCount, syncSettings)
                    } else {
                        UserScreen {
                            when (val current = state) {
                                AppState.Idle -> IdleScreen(
                                    viewModel,
                                    connection,
                                    readerScanState,
                                    readerReady,
                                    readerDiagnostic,
                                    items,
                                    triggerOverlayAvailable,
                                    requestTriggerOverlayPermission,
                                )
                                AppState.InitialChecking -> StatusScreen("Проверяю метку…")
                                is AppState.AddName -> VoiceListeningScreen(AppState.VoiceListening(VoiceInputMode.ADD, current.pendingEpc, current.renameItem), voiceBackend, viewModel::openCatalog)
                                is AppState.Camera -> CameraScreen(current, viewModel, cameraPermission, requestCameraPermission)
                                is AppState.PhotoReview -> PhotoReviewScreen(current, viewModel::redoPhoto, viewModel::acceptPhoto)
                                is AppState.WaitForNewTag -> WaitForTagScreen(current, viewModel)
                                is AppState.AddSuccess -> AddSuccessScreen(current)
                                is AppState.VoiceListening -> VoiceListeningScreen(current, voiceBackend, viewModel::openCatalog)
                                is AppState.VoiceConfirmation -> VoiceConfirmationScreen(current, viewModel)
                                AppState.AskSearchCommand -> VoiceListeningScreen(AppState.VoiceListening(VoiceInputMode.SEARCH), voiceBackend, viewModel::openCatalog)
                                is AppState.ReaderPreparation -> ReaderPreparationScreen(current)
                                is AppState.SearchTarget -> SearchTargetScreen(current, readerReady || viewModel.fakeGateway != null)
                                is AppState.RfidSearch -> SearchProgressScreen(current, readerReady || viewModel.fakeGateway != null)
                                is AppState.SearchPaused -> SearchPausedScreen(current, readerReady || viewModel.fakeGateway != null, viewModel)
                                is AppState.ItemCard -> ItemCardScreen(current.item, viewModel)
                                is AppState.Catalog -> CatalogScreen(current, items, viewModel)
                                is AppState.MultipleTags -> MessageScreen(current.message, viewModel::openCatalog)
                                is AppState.Message -> ConfirmScreen(
                                    text = current.text,
                                    highlightedTitle = current.highlightedTitle,
                                    onConfirm = { viewModel.confirmMessage(true) },
                                    onCancel = { viewModel.confirmMessage(false) },
                                )
                                AppState.Service -> Unit
                                is AppState.Error -> MessageScreen(current.text, viewModel::closeService)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private val LocalScreenTouchEnabled = staticCompositionLocalOf { true }

/** Shared safe-area and spacing contract for every ordinary user flow. */
@Composable
private fun UserScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) { content() }
}

/**
 * All ordinary buttons go through the same forgiving touch target: the
 * initial pointer decides the button, small movement does not cancel it, a
 * second finger is ignored, and accidental double activation is debounced.
 */
@Composable
private fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    shape: Shape = RoundedCornerShape(50),
    content: @Composable RowScope.() -> Unit,
) {
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    MaterialButton(
        onClick = {},
        modifier = modifier.resilientButtonInput(onClick, enabled, screenTouchEnabled),
        enabled = enabled,
        colors = colors,
        shape = shape,
        content = content,
    )
}

@Composable
private fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    content: @Composable RowScope.() -> Unit,
) {
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    MaterialOutlinedButton(
        onClick = {},
        modifier = modifier.resilientButtonInput(onClick, enabled, screenTouchEnabled),
        enabled = enabled,
        shape = shape,
        content = content,
    )
}

@Composable
private fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    MaterialTextButton(
        onClick = {},
        modifier = modifier.resilientButtonInput(onClick, enabled, screenTouchEnabled),
        enabled = enabled,
        content = content,
    )
}

private fun Modifier.resilientButtonInput(
    onClick: () -> Unit,
    enabled: Boolean,
    screenTouchEnabled: Boolean,
): Modifier =
    pointerInput(enabled, screenTouchEnabled, onClick) {
        awaitPointerEventScope {
            var lastActionAt = 0L
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                var multiTouch = false
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes
                        .filter { it.id != down.id && it.pressed }
                        .forEach {
                            multiTouch = true
                            it.consume()
                        }
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    primary?.consume()
                    if (primary?.pressed == false || primary == null) break
                }
                val now = System.currentTimeMillis()
                if (enabled && screenTouchEnabled && !multiTouch && now - lastActionAt >= 500L) {
                    lastActionAt = now
                    onClick()
                }
            }
        }
    }.semantics {
        onClick {
            if (!enabled) return@onClick false
            if (screenTouchEnabled) onClick()
            true
        }
    }

/**
 * Lets LazyColumn keep its normal drag gesture. A touch opens a card only if
 * it ends without travelling past the system touch-slop threshold.
 */
private fun Modifier.catalogCardTapInput(
    onClick: () -> Unit,
    screenTouchEnabled: Boolean,
): Modifier = pointerInput(onClick, screenTouchEnabled) {
    awaitPointerEventScope {
        var lastActionAt = 0L
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved = false
            var multiTouch = false
            while (true) {
                val event = awaitPointerEvent()
                event.changes.filter { it.id != down.id && it.pressed }.forEach { multiTouch = true }
                val primary = event.changes.firstOrNull { it.id == down.id }
                if (primary != null && (primary.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (primary?.pressed == false || primary == null) break
            }
            val now = System.currentTimeMillis()
            if (screenTouchEnabled && !moved && !multiTouch && now - lastActionAt >= 500L) {
                lastActionAt = now
                onClick()
            }
        }
    }
}.semantics {
    onClick {
        if (screenTouchEnabled) onClick()
        true
    }
}

private fun Typography.withRoomyLineHeight(): Typography = copy(
    displayLarge = displayLarge.copy(lineHeight = 1.28.em),
    displayMedium = displayMedium.copy(lineHeight = 1.28.em),
    displaySmall = displaySmall.copy(lineHeight = 1.28.em),
    headlineLarge = headlineLarge.copy(lineHeight = 1.28.em),
    headlineMedium = headlineMedium.copy(lineHeight = 1.28.em),
    headlineSmall = headlineSmall.copy(lineHeight = 1.28.em),
    titleLarge = titleLarge.copy(lineHeight = 1.28.em),
    titleMedium = titleMedium.copy(lineHeight = 1.28.em),
    titleSmall = titleSmall.copy(lineHeight = 1.28.em),
    bodyLarge = bodyLarge.copy(lineHeight = 1.28.em),
    bodyMedium = bodyMedium.copy(lineHeight = 1.28.em),
    bodySmall = bodySmall.copy(lineHeight = 1.28.em),
    labelLarge = labelLarge.copy(lineHeight = 1.28.em),
    labelMedium = labelMedium.copy(lineHeight = 1.28.em),
    labelSmall = labelSmall.copy(lineHeight = 1.28.em),
)

/** The one visual treatment for a user-facing item name. */
@Composable
private fun ThingName(
    name: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    fontSize: androidx.compose.ui.unit.TextUnit = 30.sp,
    color: Color = Color(0xFF1565C0),
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        buildAnnotatedString {
            append("Вещь: ")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
            append(name)
            pop()
        },
        modifier = modifier,
        fontSize = fontSize,
        textAlign = textAlign,
    )
}

private fun feedbackKey(state: AppState): String = when (state) {
    AppState.Idle -> "idle"
    AppState.Service -> "service"
    AppState.InitialChecking -> "initial"
    is AppState.Catalog -> "catalog"
    is AppState.ItemCard -> "card-${state.item.id}"
    is AppState.ReaderPreparation -> "reader-preparation-${state.item.id}"
    is AppState.SearchTarget -> "target-${state.item.id}"
    is AppState.RfidSearch -> "search-${state.item.id}"
    is AppState.SearchPaused -> "paused-${state.item.id}"
    is AppState.VoiceListening -> "voice-${state.mode}-${state.renameItem?.id ?: 0}-${state.attempt}-${state.instruction ?: ""}"
    is AppState.VoiceConfirmation -> "voice-confirm-${state.mode}"
    is AppState.AddName -> "add-name"
    is AppState.Camera -> "camera-${state.context}"
    is AppState.PhotoReview -> "photo-review"
    is AppState.WaitForNewTag -> "wait-tag"
    is AppState.AddSuccess -> "add-success-${state.name}"
    is AppState.MultipleTags -> "multiple"
    is AppState.Message -> "message-${state.highlightedTitle ?: state.text}"
    is AppState.Error -> "error"
    AppState.AskSearchCommand -> "ask-search"
}

@Composable
private fun IdleScreen(
    viewModel: MainViewModel,
    connection: ConnectionState,
    scanState: ReaderScanState,
    readerReady: Boolean,
    diagnostic: String?,
    items: List<Item>,
    triggerOverlayAvailable: Boolean,
    requestTriggerOverlayPermission: () -> Unit,
) {
    val connectionInProgress = connection == ConnectionState.CONNECTING ||
        scanState == ReaderScanState.SCANNING ||
        (connection == ConnectionState.CONNECTED && !readerReady)
    val showInstruction = !connectionInProgress && (readerReady || viewModel.fakeGateway != null)
    val scanningInstruction = "Ищу считыватель рядом…\n\n" +
        "Проверьте, мигает ли слева синяя лампочка.\n" +
        "Если не мигает, удерживайте кнопку питания справа на IH25 до звукового сигнала.\n" +
        "Держите считыватель рядом с телефоном."
    val disconnectedInstruction = "Считыватель отключён.\n\n" +
        "Проверьте, мигает ли слева синяя лампочка.\n" +
        "Если не мигает, удерживайте кнопку питания справа на IH25 до звукового сигнала.\n" +
        "Держите считыватель рядом с телефоном."
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val readerStatus = when {
            connection == ConnectionState.CONNECTING -> "Подключаю считыватель…"
            connection == ConnectionState.ERROR -> "Не удалось подключиться"
            scanState == ReaderScanState.SCANNING -> "Ищу считыватель рядом…"
            scanState == ReaderScanState.COMPLETE && diagnostic?.contains("Найдено устройств: 0") == true -> "Считыватель не найден"
            scanState == ReaderScanState.COMPLETE && diagnostic?.startsWith("Найдено устройств:") == true -> "Найдено несколько устройств"
            connection == ConnectionState.CONNECTED -> "Готовлю считыватель…"
            viewModel.fakeGateway != null -> "Демо-режим без считывателя"
            else -> "Считыватель отключён"
        }
        Text(
            "Искатель вещей",
            modifier = Modifier.fillMaxWidth().height(52.dp),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        // Keep one stable central status slot. The old layout rendered a
        // technical status above the instruction, which made the instruction
        // jump vertically as connection state changed.
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (showInstruction) {
                    "Нажмите курок чтобы добавить метку или начать поиск"
                } else if (scanState == ReaderScanState.SCANNING) {
                    scanningInstruction
                } else if (connection == ConnectionState.DISCONNECTED && viewModel.fakeGateway == null) {
                    disconnectedInstruction
                } else {
                    readerStatus
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                fontSize = if (showInstruction) 24.sp else if (scanState == ReaderScanState.SCANNING || connection == ConnectionState.DISCONNECTED) 20.sp else 22.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = if (scanState == ReaderScanState.SCANNING || connection == ConnectionState.DISCONNECTED) 27.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                textAlign = TextAlign.Center,
            )
            // Reserve the same space while connecting so the status does not
            // move when the animation becomes visible after reader setup.
            Box(
                modifier = Modifier.fillMaxWidth().height(184.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showInstruction) {
                    TriggerGif(
                        modifier = Modifier.fillMaxWidth().height(176.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!triggerOverlayAvailable) {
                OutlinedButton(
                    requestTriggerOverlayPermission,
                    Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("РАЗРЕШИТЬ ЗАПУСК ПО КУРКУ", fontSize = 18.sp)
                }
            }
            if (viewModel.fakeGateway != null) Button(viewModel::simulateTriggerPress, Modifier.fillMaxWidth().height(88.dp)) { Text("ДЕМО: НАЖАТЬ КУРОК", fontSize = 20.sp) }
            Button(viewModel::openCatalog, Modifier.fillMaxWidth().height(88.dp)) { Text("Открыть каталог (${items.size})", fontSize = 20.sp) }
            OutlinedButton(viewModel::beginAddByVoice, Modifier.fillMaxWidth().height(88.dp)) { Text("Добавить голосом", fontSize = 20.sp) }
        }
        if (viewModel.fakeGateway != null) TextButton(viewModel::openService) { Text("Разработчик: сервис") }
    }
}

@Composable private fun StatusScreen(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }

@Composable
private fun VoiceListeningScreen(state: AppState.VoiceListening, backend: VoiceBackend, onOpenCatalog: () -> Unit) {
    val isNewItem = state.mode == VoiceInputMode.ADD && state.renameItem == null
    val prompt = state.instruction
        ?: when {
            isNewItem -> "Скажите, как ее назвать?"
            state.renameItem != null -> "Скажите новое название"
            state.mode == VoiceInputMode.SEARCH -> "Скажите, что найти?"
            else -> "Скажите, как ее назвать?"
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!state.isProcessing) {
            if (isNewItem) {
                Text("Добавляем новую вещь", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("Скажите, как ее назвать?", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            } else if (state.renameItem != null) {
                Text("Изменяем название", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                ThingName(state.renameItem.name, fontSize = 30.sp)
                Spacer(Modifier.height(12.dp))
                Text(prompt, fontSize = 28.sp, textAlign = TextAlign.Center)
            } else if (state.missingSearchQuery != null) {
                Text("В каталоге отсутствует", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    "«${state.missingSearchQuery}»",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1565C0),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text("Назовите другую вещь", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            } else {
                Text(prompt, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(28.dp))
        if (state.isProcessing) AnimatedProcessingIcon() else AnimatedMicrophone()
        Spacer(Modifier.height(20.dp))
        val status = when {
            state.isProcessing -> "Распознаю речь…"
            state.isReady -> "Слушаю"
            else -> when (backend) {
                VoiceBackend.WHISPER -> "Подготавливаю Whisper…"
                VoiceBackend.VOSK -> "Подготавливаю Vosk…"
            }
        }
        Text(status, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (state.isReady) Color(0xFF1B5E20) else Color(0xFF5D4037), textAlign = TextAlign.Center)
        if (state.missingSearchQuery != null) {
            Spacer(Modifier.height(24.dp))
            Button(onOpenCatalog, Modifier.fillMaxWidth().height(78.dp)) {
                Text("ОТКРЫТЬ КАТАЛОГ", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AnimatedProcessingIcon() {
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFFE3F2FD)) }
        CircularProgressIndicator(
            modifier = Modifier.size(88.dp),
            color = Color(0xFF1565C0),
            strokeWidth = 10.dp,
        )
    }
}

@Composable
private fun AnimatedMicrophone() {
    val transition = rememberInfiniteTransition(label = "microphone")
    val pulse by transition.animateFloat(.92f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "microphone-pulse")
    Canvas(Modifier.size(132.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }) {
        val center = size.minDimension / 2f
        drawCircle(Color(0xFFE8F5E9), center)
        drawRoundRect(Color(0xFF2E7D32), androidx.compose.ui.geometry.Offset(center - 18f, center - 34f), androidx.compose.ui.geometry.Size(36f, 62f), androidx.compose.ui.geometry.CornerRadius(18f, 18f))
        drawArc(Color(0xFF2E7D32), 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(center - 34f, center - 9f), size = androidx.compose.ui.geometry.Size(68f, 54f), style = Stroke(7f))
        drawLine(Color(0xFF2E7D32), androidx.compose.ui.geometry.Offset(center, center + 45f), androidx.compose.ui.geometry.Offset(center, center + 59f), 7f)
        drawLine(Color(0xFF2E7D32), androidx.compose.ui.geometry.Offset(center - 20f, center + 59f), androidx.compose.ui.geometry.Offset(center + 20f, center + 59f), 7f)
    }
}

@Composable
private fun VoiceConfirmationScreen(state: AppState.VoiceConfirmation, viewModel: MainViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
            if (state.suggestedItem != null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Я услышал:", fontSize = 25.sp, textAlign = TextAlign.Center)
                    Text("«${state.text}»", fontSize = 30.sp, color = Color(0xFF616161), textAlign = TextAlign.Center)
                    Text("Вероятно, вы имели в виду:", fontSize = 25.sp, textAlign = TextAlign.Center)
                    ThingName(state.suggestedItem.name, fontSize = 36.sp)
                }
            } else {
                ThingName(state.text, fontSize = 40.sp)
            }
        }
        Button(
            viewModel::editVoiceConfirmation,
            Modifier.fillMaxWidth().height(82.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D), contentColor = Color(0xFF3E2723)),
        ) { Text("ИЗМЕНИТЬ", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        Button(
            viewModel::acceptVoiceConfirmation,
            Modifier.fillMaxWidth().height(82.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A), contentColor = Color.White),
        ) { Text(if (state.suggestedItem != null) "ДА — ИСКАТЬ" else "ГОТОВО", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun CameraScreen(state: AppState.Camera, viewModel: MainViewModel, permission: Boolean, requestPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var processingPhoto by remember { mutableStateOf(false) }
    val photoScope = rememberCoroutineScope()
    val shutterSound = remember(context) { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val vibrator = remember(context) { context.getSystemService(Vibrator::class.java) }
    DisposableEffect(shutterSound) { onDispose { shutterSound.release() } }
    LaunchedEffect(viewModel, shutterSound, vibrator) {
        viewModel.photoFeedback.collect {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    val controller = remember(context) { LifecycleCameraController(context).apply { cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA; setEnabledUseCases(CameraController.IMAGE_CAPTURE) } }
    LaunchedEffect(lifecycleOwner, controller) { controller.bindToLifecycle(lifecycleOwner) }
    LaunchedEffect(viewModel, controller) {
        viewModel.photoCapture.collect {
            if (!permission) return@collect
            if (processingPhoto) return@collect
            processingPhoto = true
            val directory = File(context.filesDir, "photos").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val source = File(directory, "capture_$timestamp.jpg")
            val file = File(directory, "photo_$timestamp.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(source).build()
            controller.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    photoScope.launch {
                        val normalized = withContext(Dispatchers.Default) { PhotoProcessor.normalize(source, file) }
                        processingPhoto = false
                        if (normalized) {
                            viewModel.onPhotoCaptured(file.absolutePath)
                        } else {
                            source.delete()
                            viewModel.onPhotoCaptureFailed()
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) { processingPhoto = false; source.delete(); viewModel.onPhotoCaptureFailed() }
            })
        }
    }
    if (!permission) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            state.name?.let { ThingName(it, fontSize = 30.sp) }
                ?: state.item?.let { ThingName(it.name, fontSize = 30.sp) }
                ?: Text("Фотография вещи", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp)); Text("Нужен доступ к камере", fontSize = 28.sp, textAlign = TextAlign.Center)
            Button(requestPermission, Modifier.fillMaxWidth().height(60.dp)) { Text("Разрешить камеру", fontSize = 19.sp) }
        }
    } else Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().background(Color.White.copy(.92f)).padding(12.dp), Alignment.Center) {
                state.name?.let { ThingName(it, fontSize = 28.sp) }
                    ?: state.item?.let { ThingName(it.name, fontSize = 28.sp) }
                    ?: Text("Фотография вещи", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            // Nothing is drawn over this slot: every visible part of the 3:4
            // viewfinder is retained by the centre-crop shown in review.
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black),
            ) {
                AndroidView(
                    { PreviewView(it).apply {
                        this.controller = controller
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    } },
                    Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                buildAnnotatedString {
                    append("Нажмите ")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF1565C0)))
                    append("Громкость+")
                    pop()
                    append(" или ")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF1565C0)))
                    append("Громкость−")
                    pop()
                    append(" для фото")
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (processingPhoto) Box(Modifier.fillMaxSize().background(Color.Black.copy(.48f)), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) { CircularProgressIndicator(color = Color.White, strokeWidth = 6.dp, modifier = Modifier.size(64.dp)); Text("Обрабатываю фото…", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
    }
}

@Composable
private fun PhotoReviewScreen(state: AppState.PhotoReview, onRedo: () -> Unit, onDone: () -> Unit) {
    val bitmap = remember(state.path) { BitmapFactory.decodeFile(state.path)?.asImageBitmap() }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val thingName = state.name ?: state.item?.name
        if (thingName != null) ThingName(thingName, fontSize = 26.sp)
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color(0xFF202020)),
            Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Color.Black),
                Alignment.Center,
            ) {
                if (bitmap != null) {
                    // The saved file is center-cropped to portrait 3:4; keeping
                    // the review in the same slot prevents unseen edges from
                    // appearing after the camera frame.
                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("Фото отсутствует", color = Color.White, fontSize = 24.sp)
                }
            }
        }
        Button(onRedo, Modifier.fillMaxWidth().height(82.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D), contentColor = Color(0xFF3E2723))) { Text("ПЕРЕДЕЛАТЬ", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        Button(onDone, Modifier.fillMaxWidth().height(82.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A), contentColor = Color.White)) { Text("ГОТОВО", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun WaitForTagScreen(state: AppState.WaitForNewTag, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when (val reason = state.reason) {
            NewTagWaitReason.Initial -> Text("Поднесите одну новую метку", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            NewTagWaitReason.NoTag -> Text("Новая метка не считалась. Поднесите одну метку и нажмите курок", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            NewTagWaitReason.MultipleTags -> Text("Считалось несколько меток. Попробуйте отойти вместе с вещью в сторону", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            is NewTagWaitReason.AlreadyAssigned -> {
                Text("Эта метка уже привязана к другой вещи. Считайте другую метку", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                ThingName(reason.item.name, fontSize = 30.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        ThingName(state.name, fontSize = 24.sp)
        if (viewModel.fakeGateway != null) Button(viewModel::simulateTriggerPress, Modifier.fillMaxWidth().height(64.dp)) { Text("ДЕМО: СЧИТАТЬ МЕТКУ", fontSize = 19.sp) }
    }
}

@Composable
private fun AddSuccessScreen(state: AppState.AddSuccess) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Вещь добавлена", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        ThingName(state.name, fontSize = 32.sp)
    }
}

@Composable
private fun ReaderPreparationScreen(state: AppState.ReaderPreparation) {
    val stage = when (state.stage) {
        ReaderPreparationStage.SEARCHING -> "Ищу считыватель рядом…"
        ReaderPreparationStage.CONNECTING -> "Подключаюсь к IH25…"
        ReaderPreparationStage.PREPARING -> "Готовлю считыватель…"
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Подготавливаю считыватель", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        ThingName(state.item.name, fontSize = 30.sp)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(modifier = Modifier.size(52.dp), strokeWidth = 5.dp)
        Spacer(Modifier.height(20.dp))
        Text(stage, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Text(
            "Проверьте, мигает ли слева синяя лампочка.\n" +
                "Если не мигает, удерживайте кнопку питания справа на IH25 до звукового сигнала.\n" +
                "Держите считыватель рядом с телефоном.",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 21.sp,
            lineHeight = 28.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchTargetScreen(state: AppState.SearchTarget, readerReady: Boolean) {
    SearchScreen(
        item = state.item,
        rssi = null,
        instruction = if (readerReady) "Удерживайте курок, чтобы начать поиск" else "Считыватель отключён",
    )
}

@Composable
private fun SearchProgressScreen(state: AppState.RfidSearch, readerReady: Boolean) {
    if (readerReady) GeigerClickFeedback(state.signalStrength)
    SearchScreen(
        item = state.item,
        rssi = state.rssi,
        instruction = if (readerReady) {
            "Перемещайте считыватель. Щелчки участятся рядом с вещью"
        } else {
            "Считыватель отключён"
        },
    )
}

@Composable
private fun SearchPausedScreen(state: AppState.SearchPaused, readerReady: Boolean, viewModel: MainViewModel) {
    SearchScreen(
        item = state.item,
        rssi = state.lastRssi,
        instruction = if (readerReady) {
            "Нажмите и продолжайте держать курок. Через секунду поиск возобновится"
        } else {
            "Считыватель отключён"
        },
        onFinish = viewModel::finishSearch,
    )
}

@Composable
private fun SearchScreen(
    item: Item,
    rssi: Int?,
    instruction: String,
    onFinish: (() -> Unit)? = null,
) {
    val signalPolicy = remember { SearchSignalPolicy() }
    val targetVisualStrength = signalPolicy.normalizedStrength(rssi)
    val animatedVisualStrength by animateFloatAsState(
        targetValue = targetVisualStrength,
        animationSpec = tween(durationMillis = 340),
        label = "RFID signal display",
    )
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Text("Ищем", fontSize = 29.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Box(Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.Center) {
            // Match the item-card title treatment and size so the selected
            // thing remains equally legible while searching.
            ThingName(item.name, fontSize = 30.sp)
        }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The search photo is intentionally a little larger than the
                // card preview. The vertical meter uses the otherwise-unused
                // space beside it instead of shrinking the photo into a wide
                // horizontal slot.
                SearchItemPhoto(item, width = 200.dp)
                Spacer(Modifier.width(12.dp))
                LinearSignalMeter(
                    animatedVisualStrength,
                    Modifier.width(112.dp).height(267.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SignalReadout(animatedVisualStrength, rssi)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
            Text(
                instruction,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 21.sp,
                lineHeight = 27.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(74.dp), contentAlignment = Alignment.Center) {
            if (onFinish != null) {
                Button(onFinish, Modifier.fillMaxSize()) { Text("ЗАВЕРШИТЬ ПОИСК", fontSize = 21.sp) }
            }
        }
    }
}

@Composable
private fun SignalReadout(animatedStrength: Float, rssi: Int?) {
    val percent = normalizedSignalStrengthPercent(animatedStrength)
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Сила сигнала",
            modifier = Modifier.weight(1f),
            fontSize = 21.sp,
            fontWeight = FontWeight.Medium,
        )
        Column(
            modifier = Modifier.width(128.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "$percent%",
                modifier = Modifier.fillMaxWidth(),
                color = signalColor(animatedStrength),
                fontSize = 31.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
                textAlign = TextAlign.End,
            )
            Text(
                formatSearchRssi(rssi),
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF546E7A),
                fontSize = 15.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun SearchItemPhoto(item: Item, width: androidx.compose.ui.unit.Dp = 160.dp) {
    val bitmap = remember(item.photoPath) {
        item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    Box(
        Modifier
            .width(width)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFE0E0E0)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("Фото отсутствует", fontSize = 20.sp)
        }
    }
}

@Composable
private fun LinearSignalMeter(signalStrength: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val barWidth = size.width * 0.34f
        val barLeft = size.width * 0.58f - barWidth / 2f
        val barTop = size.height * 0.08f
        val barBottom = size.height * 0.82f
        val barHeight = barBottom - barTop
        val cornerRadius = barWidth / 2f
        val fillHeight = barHeight * signalStrength.coerceIn(0f, 1f)

        drawRoundRect(
            color = Color(0xFFCFD8DC),
            topLeft = androidx.compose.ui.geometry.Offset(barLeft, barTop),
            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
        )
        if (fillHeight > 0f) {
            drawRoundRect(
                color = signalColor(signalStrength),
                topLeft = androidx.compose.ui.geometry.Offset(barLeft, barBottom - fillHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, fillHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            )
        }
        repeat(5) { index ->
            val y = barBottom - barHeight * index / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = androidx.compose.ui.geometry.Offset(barLeft, y),
                end = androidx.compose.ui.geometry.Offset(barLeft + barWidth, y),
                strokeWidth = 4f,
            )
        }

        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(38, 50, 56)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = (size.width * 0.20f).coerceIn(30f, 52f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        listOf(
            barBottom to "0",
            (barTop + barBottom) / 2f to "50",
            barTop to "100",
        ).forEach { (y, label) ->
            drawContext.canvas.nativeCanvas.drawText(label, size.width * 0.16f, y + labelPaint.textSize * 0.35f, labelPaint)
        }
    }
}

@Composable
private fun GeigerClickFeedback(signalStrength: Float) {
    val context = LocalContext.current
    val player = remember(context) { GeigerClickPlayer(context.applicationContext) }
    val policy = remember { SearchSignalPolicy() }
    val latestStrength by rememberUpdatedState(signalStrength)
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        var cadenceStrength = latestStrength.coerceIn(0f, 1f)
        var lastClickAt = SystemClock.elapsedRealtime() - policy.clickIntervalMs(cadenceStrength)
        while (isActive) {
            val target = latestStrength.coerceIn(0f, 1f)
            val smoothing = if (target >= cadenceStrength) 0.18f else 0.06f
            cadenceStrength += (target - cadenceStrength) * smoothing
            if (kotlin.math.abs(target - cadenceStrength) < 0.002f) cadenceStrength = target
            val interval = policy.clickIntervalMs(cadenceStrength)
            val now = SystemClock.elapsedRealtime()
            val dueAt = lastClickAt + interval
            if (now >= dueAt) {
                if (player.play()) {
                    lastClickAt = now
                    if (BuildConfig.DEBUG) {
                        Log.d(GEIGER_LOG_TAG, "click strength=$cadenceStrength intervalMs=$interval")
                    }
                }
                delay(15L)
            } else {
                delay((dueAt - now).coerceIn(5L, 20L))
            }
        }
    }
}

private const val GEIGER_LOG_TAG = "GeigerClick"
private val VOICE_INLINE_STATUSES = setOf("Слушаю", "Распознаю речь")

private fun signalColor(strength: Float): Color = when {
    strength <= 0f -> Color(0xFF546E7A)
    strength < 0.35f -> Color(0xFF546E7A)
    strength < 0.70f -> Color(0xFFE65100)
    else -> Color(0xFF2E7D32)
}

@Composable
private fun ItemCardScreen(item: Item, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemDetails(item)
        Text("Нажмите кнопку с нужным действием", fontSize = 23.sp, textAlign = TextAlign.Center)
        Button(
            { viewModel.startSearching(item) },
            Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
        ) { Text("ИСКАТЬ ВЕЩЬ", fontSize = 19.sp) }
        OutlinedButton({ viewModel.changeTag(item) }, Modifier.fillMaxWidth().height(60.dp)) { Text("ИЗМЕНИТЬ МЕТКУ", fontSize = 18.sp) }
        OutlinedButton({ viewModel.rename(item) }, Modifier.fillMaxWidth().height(60.dp)) { Text("ИЗМЕНИТЬ НАЗВАНИЕ", fontSize = 18.sp) }
        OutlinedButton({ viewModel.retakePhoto(item) }, Modifier.fillMaxWidth().height(60.dp)) { Text("ПЕРЕДЕЛАТЬ ФОТО", fontSize = 18.sp) }
        OutlinedButton({ viewModel.delete(item) }, Modifier.fillMaxWidth().height(60.dp)) { Text("УДАЛИТЬ", fontSize = 18.sp, color = Color(0xFFB71C1C)) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ItemDetails(item: Item) {
    ThingName(item.name, fontSize = 30.sp)
    val bitmap = remember(item.photoPath) { item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Box(
        Modifier.width(180.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE0E0E0)),
        Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("Фото отсутствует", fontSize = 22.sp)
    }
}

@Composable
private fun CatalogScreen(state: AppState.Catalog, items: List<Item>, viewModel: MainViewModel) {
    val listState = rememberLazyListState()
    val screenTouchEnabled = LocalScreenTouchEnabled.current
    LaunchedEffect(state.selectedIndex, items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(state.selectedIndex.coerceIn(0, items.lastIndex)) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text("Каталог", modifier = Modifier.fillMaxWidth(), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                buildAnnotatedString {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF1565C0)))
                    append("Громкость+")
                    pop()
                    append(" — вверх    ")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF1565C0)))
                    append("Громкость−")
                    pop()
                    append(" — вниз")
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Text("Нажмите на вещь", modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), fontSize = 16.sp, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        Card(
                            Modifier.fillMaxWidth().height(150.dp).catalogCardTapInput(
                                onClick = { viewModel.selectCatalogItem(item) },
                                screenTouchEnabled = screenTouchEnabled,
                            ),
                            colors = CardDefaults.cardColors(containerColor = if (index == state.selectedIndex) Color(0xFFE3F2FD) else Color.White),
                        ) {
                            ItemPreview(item, false, Modifier.padding(10.dp))
                        }
                    }
                }
                if (items.isEmpty()) Text("Каталог пуст", modifier = Modifier.align(Alignment.Center), fontSize = 24.sp)
                if (items.size > 1) Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp).padding(vertical = 6.dp)) {
                    val track = size.height
                    val thumb = (track / items.size).coerceAtLeast(28f)
                    val offset = if (items.size <= 1) 0f else (track - thumb) * state.selectedIndex / (items.size - 1).toFloat()
                    drawRoundRect(Color(0xFF78909C), androidx.compose.ui.geometry.Offset(0f, offset), androidx.compose.ui.geometry.Size(size.width, thumb), androidx.compose.ui.geometry.CornerRadius(size.width, size.width))
                }
            }
            Text("${if (items.isEmpty()) 0 else state.selectedIndex + 1} из ${items.size}", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 19.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ItemPreview(item: Item, large: Boolean, modifier: Modifier = Modifier) {
    val bitmap = remember(item.photoPath) { item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .width(if (large) 180.dp else 102.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE0E0E0)),
            Alignment.Center,
        ) { if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("Фото", fontSize = if (large) 22.sp else 16.sp) }
        ThingName(item.name, modifier = Modifier.weight(1f), fontSize = if (large) 30.sp else 24.sp, textAlign = TextAlign.Start)
    }
}

@Composable private fun MessageScreen(text: String, onNext: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(text, fontSize = 28.sp, textAlign = TextAlign.Center); Spacer(Modifier.height(20.dp)); Button(onNext, Modifier.fillMaxWidth().height(60.dp)) { Text("ПОНЯТНО", fontSize = 20.sp) } } }
@Composable
private fun ConfirmScreen(
    text: String,
    highlightedTitle: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (!highlightedTitle.isNullOrBlank()) {
            Spacer(Modifier.height(18.dp))
            ThingName(highlightedTitle, fontSize = 38.sp)
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onConfirm,
            Modifier.fillMaxWidth().height(96.dp),
        ) { Text("ПОДТВЕРДИТЬ", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.weight(1f))
        TextButton(
            onCancel,
            Modifier.fillMaxWidth().height(96.dp),
        ) { Text("ОТМЕНА", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ServiceScreen(
    viewModel: MainViewModel,
    connection: ConnectionState,
    scanState: ReaderScanState,
    devices: List<ru.rfidhunter.app.domain.RfidDevice>,
    diagnostic: String?,
    readerReady: Boolean,
    recentReads: List<ru.rfidhunter.app.domain.TagRead>,
    pendingSyncCount: Int,
    syncSettings: ru.rfidhunter.app.data.SyncSettingsState,
) {
    val fake = viewModel.fakeGateway
    var voiceText by remember { mutableStateOf("") }
    var readerAddress by remember { mutableStateOf(viewModel.readerAddress()) }
    var diagnosticsEnabled by remember { mutableStateOf(viewModel.diagnosticsEnabled()) }
    var recordings by remember { mutableStateOf(viewModel.diagnosticRecordings()) }
    var backupEndpoint by remember(syncSettings.endpoint) { mutableStateOf(syncSettings.endpoint) }
    var accountPassword by remember { mutableStateOf("") }
    var accountPasswordRepeat by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Samsung RFIDHunter") }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("SERVICE / DEBUG", fontSize = 20.sp, fontWeight = FontWeight.Bold); TextButton(viewModel::closeService) { Text("Закрыть") } }
        Text("Backend: ${if (fake != null) "FAKE" else "HONEYWELL IH25"}")
        Text("Состояние: ${when {
            scanState == ReaderScanState.SCANNING -> "сканирую"
            connection == ConnectionState.CONNECTING -> "подключаюсь"
            connection == ConnectionState.ERROR -> "ошибка"
            readerReady -> "готов"
            connection == ConnectionState.CONNECTED -> "создаю reader"
            else -> "отключён"
        }}")
        diagnostic?.takeIf { it.isNotBlank() }?.let { Text("Диагностика: $it") }
        Text("Резервное копирование каталога", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("В очереди на отправку: $pendingSyncCount")
        syncSettings.lastSuccessAt?.let { timestamp ->
            Text("Последняя успешная отправка: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))}")
        }
        syncSettings.lastError?.let { Text("Ошибка синхронизации: $it", color = Color(0xFFB00020)) }
        OutlinedTextField(
            value = backupEndpoint,
            onValueChange = { backupEndpoint = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Адрес сервера (например, https://server)") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaterialOutlinedButton({ viewModel.setBackupEndpoint(backupEndpoint) }, modifier = Modifier.weight(1f)) { Text("СОХРАНИТЬ") }
            MaterialOutlinedButton({ viewModel.syncNow() }, modifier = Modifier.weight(1f)) { Text("СИНХРОНИЗИРОВАТЬ") }
        }
        Text("Отправляются только новые и изменённые записи; фото передаются отдельно по SHA-256.", fontSize = 14.sp)
        Text("Device ID: ${viewModel.getApplication<RfidHunterApplication>().syncSettings.deviceId}", fontSize = 13.sp)
        syncSettings.accountId?.let { Text("Каталог: $it", fontSize = 13.sp) }
        Text(
            if (syncSettings.registered) "Устройство зарегистрировано (${syncSettings.scopes.sorted().joinToString(", ")})"
            else "Устройство не зарегистрировано",
            color = if (syncSettings.registered) Color(0xFF2E7D32) else Color(0xFFB00020),
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Резервирование необязательно: без учетной записи каталог хранится только на телефоне. Для нового телефона используйте тот же пароль.",
            fontSize = 14.sp,
        )
        Text("Пароль не отправляется в резервной копии и не восстанавливается сервером — сохраните его отдельно.", fontSize = 14.sp, color = Color(0xFF8A1C1C))
        OutlinedTextField(
            value = accountPassword,
            onValueChange = { accountPassword = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Пароль резервной учетной записи") },
        )
        OutlinedTextField(
            value = accountPasswordRepeat,
            onValueChange = { accountPasswordRepeat = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Повторите пароль для создания") },
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Название устройства") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaterialOutlinedButton(
                { viewModel.createBackupAccount(accountPassword, deviceName) },
                modifier = Modifier.weight(1f),
                enabled = accountPassword.length >= 8 && accountPassword == accountPasswordRepeat && !syncSettings.registered,
            ) { Text("СОЗДАТЬ") }
            MaterialOutlinedButton(
                { viewModel.recoverBackupAccount(accountPassword, deviceName) },
                modifier = Modifier.weight(1f),
                enabled = accountPassword.length >= 8,
            ) { Text("ВОССТАНОВИТЬ") }
        }
        /*
         * The password flow above is the only account setup path; no pairing
         * code or administrator action is required.
         */
        /*
        MaterialOutlinedButton(
            { viewModel.enrollBackupDevice(pairingCode, deviceName) },
            modifier = Modifier.fillMaxWidth(),
            enabled = pairingCode.isNotBlank() && !syncSettings.registered,
        ) { Text("ЗАРЕГИСТРИРОВАТЬ УСТРОЙСТВО") }
        */
        MaterialOutlinedButton(
            viewModel::restoreBackup,
            modifier = Modifier.fillMaxWidth(),
            enabled = syncSettings.registered && syncSettings.scopes.contains("RESTORE"),
        ) { Text("ВОССТАНОВИТЬ МОЙ КАТАЛОГ") }
        Button(viewModel::scanReaders, Modifier.fillMaxWidth(), enabled = scanState != ReaderScanState.SCANNING) {
            if (scanState == ReaderScanState.SCANNING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Сканирую IH25…")
            } else Text("Сканировать IH25")
        }
        if (devices.isNotEmpty()) {
            Text("Найдено устройств: ${devices.size}", fontWeight = FontWeight.Bold)
            devices.forEach { device ->
                val selected = viewModel.readerAddress().equals(device.macAddress, true)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFE8F5E9) else Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.name + if (device.likelyHoneywell) " (IH25?)" else "", fontWeight = FontWeight.Bold)
                        Text("${device.macAddress}   RSSI ${device.rssi} dBm")
                        Button({ viewModel.selectReader(device); readerAddress = device.macAddress }, enabled = !selected) { Text(if (selected) "Выбрано" else "Выбрать") }
                    }
                }
            }
        }
        Text("Движок: ${viewModel.currentVoiceBackend()}")
        Text("Whisper model: ${viewModel.currentWhisperVariant()}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Сохранять записи для диагностики")
            androidx.compose.material3.Switch(
                checked = diagnosticsEnabled,
                onCheckedChange = { diagnosticsEnabled = it; viewModel.setDiagnosticsEnabled(it) },
            )
        }
        Button({ recordings = viewModel.diagnosticRecordings() }, Modifier.fillMaxWidth()) { Text("ОБНОВИТЬ СПИСОК ЗАПИСЕЙ") }
        recordings.take(8).forEach { file ->
            key(file.absolutePath) {
                OutlinedButton({ viewModel.transcribeDiagnostic(file) }, Modifier.fillMaxWidth()) {
                    Text("ПОВТОРИТЬ: ${file.name}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ viewModel.setVoiceBackend(VoiceBackend.WHISPER) }) { Text("Whisper q5_1") }
            OutlinedButton({ viewModel.setVoiceBackend(VoiceBackend.VOSK) }) { Text("Vosk") }
        }
        OutlinedTextField(readerAddress, { readerAddress = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Bluetooth MAC считывателя") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ viewModel.setReaderAddress(readerAddress); viewModel.simulateConnect() }) { Text("Подключить") }
            OutlinedButton(viewModel::simulateDisconnect) { Text("Отключить") }
            if (fake != null) OutlinedButton(viewModel::simulateTriggerPress) { Text("trigger") }
        }
        Button(
            onClick = { viewModel.startDiagnosticInventory() },
            enabled = fake != null || readerReady,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Проверить метки (5 секунд)") }
        if (recentReads.isNotEmpty()) {
            Text("Последние EPC: ${recentReads.size}", fontWeight = FontWeight.Bold)
            recentReads.takeLast(8).forEach { read -> Text("${read.epc}   RSSI ${read.rssi}") }
        }
        Text("Сценарий первичного чтения:")
        FakeScenario.values().forEach { scenario -> OutlinedButton({ viewModel.setFakeScenario(scenario) }, Modifier.fillMaxWidth()) { Text(scenario.name) } }
        Text("Тест голосовой команды", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(voiceText, { voiceText = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Например: найди очки") })
        Button({ viewModel.submitServiceVoice(voiceText); voiceText = "" }, enabled = voiceText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("ОТПРАВИТЬ КОМАНДУ") }
    }
}
