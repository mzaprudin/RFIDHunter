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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
                        is AppState.AddName -> AddNameScreen(current, viewModel, microphonePermission, requestMicrophonePermission)
                        is AppState.Camera -> CameraScreen(viewModel, cameraPermission, requestCameraPermission)
                        is AppState.PhotoReview -> PhotoReviewScreen(current, viewModel::redoPhoto, viewModel::acceptPhoto)
                        is AppState.WaitForNewTag -> WaitForTagScreen(current, viewModel)
                        AppState.AskSearchCommand -> SearchQuestionScreen(viewModel, microphonePermission, requestMicrophonePermission)
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
            Button(onClick = viewModel::simulateTriggerPress, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("ДЕМО: НАЖАТЬ КУРОК", fontSize = 19.sp)
            }
        }
        Button(onClick = viewModel::openCatalog, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text("Открыть каталог (${items.size})", fontSize = 19.sp)
        }
        OutlinedButton(onClick = viewModel::beginAddByVoice, modifier = Modifier.fillMaxWidth().height(58.dp)) {
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
private fun AddNameScreen(
    state: AppState.AddName,
    viewModel: MainViewModel,
    microphonePermission: Boolean,
    requestMicrophonePermission: () -> Unit,
) {
    TextInputScreen(
        title = when {
            state.renameItem != null -> "Как назвать вещь заново?"
            state.pendingEpc == null -> "Как назвать вещь?"
            else -> "Новая метка. Как назвать вещь?"
        },
        primaryLabel = "Принять название",
        onSubmit = viewModel::submitVoice,
        onVoice = { if (microphonePermission) viewModel.listenVoice() else requestMicrophonePermission() },
    )
}

@Composable
private fun SearchQuestionScreen(
    viewModel: MainViewModel,
    microphonePermission: Boolean,
    requestMicrophonePermission: () -> Unit,
) {
    TextInputScreen(
        title = "Что найти?",
        primaryLabel = "Начать поиск",
        onSubmit = viewModel::submitVoice,
        onVoice = { if (microphonePermission) viewModel.listenVoice() else requestMicrophonePermission() },
    )
}

@Composable
private fun TextInputScreen(title: String, primaryLabel: String, onSubmit: (String) -> Unit, onVoice: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.headlineSmall,
            label = { Text("Название") },
            singleLine = true,
        )
        Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Text(primaryLabel, fontSize = 19.sp)
        }
        OutlinedButton(onClick = onVoice, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Text("Слушать офлайн", fontSize = 19.sp)
        }
    }
}

@Composable
private fun CameraScreen(viewModel: MainViewModel, permission: Boolean, requestPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
            val directory = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(directory, "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            controller.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = viewModel.onPhotoCaptured(file.absolutePath)
                override fun onError(exception: ImageCaptureException) = Unit
            })
        }
    }
    if (!permission) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
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
