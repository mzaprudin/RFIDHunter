package ru.rfidhunter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import ru.rfidhunter.app.R
import ru.rfidhunter.app.ui.screens.AddSuccessScreen
import ru.rfidhunter.app.ui.screens.CatalogScreen
import ru.rfidhunter.app.ui.screens.CameraScreen
import ru.rfidhunter.app.ui.screens.ConfirmScreen
import ru.rfidhunter.app.ui.screens.IdleScreen
import ru.rfidhunter.app.ui.screens.ItemCardScreen
import ru.rfidhunter.app.ui.screens.MessageScreen
import ru.rfidhunter.app.ui.screens.PhotoReviewScreen
import ru.rfidhunter.app.ui.screens.ReaderPreparationScreen
import ru.rfidhunter.app.ui.screens.SearchPausedScreen
import ru.rfidhunter.app.ui.screens.SearchProgressScreen
import ru.rfidhunter.app.ui.screens.SearchTargetScreen
import ru.rfidhunter.app.ui.screens.ServiceScreen
import ru.rfidhunter.app.ui.screens.StatusScreen
import ru.rfidhunter.app.ui.screens.VoiceConfirmationScreen
import ru.rfidhunter.app.ui.screens.VoiceListeningScreen
import ru.rfidhunter.app.ui.screens.WaitForTagScreen
import ru.rfidhunter.app.ui.components.LocalScreenTouchEnabled
import ru.rfidhunter.app.ui.components.StatusBanner
import ru.rfidhunter.app.ui.theme.Dimens
import ru.rfidhunter.app.ui.theme.RfidHunterTheme
import ru.rfidhunter.app.voice.OfflinePromptPlayer

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

    CompositionLocalProvider(LocalScreenTouchEnabled provides screenTouchEnabled) {
        RfidHunterTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                    StatusBanner(announcement, Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPaddingH))
                }
                Box(Modifier.weight(1f)) {
                    if (state is AppState.Service) {
                        ServiceScreen(viewModel, connection, readerScanState, discoveredDevices, readerDiagnostic, readerReady, recentReads, pendingSyncCount, syncSettings)
                    } else {
                        UserScreen {
                            ScreenTransition(state) { target ->
                                when (val screen = target) {
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
                                    AppState.InitialChecking -> StatusScreen(stringResource(R.string.status_checking_tag))
                                    is AppState.AddName -> VoiceListeningScreen(AppState.VoiceListening(VoiceInputMode.ADD, screen.pendingEpc, screen.renameItem), voiceBackend, viewModel::openCatalog)
                                    is AppState.Camera -> CameraScreen(screen, viewModel, cameraPermission, requestCameraPermission)
                                    is AppState.PhotoReview -> PhotoReviewScreen(screen, viewModel::redoPhoto, viewModel::acceptPhoto)
                                    is AppState.WaitForNewTag -> WaitForTagScreen(screen, viewModel)
                                    is AppState.AddSuccess -> AddSuccessScreen(screen)
                                    is AppState.VoiceListening -> VoiceListeningScreen(screen, voiceBackend, viewModel::openCatalog)
                                    is AppState.VoiceConfirmation -> VoiceConfirmationScreen(screen, viewModel)
                                    AppState.AskSearchCommand -> VoiceListeningScreen(AppState.VoiceListening(VoiceInputMode.SEARCH), voiceBackend, viewModel::openCatalog)
                                    is AppState.ReaderPreparation -> ReaderPreparationScreen(screen)
                                    is AppState.SearchTarget -> SearchTargetScreen(screen, readerReady || viewModel.fakeGateway != null)
                                    is AppState.RfidSearch -> SearchProgressScreen(screen, readerReady || viewModel.fakeGateway != null)
                                    is AppState.SearchPaused -> SearchPausedScreen(screen, readerReady || viewModel.fakeGateway != null, viewModel)
                                    is AppState.ItemCard -> ItemCardScreen(screen.item, viewModel)
                                    is AppState.Catalog -> CatalogScreen(screen, items, viewModel)
                                    is AppState.MultipleTags -> MessageScreen(screen.message, viewModel::openCatalog)
                                    is AppState.Message -> ConfirmScreen(
                                        text = screen.text,
                                        highlightedTitle = screen.highlightedTitle,
                                        onConfirm = { viewModel.confirmMessage(true) },
                                        onCancel = { viewModel.confirmMessage(false) },
                                    )
                                    AppState.Service -> Unit
                                    is AppState.Error -> MessageScreen(screen.text, viewModel::closeService)
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/** Shared safe-area and spacing contract for every ordinary user flow. */
@Composable
private fun UserScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) { content() }
}

@Composable
private fun ScreenTransition(state: AppState, content: @Composable (AppState) -> Unit) {
    AnimatedContent(
        targetState = state,
        // Logical screen identity: excludes RSSI, voice progress flags,
        // selected catalogue row — high-frequency updates never re-run the fade.
        contentKey = { touchGateKey(it) },
        transitionSpec = {
            // The camera exits quickly: its viewfinder keeps streaming while
            // the outgoing slot is composed and photo decode already loads
            // the main thread during Camera -> PhotoReview.
            val exitDuration = if (initialState is AppState.Camera) 70 else 160
            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(exitDuration))
        },
        label = "screen-transition",
    ) { target -> content(target) }
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

// Raw event strings, not resources: they are matched against viewModel
// events and double as audio prompt keys. Display twins live in
// strings.xml (voice_status_listening / voice_status_recognizing).
private val VOICE_INLINE_STATUSES = setOf("Слушаю", "Распознаю речь")
