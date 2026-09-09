package ru.rfidhunter.app.ui.screens

import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.rfidhunter.app.R
import ru.rfidhunter.app.ui.AppState
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.ui.MainViewModel.PhotoCaptureRequest
import ru.rfidhunter.app.ui.PhotoProcessor
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.PillRole
import ru.rfidhunter.app.ui.components.ThingName
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.Dimens
import ru.rfidhunter.app.ui.theme.ReviewBackdrop
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
internal fun CameraScreen(state: AppState.Camera, viewModel: MainViewModel, permission: Boolean, requestPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var processingRequest by remember { mutableStateOf<PhotoCaptureRequest?>(null) }
    val processingPhoto = processingRequest != null
    // Equal Camera data can still belong to a different navigation session.
    val latestCamera = remember { mutableStateOf(state, referentialEqualityPolicy()) }.apply { value = state }
    val appState by viewModel.state.collectAsState()
    val currentSession = appState === state
    val shutterSound = remember(context) { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val vibrator = remember(context) { context.getSystemService(Vibrator::class.java) }
    DisposableEffect(shutterSound) { onDispose { shutterSound.release() } }
    val controller = remember(context) { LifecycleCameraController(context).apply { cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA; setEnabledUseCases(CameraController.IMAGE_CAPTURE) } }
    DisposableEffect(lifecycleOwner, controller, permission, currentSession) {
        if (permission && currentSession) {
            try {
                controller.bindToLifecycle(lifecycleOwner)
            } catch (error: Exception) {
                controller.unbind()
                Log.w("RfidCamera", "not-ready: bind failed", error)
            }
        }
        onDispose {
            controller.unbind()
            Log.d("RfidCamera", "disposed binding permission=$permission current=$currentSession")
        }
    }
    LaunchedEffect(viewModel, controller, lifecycleOwner, permission, currentSession) {
        if (!permission || !currentSession) return@LaunchedEffect
        // Navigation cancels capture even while AnimatedContent retains the outgoing view.
        viewModel.state.collectLatest { camera ->
            if (camera !is AppState.Camera) return@collectLatest
            val sessionId = System.identityHashCode(camera)
            coroutineScope {
                var activeRequest: PhotoCaptureRequest? = null
                try {
                    viewModel.photoCapture.collect { request ->
                        val reason = when {
                            request.camera !== camera || camera !== latestCamera.value || viewModel.state.value !== camera -> "session"
                            processingRequest != null -> "busy"
                            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> "lifecycle"
                            controller.cameraInfo?.cameraState?.value?.type != CameraState.Type.OPEN -> "camera not open"
                            else -> null
                        }
                        if (reason != null || !viewModel.tryStartPhotoCapture(request)) {
                            Log.d("RfidCamera", "not-ready: ${reason ?: "busy or stale session"} session=$sessionId")
                            return@collect
                        }
                        activeRequest = request
                        processingRequest = request
                        // Keep collecting (and discarding) busy keys rather than queuing a second shot.
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                val directory = File(context.filesDir, "photos")
                                check(directory.isDirectory || directory.mkdirs()) { "Cannot create photo directory" }
                                val source = File.createTempFile("capture_", ".jpg", directory)
                                val file = File(directory, source.name.replaceFirst("capture_", "photo_"))
                                var delivered = false
                                try {
                                    suspendCancellableCoroutine<Unit> { continuation ->
                                        val options = ImageCapture.OutputFileOptions.Builder(source).build()
                                        controller.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                if (viewModel.state.value !== request.camera) continuation.cancel()
                                                if (continuation.isActive) continuation.resume(Unit)
                                                else source.delete()
                                            }

                                            override fun onError(exception: ImageCaptureException) {
                                                source.delete()
                                                if (continuation.isActive) continuation.resumeWithException(exception)
                                                else Log.d("RfidCamera", "capture error after cancellation session=$sessionId", exception)
                                            }
                                        })
                                        Log.i("RfidCamera", "accepted session=$sessionId")
                                        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
                                            .onFailure { Log.w("RfidCamera", "shutter sound failed", it) }
                                        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)) }
                                            .onFailure { Log.w("RfidCamera", "shutter vibration failed", it) }
                                    }
                                    check(withContext(Dispatchers.Default) { PhotoProcessor.normalize(source, file) }) {
                                        "Photo normalization failed"
                                    }
                                    delivered = viewModel.onPhotoCaptured(request, file.absolutePath)
                                    if (delivered) Log.i("RfidCamera", "success session=$sessionId")
                                } finally {
                                    if (!delivered) {
                                        source.delete()
                                        file.delete()
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w("RfidCamera", "capture error session=$sessionId", error)
                                viewModel.onPhotoCaptureFailed(request)
                            } finally {
                                if (processingRequest === request) processingRequest = null
                                viewModel.cancelPhotoCapture(request)
                            }
                        }
                    }
                } finally {
                    activeRequest?.let { request ->
                        if (processingRequest === request) processingRequest = null
                        viewModel.cancelPhotoCapture(request)
                    }
                    Log.d("RfidCamera", "disposed capture session=$sessionId")
                }
            }
        }
    }
    if (!permission) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            state.name?.let { ThingName(it, fontSize = 30.sp) }
                ?: state.item?.let { ThingName(it.name, fontSize = 30.sp) }
                ?: Text(stringResource(R.string.camera_photo_fallback_title), style = AppStyles.Headline, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp)); Text(stringResource(R.string.camera_permission_needed_title), fontSize = 28.sp, textAlign = TextAlign.Center)
            Button(requestPermission, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight)) { Text(stringResource(R.string.camera_permission_grant_action), fontSize = 19.sp) }
        }
    } else Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().background(Color.White.copy(.92f)).padding(12.dp), Alignment.Center) {
                state.name?.let { ThingName(it, fontSize = 28.sp) }
                    ?: state.item?.let { ThingName(it.name, fontSize = 28.sp) }
                    ?: Text(stringResource(R.string.camera_photo_fallback_title), style = AppStyles.Subtitle, textAlign = TextAlign.Center)
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
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                    append("Громкость+")
                    pop()
                    append(" или ")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                    append("Громкость−")
                    pop()
                    append(" для фото")
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (processingPhoto) Box(Modifier.fillMaxSize().background(Color.Black.copy(.48f)), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) { CircularProgressIndicator(color = Color.White, strokeWidth = 6.dp, modifier = Modifier.size(64.dp)); Text(stringResource(R.string.camera_processing_photo), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
    }
}

@Composable
internal fun PhotoReviewScreen(state: AppState.PhotoReview, onRedo: () -> Unit, onDone: () -> Unit) {
    val bitmap = remember(state.path) { BitmapFactory.decodeFile(state.path)?.asImageBitmap() }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val thingName = state.name ?: state.item?.name
        if (thingName != null) ThingName(thingName, fontSize = 26.sp)
        Box(
            Modifier.fillMaxWidth().weight(1f).background(ReviewBackdrop),
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
                    Image(bitmap, contentDescription = thingName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(stringResource(R.string.photo_review_missing_photo), color = Color.White, fontSize = 24.sp)
                }
            }
        }
        Button(onRedo, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight), role = PillRole.Warning, icon = Icons.Filled.Refresh) { Text(stringResource(R.string.photo_review_redo_action), fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        Button(onDone, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight), role = PillRole.Success, icon = Icons.Filled.Check) { Text(stringResource(R.string.photo_review_done_action), fontSize = 23.sp, fontWeight = FontWeight.Bold) }
    }
}
