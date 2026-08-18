package ru.rfidhunter.app.ui

import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collect
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.FakeScenario
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.voice.OfflinePromptPlayer
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
    var announcement by remember { mutableStateOf("") }
    val context = LocalContext.current
    val promptPlayer = remember(context) { OfflinePromptPlayer(context) }
    DisposableEffect(promptPlayer) {
        onDispose { promptPlayer.release() }
    }
    BackHandler(enabled = state !is AppState.Idle) { viewModel.goBack() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            announcement = it
            promptPlayer.play(it)
        }
    }

    LaunchedEffect(state, microphonePermission) {
        if (state is AppState.VoiceListening) {
            if (microphonePermission) viewModel.listenVoice()
            else requestMicrophonePermission()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F2)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (announcement.isNotBlank()) {
                    Text(
                        text = announcement,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE8F5E9))
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    when (val current = state) {
                        AppState.Idle -> IdleScreen(viewModel, connection, items)
                        AppState.InitialChecking -> StatusScreen("Проверяю метку…")
                        is AppState.AddName -> VoiceListeningScreen(
                            AppState.VoiceListening(VoiceInputMode.ADD, current.pendingEpc, current.renameItem),
                        )
                        is AppState.Camera -> CameraScreen(current, viewModel, cameraPermission, requestCameraPermission)
                        is AppState.PhotoReview -> PhotoReviewScreen(current, viewModel::redoPhoto, viewModel::acceptPhoto)
                        is AppState.WaitForNewTag -> WaitForTagScreen(current, viewModel)
                        is AppState.VoiceListening -> VoiceListeningScreen(current)
                        is AppState.VoiceConfirmation -> VoiceConfirmationScreen(current, viewModel)
                        AppState.AskSearchCommand -> VoiceListeningScreen(AppState.VoiceListening(VoiceInputMode.SEARCH))
                        is AppState.SearchTarget -> SearchTargetScreen(current, viewModel)
                        is AppState.RfidSearch -> SearchProgressScreen(current)
                        is AppState.Found -> FoundScreen(current, viewModel)
                        is AppState.ItemCard -> ItemCardScreen(current.item, viewModel)
                        is AppState.Catalog -> CatalogScreen(current, items, viewModel)
                        is AppState.MultipleTags -> MessageScreen(current.message, viewModel::openCatalog)
                        is AppState.Message -> ConfirmScreen(current.text, { viewModel.confirmMessage(true) }, { viewModel.confirmMessage(false) })
                        AppState.Service -> ServiceScreen(viewModel)
                        is AppState.Error -> MessageScreen(current.text, viewModel::closeService)
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleScreen(viewModel: MainViewModel, connection: ConnectionState, items: List<Item>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Text("Искатель вещей", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            text = when (connection) {
                ConnectionState.CONNECTED -> "Считыватель подключён"
                ConnectionState.CONNECTING -> "Подключаю считыватель…"
                ConnectionState.ERROR -> "Ошибка считывателя"
                ConnectionState.DISCONNECTED -> "Демо-режим без считывателя"
            },
            fontSize = 18.sp,
        )
        Text("Нажмите курок, чтобы проверить метку", fontSize = 24.sp, textAlign = TextAlign.Center)
        if (viewModel.fakeGateway != null) {
            Button(onClick = viewModel::simulateTriggerPress, modifier = Modifier.fillMaxWidth().height(88.dp)) {
                Text("ДЕМО: НАЖАТЬ КУРОК", fontSize = 19.sp)
            }
        }
        Button(onClick = viewModel::openCatalog, modifier = Modifier.fillMaxWidth().height(88.dp)) {
            Text("Открыть каталог (${items.size})", fontSize = 19.sp)
        }
        OutlinedButton(onClick = viewModel::beginAddByVoice, modifier = Modifier.fillMaxWidth().height(88.dp)) {
            Text("Добавить голосом", fontSize = 19.sp)
        }
        if (viewModel.fakeGateway != null) {
            TextButton(onClick = viewModel::openService) { Text("Разработчик: сервис") }
        }
    }
}

@Composable
private fun StatusScreen(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VoiceListeningScreen(state: AppState.VoiceListening) {
    val prompt = if (state.mode == VoiceInputMode.SEARCH) "Скажите, что найти?" else "Скажите, как назвать вещь?"
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(prompt, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        AnimatedMicrophone()
        Spacer(Modifier.height(24.dp))
        Text("Слушаю", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
    }
}

@Composable
private fun AnimatedMicrophone() {
    val transition = rememberInfiniteTransition(label = "microphone")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "microphone-pulse",
    )
    Canvas(
        Modifier
            .size(132.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse },
    ) {
        val center = size.minDimension / 2f
        drawCircle(Color(0xFFE8F5E9), radius = center)
        drawRoundRect(
            color = Color(0xFF2E7D32),
            topLeft = androidx.compose.ui.geometry.Offset(center - 18f, center - 34f),
            size = androidx.compose.ui.geometry.Size(36f, 62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
        )
        drawArc(
            color = Color(0xFF2E7D32),
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(center - 34f, center - 9f),
            size = androidx.compose.ui.geometry.Size(68f, 54f),
            style = Stroke(width = 7f),
        )
        drawLine(Color(0xFF2E7D32), androidx.compose.ui.geometry.Offset(center, center + 45f), androidx.compose.ui.geometry.Offset(center, center + 59f), strokeWidth = 7f)
        drawLine(Color(0xFF2E7D32), androidx.compose.ui.geometry.Offset(center - 20f, center + 59f), androidx.compose.ui.geometry.Offset(center + 20f, center + 59f), strokeWidth = 7f)
    }
}

@Composable
private fun VoiceConfirmationScreen(state: AppState.VoiceConfirmation, viewModel: MainViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .resilientReviewInput(viewModel::editVoiceConfirmation, viewModel::acceptVoiceConfirmation),
    ) {
        Column(Modifier.fillMaxSize()) {
            ReviewZone("ИЗМЕНИТЬ", Color(0xFFFFE0B2), Modifier.weight(0.22f))
            Box(
                Modifier.fillMaxWidth().weight(0.56f).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.text,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            ReviewZone("ГОТОВО!", Color(0xFFC8E6C9), Modifier.weight(0.22f))
        }
    }
}

@Composable
private fun CameraScreen(
    state: AppState.Camera,
    viewModel: MainViewModel,
    permission: Boolean,
    requestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var processingPhoto by remember { mutableStateOf(false) }
    val shutterSound = remember(context) { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val vibrator = remember(context) { context.getSystemService(Vibrator::class.java) }
    DisposableEffect(shutterSound) {
        onDispose { shutterSound.release() }
    }
    LaunchedEffect(viewModel, shutterSound, vibrator) {
        viewModel.photoFeedback.collect {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            vibrator?.let { device ->
                device.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    LaunchedEffect(lifecycleOwner, controller) { controller.bindToLifecycle(lifecycleOwner) }
    LaunchedEffect(viewModel, controller) {
        viewModel.photoCapture.collect {
            if (!permission) return@collect
            processingPhoto = true
            val directory = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(directory, "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            controller.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processingPhoto = false
                    viewModel.onPhotoCaptured(file.absolutePath)
                }
                override fun onError(exception: ImageCaptureException) {
                    processingPhoto = false
                    viewModel.onPhotoCaptureFailed()
                }
            })
        }
    }
    if (!permission) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                state.name ?: state.item?.name ?: "Фотография вещи",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text("Нужен доступ к камере", fontSize = 28.sp, textAlign = TextAlign.Center)
            Button(onClick = requestPermission, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Разрешить камеру", fontSize = 19.sp) }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { PreviewView(it).apply { this.controller = controller } },
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "Нажмите Volume+ или Volume− для фото",
                modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .65f)).padding(18.dp),
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = state.name ?: state.item?.name ?: "Фотография вещи",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .72f))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (processingPhoto) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 6.dp, modifier = Modifier.size(64.dp))
                        Text("Обрабатываю фото…", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoReviewScreen(state: AppState.PhotoReview, onRedo: () -> Unit, onDone: () -> Unit) {
    val bitmap = remember(state.path) { state.path.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .resilientReviewInput(onRedo, onDone),
    ) {
        Column(Modifier.fillMaxSize()) {
            ReviewZone("ПЕРЕДЕЛАТЬ", Color(0xFFFFE0B2), Modifier.weight(0.22f))
            Box(Modifier.fillMaxWidth().weight(0.56f).background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text("Фото отсутствует", color = Color.White, fontSize = 24.sp)
            }
            ReviewZone("ГОТОВО!", Color(0xFFC8E6C9), Modifier.weight(0.22f))
        }
    }
}

private fun Modifier.resilientReviewInput(onRedo: () -> Unit, onDone: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var lastActionAt = 0L
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val action = when {
                down.position.y < size.height * .28f -> onRedo
                down.position.y > size.height * .72f -> onDone
                else -> null
            }
            var released = false
            while (!released) {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id }
                pointer?.consume()
                released = pointer == null || !pointer.pressed
            }
            val now = System.currentTimeMillis()
            if (action != null && now - lastActionAt >= 500) {
                lastActionAt = now
                action()
            }
        }
    }
}

@Composable
private fun ReviewZone(label: String, color: Color, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(color), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WaitForTagScreen(state: AppState.WaitForNewTag, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Поднесите одну новую метку", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Вещь: ${state.name}", fontSize = 22.sp)
        if (viewModel.fakeGateway != null) Button(onClick = viewModel::simulateTriggerPress, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("ДЕМО: СЧИТАТЬ МЕТКУ", fontSize = 19.sp) }
    }
}

@Composable
private fun SearchTargetScreen(state: AppState.SearchTarget, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ItemPreview(state.item, large = true)
        Text("Удерживайте курок и перемещайте считыватель", fontSize = 24.sp, textAlign = TextAlign.Center)
        HoldTriggerButton(onStart = viewModel::onTriggerPressed, onStop = viewModel::onTriggerReleased)
    }
}

@Composable
private fun HoldTriggerButton(onStart: () -> Unit, onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2E7D32))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(false)
                        down.consume()
                        onStart()
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            pointer?.consume()
                            if (pointer?.pressed == false) break
                        }
                        onStop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text("УДЕРЖИВАТЬ КУРOК", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SearchProgressScreen(state: AppState.RfidSearch) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ItemPreview(state.item, large = true)
        Text("Ищу…", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Текущая мощность: ${state.power}", fontSize = 22.sp)
    }
}

@Composable
private fun FoundScreen(state: AppState.Found, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("ВЕЩЬ НАЙДЕНА", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
        ItemPreview(state.item, large = true)
        Button(onClick = viewModel::closeService, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Готово", fontSize = 20.sp) }
    }
}

@Composable
private fun ItemCardScreen(item: Item, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ItemPreview(item, large = true)
        Text("Действия с вещью", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Button(onClick = { viewModel.changeTag(item) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Изменить метку", fontSize = 18.sp) }
        Button(onClick = { viewModel.rename(item) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Изменить название", fontSize = 18.sp) }
        Button(onClick = { viewModel.retakePhoto(item) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Переделать фото", fontSize = 18.sp) }
        OutlinedButton(onClick = { viewModel.delete(item) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Удалить", fontSize = 18.sp, color = Color(0xFFB71C1C)) }
    }
}

@Composable
private fun CatalogScreen(state: AppState.Catalog, items: List<Item>, viewModel: MainViewModel) {
    val pages = items.chunked(3)
    val page = pages.getOrNull(state.page).orEmpty()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Каталог", modifier = Modifier.fillMaxWidth(), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        page.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f).clickable { viewModel.selectCatalogItem(item) },
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) { ItemPreview(item, large = false, modifier = Modifier.padding(10.dp)) }
        }
        if (page.isEmpty()) Text("Каталог пуст", fontSize = 24.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("Страница ${if (pages.isEmpty()) 0 else state.page + 1} из ${pages.size}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 18.sp)
    }
}

@Composable
private fun ItemPreview(item: Item, large: Boolean, modifier: Modifier = Modifier) {
    val bitmap = remember(item.photoPath) { item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(if (large) 180.dp else 96.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("Фото", fontSize = if (large) 22.sp else 16.sp)
        }
        Text(item.name, fontSize = if (large) 30.sp else 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageScreen(text: String, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, fontSize = 28.sp, textAlign = TextAlign.Center)
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Понятно", fontSize = 20.sp) }
    }
}

@Composable
private fun ConfirmScreen(text: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, fontSize = 28.sp, textAlign = TextAlign.Center)
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Подтвердить", fontSize = 20.sp) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Отмена", fontSize = 20.sp) }
    }
}

@Composable
private fun ServiceScreen(viewModel: MainViewModel) {
    val fake = viewModel.fakeGateway
    var voiceText by remember { mutableStateOf("") }
    var readerAddress by remember { mutableStateOf(viewModel.readerAddress()) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SERVICE / DEBUG", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = viewModel::closeService) { Text("Закрыть") }
        }
        Text("Backend: ${if (fake != null) "FAKE" else "HONEYWELL"}")
        OutlinedTextField(
            value = readerAddress,
            onValueChange = { readerAddress = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Bluetooth MAC считывателя") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { viewModel.setReaderAddress(readerAddress); viewModel.simulateConnect() }) { Text("connect") }
            OutlinedButton(onClick = viewModel::simulateDisconnect) { Text("disconnect") }
            OutlinedButton(onClick = viewModel::simulateTriggerPress) { Text("trigger") }
        }
        Text("Сценарий первичного чтения:")
        FakeScenario.values().forEach { scenario ->
            OutlinedButton(onClick = { viewModel.setFakeScenario(scenario) }, modifier = Modifier.fillMaxWidth()) { Text(scenario.name) }
        }
        Text("Тест голосовой команды", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = voiceText,
            onValueChange = { voiceText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Например: найди очки") },
        )
        Button(
            onClick = {
                viewModel.submitServiceVoice(voiceText)
                voiceText = ""
            },
            enabled = voiceText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Отправить команду") }
        Text("После выбора сценария нажмите trigger. Сервисный экран предназначен только для разработчика.", fontSize = 14.sp)
    }
}
