package ru.rfidhunter.app.ui

import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collect
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.FakeScenario
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.ReaderScanState
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
    requestCameraPermission: () -> Unit,
    requestMicrophonePermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val items by viewModel.items.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val readerScanState by viewModel.readerScanState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val readerDiagnostic by viewModel.readerDiagnostic.collectAsState()
    val readerReady by viewModel.readerReady.collectAsState()
    val recentReads by viewModel.recentReads.collectAsState()
    val voiceBackend by viewModel.voiceBackend.collectAsState()
    var announcement by remember { mutableStateOf("") }
    var announcementKey by remember { mutableStateOf("") }
    val screenKey = feedbackKey(state)
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
    MaterialTheme(typography = roomyTypography) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F7F2)) {
            Column(Modifier.fillMaxSize()) {
                val hideConnectionAnnouncement = state == AppState.Idle && readerReady && connection == ConnectionState.CONNECTED
                if (announcement.isNotBlank() && !hideConnectionAnnouncement) {
                    Text(announcement, modifier = Modifier.fillMaxWidth().background(Color(0xFFE8F5E9)).padding(16.dp), fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
                Box(Modifier.weight(1f)) {
                    when (val current = state) {
                        AppState.Idle -> IdleScreen(viewModel, connection, readerScanState, readerReady, readerDiagnostic, items)
                        AppState.InitialChecking -> StatusScreen("Проверяю метку…")
                        is AppState.AddName -> VoiceListeningScreen(AppState.VoiceListening(VoiceInputMode.ADD, current.pendingEpc, current.renameItem), voiceBackend, viewModel::openCatalog)
                        is AppState.Camera -> CameraScreen(current, viewModel, cameraPermission, requestCameraPermission)
                        is AppState.PhotoReview -> PhotoReviewScreen(current, viewModel::redoPhoto, viewModel::acceptPhoto)
                        is AppState.WaitForNewTag -> WaitForTagScreen(current, viewModel)
                        is AppState.VoiceListening -> VoiceListeningScreen(current, voiceBackend, viewModel::openCatalog)
                        is AppState.VoiceConfirmation -> VoiceConfirmationScreen(current, viewModel)
                        AppState.AskSearchCommand -> VoiceListeningScreen(AppState.VoiceListening(VoiceInputMode.SEARCH), voiceBackend, viewModel::openCatalog)
                        is AppState.SearchTarget -> SearchTargetScreen(current, viewModel)
                        is AppState.RfidSearch -> SearchProgressScreen(current)
                        is AppState.SearchPaused -> SearchPausedScreen(current, viewModel)
                        is AppState.Found -> FoundScreen(current, viewModel)
                        is AppState.ItemCard -> ItemCardScreen(current.item, viewModel)
                        is AppState.Catalog -> CatalogScreen(current, items, viewModel)
                        is AppState.MultipleTags -> MessageScreen(current.message, viewModel::openCatalog)
                        is AppState.Message -> ConfirmScreen(
                            text = current.text,
                            highlightedTitle = current.highlightedTitle,
                            onConfirm = { viewModel.confirmMessage(true) },
                            onCancel = { viewModel.confirmMessage(false) },
                        )
                        AppState.Service -> ServiceScreen(viewModel, connection, readerScanState, discoveredDevices, readerDiagnostic, readerReady, recentReads)
                        is AppState.Error -> MessageScreen(current.text, viewModel::closeService)
                    }
                }
            }
        }
    }
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
    content: @Composable RowScope.() -> Unit,
) {
    MaterialButton(
        onClick = {},
        modifier = modifier.resilientButtonInput(onClick, enabled),
        enabled = enabled,
        colors = colors,
        content = content,
    )
}

@Composable
private fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialOutlinedButton(
        onClick = {},
        modifier = modifier.resilientButtonInput(onClick, enabled),
        enabled = enabled,
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
    MaterialTextButton(
        onClick = {},
        modifier = modifier.resilientButtonInput(onClick, enabled),
        enabled = enabled,
        content = content,
    )
}

private fun Modifier.resilientButtonInput(onClick: () -> Unit, enabled: Boolean): Modifier =
    pointerInput(enabled) {
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
                if (enabled && !multiTouch && now - lastActionAt >= 500L) {
                    lastActionAt = now
                    onClick()
                }
            }
        }
    }.semantics {
        onClick {
            if (!enabled) return@onClick false
            onClick()
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

private fun feedbackKey(state: AppState): String = when (state) {
    AppState.Idle -> "idle"
    AppState.Service -> "service"
    AppState.InitialChecking -> "initial"
    is AppState.Catalog -> "catalog"
    is AppState.ItemCard -> "card-${state.item.id}"
    is AppState.SearchTarget -> "target-${state.item.id}"
    is AppState.RfidSearch -> "search-${state.item.id}"
    is AppState.SearchPaused -> "paused-${state.item.id}"
    is AppState.Found -> "found-${state.item.id}"
    is AppState.VoiceListening -> "voice-${state.mode}-${state.renameItem?.id ?: 0}-${state.attempt}-${state.instruction ?: ""}"
    is AppState.VoiceConfirmation -> "voice-confirm-${state.mode}"
    is AppState.AddName -> "add-name"
    is AppState.Camera -> "camera-${state.context}"
    is AppState.PhotoReview -> "photo-review"
    is AppState.WaitForNewTag -> "wait-tag"
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
) {
    val connectionInProgress = connection == ConnectionState.CONNECTING ||
        scanState == ReaderScanState.SCANNING ||
        (connection == ConnectionState.CONNECTED && !readerReady)
    val showInstruction = !connectionInProgress && (readerReady || viewModel.fakeGateway != null)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text("Искатель вещей", fontSize = 34.sp, fontWeight = FontWeight.Bold)
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
        if (readerStatus.isNotBlank() && !readerReady) {
            Text(readerStatus, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
        if (showInstruction) {
            Text("Нажмите курок чтобы проверить метку или начать поиск", fontSize = 24.sp, textAlign = TextAlign.Center)
        }
        if (viewModel.fakeGateway != null) Button(viewModel::simulateTriggerPress, Modifier.fillMaxWidth().height(96.dp)) { Text("ДЕМО: НАЖАТЬ КУРОК", fontSize = 20.sp) }
        Button(viewModel::openCatalog, Modifier.fillMaxWidth().height(96.dp)) { Text("Открыть каталог (${items.size})", fontSize = 20.sp) }
        OutlinedButton(viewModel::beginAddByVoice, Modifier.fillMaxWidth().height(96.dp)) { Text("Добавить голосом", fontSize = 20.sp) }
        if (viewModel.fakeGateway != null) TextButton(viewModel::openService) { Text("Разработчик: сервис") }
    }
}

@Composable private fun StatusScreen(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }

@Composable
private fun VoiceListeningScreen(state: AppState.VoiceListening, backend: VoiceBackend, onOpenCatalog: () -> Unit) {
    val isNewTag = state.mode == VoiceInputMode.ADD &&
        state.pendingEpc != null &&
        state.renameItem == null
    val prompt = state.instruction
        ?: when {
            isNewTag -> "Метка считана, но её ещё нет в каталоге"
            state.mode == VoiceInputMode.SEARCH -> "Скажите, что найти?"
            else -> "Скажите, как назвать вещь?"
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!state.isProcessing) {
            if (isNewTag) {
                Text("Добавляем новую вещь", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(prompt, fontSize = 28.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("Скажите, как ее назвать?", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
    Column(Modifier.fillMaxSize().resilientReviewInput(viewModel::editVoiceConfirmation, viewModel::acceptVoiceConfirmation)) {
        ReviewZone("ИЗМЕНИТЬ", Color(0xFFFFE0B2), Modifier.weight(.22f))
        Box(Modifier.fillMaxWidth().weight(.56f).background(Color.White), Alignment.Center) {
            if (state.suggestedItem != null) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Я услышал:", fontSize = 25.sp, textAlign = TextAlign.Center)
                    Text("«${state.text}»", fontSize = 30.sp, color = Color(0xFF616161), textAlign = TextAlign.Center)
                    Text("Вероятно, вы имели в виду:", fontSize = 25.sp, textAlign = TextAlign.Center)
                    Text("«${state.suggestedItem.name}»", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), textAlign = TextAlign.Center)
                }
            } else {
                Text(state.text, modifier = Modifier.fillMaxWidth().padding(24.dp), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), textAlign = TextAlign.Center)
            }
        }
        ReviewZone(if (state.suggestedItem != null) "ДА — ИСКАТЬ" else "ГОТОВО!", Color(0xFFC8E6C9), Modifier.weight(.22f))
    }
}

@Composable
private fun CameraScreen(state: AppState.Camera, viewModel: MainViewModel, permission: Boolean, requestPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var processingPhoto by remember { mutableStateOf(false) }
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
            processingPhoto = true
            val directory = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(directory, "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            controller.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { processingPhoto = false; viewModel.onPhotoCaptured(file.absolutePath) }
                override fun onError(exception: ImageCaptureException) { processingPhoto = false; viewModel.onPhotoCaptureFailed() }
            })
        }
    }
    if (!permission) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(state.name ?: state.item?.name ?: "Фотография вещи", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp)); Text("Нужен доступ к камере", fontSize = 28.sp, textAlign = TextAlign.Center)
            Button(requestPermission, Modifier.fillMaxWidth().height(60.dp)) { Text("Разрешить камеру", fontSize = 19.sp) }
        }
    } else Box(Modifier.fillMaxSize()) {
        AndroidView({ PreviewView(it).apply { this.controller = controller } }, Modifier.fillMaxSize())
        Text("Нажмите Volume+ или Volume− для фото", modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(.65f)).padding(18.dp), color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
        Text(state.name ?: state.item?.name ?: "Фотография вещи", modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(.72f)).padding(16.dp), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (processingPhoto) Box(Modifier.fillMaxSize().background(Color.Black.copy(.48f)), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) { CircularProgressIndicator(color = Color.White, strokeWidth = 6.dp, modifier = Modifier.size(64.dp)); Text("Обрабатываю фото…", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
    }
}

@Composable
private fun PhotoReviewScreen(state: AppState.PhotoReview, onRedo: () -> Unit, onDone: () -> Unit) {
    val bitmap = remember(state.path) { BitmapFactory.decodeFile(state.path)?.asImageBitmap() }
    Column(Modifier.fillMaxSize().resilientReviewInput(onRedo, onDone)) {
        ReviewZone("ПЕРЕДЕЛАТЬ", Color(0xFFFFE0B2), Modifier.weight(.22f))
        Box(Modifier.fillMaxWidth().weight(.56f).padding(vertical = 12.dp).background(Color(0xFF202020)), Alignment.Center) { if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else Text("Фото отсутствует", color = Color.White, fontSize = 24.sp) }
        ReviewZone("ГОТОВО!", Color(0xFFC8E6C9), Modifier.weight(.22f))
    }
}

private fun Modifier.resilientReviewInput(onRedo: () -> Unit, onDone: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
            var lastActionAt = 0L
            while (true) {
                val down = awaitFirstDown(false)
                val action = when { down.position.y < size.height * .28f -> onRedo; down.position.y > size.height * .72f -> onDone; else -> null }
                var multiTouch = false
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes
                        .filter { it.id != down.id && it.pressed }
                        .forEach {
                            multiTouch = true
                            it.consume()
                        }
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    pointer?.consume()
                    if (pointer?.pressed == false || pointer == null) break
                }
                val now = System.currentTimeMillis()
                if (action != null && !multiTouch && now - lastActionAt >= 500) { lastActionAt = now; action() }
            }
        }
    }

@Composable private fun ReviewZone(label: String, color: Color, modifier: Modifier) = Box(modifier.fillMaxWidth().background(color), Alignment.Center) { Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold) }

@Composable
private fun WaitForTagScreen(state: AppState.WaitForNewTag, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Поднесите одну новую метку", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Вещь: ${state.name}", fontSize = 24.sp, color = Color(0xFF1565C0))
        if (viewModel.fakeGateway != null) Button(viewModel::simulateTriggerPress, Modifier.fillMaxWidth().height(64.dp)) { Text("ДЕМО: СЧИТАТЬ МЕТКУ", fontSize = 19.sp) }
    }
}

@Composable
private fun SearchTargetScreen(state: AppState.SearchTarget, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ItemDetails(state.item, "Поиск вещи")
        Text("Удерживайте курок и перемещайте считыватель", fontSize = 24.sp, textAlign = TextAlign.Center)
        HoldTriggerButton(viewModel::onTriggerPressed, viewModel::onTriggerReleased)
    }
}

@Composable
private fun HoldTriggerButton(onStart: () -> Unit, onStop: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2E7D32)).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(false); down.consume(); onStart()
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes
                        .filter { it.id != down.id && it.pressed }
                        .forEach { it.consume() }
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    pointer?.consume()
                    if (pointer?.pressed == false || pointer == null) break
                }
                onStop()
            }
        }
    }, Alignment.Center) { Text("УДЕРЖИВАТЬ КУРОК", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SearchProgressScreen(state: AppState.RfidSearch) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ItemDetails(state.item, "Ищем")
        Text("Мощность: ${state.power}", fontSize = 22.sp)
        Text("Отпустите курок, когда увидите вещь", fontSize = 23.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchPausedScreen(state: AppState.SearchPaused, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ItemDetails(state.item, "Поиск приостановлен")
        Text(if (state.lastDetection) "Метка подавала сигнал" else "Сигнал пока не зафиксирован", fontSize = 22.sp, textAlign = TextAlign.Center)
        Text("Коротко нажмите курок для новой проверки. Удерживайте 1 секунду, чтобы продолжить поиск.", fontSize = 22.sp, textAlign = TextAlign.Center)
        Button(viewModel::finishSearch, Modifier.fillMaxWidth().height(74.dp)) { Text("ЗАВЕРШИТЬ ПОИСК", fontSize = 21.sp) }
        HoldTriggerButton(viewModel::onTriggerPressed, viewModel::onTriggerReleased)
    }
}

@Composable
private fun FoundScreen(state: AppState.Found, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ItemDetails(state.item, "ВЕЩЬ НАЙДЕНА")
        Spacer(Modifier.height(16.dp))
        Button(viewModel::closeService, Modifier.fillMaxWidth().height(70.dp)) { Text("ГОТОВО", fontSize = 22.sp) }
    }
}

@Composable
private fun ItemCardScreen(item: Item, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ItemDetails(item, item.name, showItemName = false)
        Text("Нажмите кнопку с нужным действием", fontSize = 23.sp, textAlign = TextAlign.Center)
        Button(
            { viewModel.startSearching(item) },
            Modifier.fillMaxWidth().height(76.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
        ) { Text("ИСКАТЬ ВЕЩЬ", fontSize = 19.sp) }
        OutlinedButton({ viewModel.changeTag(item) }, Modifier.fillMaxWidth().height(76.dp)) { Text("ИЗМЕНИТЬ МЕТКУ", fontSize = 18.sp) }
        OutlinedButton({ viewModel.rename(item) }, Modifier.fillMaxWidth().height(76.dp)) { Text("ИЗМЕНИТЬ НАЗВАНИЕ", fontSize = 18.sp) }
        OutlinedButton({ viewModel.retakePhoto(item) }, Modifier.fillMaxWidth().height(76.dp)) { Text("ПЕРЕДЕЛАТЬ ФОТО", fontSize = 18.sp) }
        OutlinedButton({ viewModel.delete(item) }, Modifier.fillMaxWidth().height(76.dp)) { Text("УДАЛИТЬ", fontSize = 18.sp, color = Color(0xFFB71C1C)) }
    }
}

@Composable
private fun ItemDetails(item: Item, title: String, showItemName: Boolean = true) {
    Text(title, modifier = Modifier.fillMaxWidth(), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    val bitmap = remember(item.photoPath) { item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE0E0E0)), Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else Text("Фото отсутствует", fontSize = 22.sp)
    }
    if (showItemName) Text(item.name, modifier = Modifier.fillMaxWidth(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), textAlign = TextAlign.Center)
}

@Composable
private fun CatalogScreen(state: AppState.Catalog, items: List<Item>, viewModel: MainViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedIndex, items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(state.selectedIndex.coerceIn(0, items.lastIndex)) }
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text("Каталог", modifier = Modifier.fillMaxWidth(), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Volume+ — вверх   Volume− — вниз   Нажмите на вещь", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 16.sp, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        Card(
                            Modifier.fillMaxWidth().height(140.dp).resilientButtonInput({ viewModel.selectCatalogItem(item) }, enabled = true),
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
        Box(Modifier.size(if (large) 180.dp else 96.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE0E0E0)), Alignment.Center) { if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("Фото", fontSize = if (large) 22.sp else 16.sp) }
        Text(item.name, fontSize = if (large) 30.sp else 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
    }
}

@Composable private fun MessageScreen(text: String, onNext: () -> Unit) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(text, fontSize = 28.sp, textAlign = TextAlign.Center); Button(onNext, Modifier.fillMaxWidth().height(60.dp)) { Text("ПОНЯТНО", fontSize = 20.sp) } } }
@Composable
private fun ConfirmScreen(
    text: String,
    highlightedTitle: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (!highlightedTitle.isNullOrBlank()) {
            Spacer(Modifier.height(18.dp))
            Text(
                highlightedTitle,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFC62828),
                textAlign = TextAlign.Center,
            )
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
) {
    val fake = viewModel.fakeGateway
    var voiceText by remember { mutableStateOf("") }
    var readerAddress by remember { mutableStateOf(viewModel.readerAddress()) }
    var diagnosticsEnabled by remember { mutableStateOf(viewModel.diagnosticsEnabled()) }
    var recordings by remember { mutableStateOf(viewModel.diagnosticRecordings()) }
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
