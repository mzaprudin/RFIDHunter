package ru.rfidhunter.app.ui.screens

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.BuildConfig
import ru.rfidhunter.app.R
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.SearchSignalPolicy
import ru.rfidhunter.app.ui.AppState
import ru.rfidhunter.app.ui.GeigerClickPlayer
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.LinearSignalMeter
import ru.rfidhunter.app.ui.components.SearchItemPhoto
import ru.rfidhunter.app.ui.components.SignalReadout
import ru.rfidhunter.app.ui.components.ThingName
import ru.rfidhunter.app.ui.theme.AppStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val GEIGER_LOG_TAG = "GeigerClick"

@Composable
internal fun SearchTargetScreen(state: AppState.SearchTarget, readerReady: Boolean) {
    SearchScreen(
        item = state.item,
        rssi = null,
        instruction = if (readerReady) stringResource(R.string.search_target_hold_trigger_hint) else stringResource(R.string.search_reader_disabled_hint),
    )
}

@Composable
internal fun SearchProgressScreen(state: AppState.RfidSearch, readerReady: Boolean) {
    if (readerReady) GeigerClickFeedback(state.signalStrength)
    SearchScreen(
        item = state.item,
        rssi = state.rssi,
        instruction = if (readerReady) {
            stringResource(R.string.search_progress_move_hint)
        } else {
            stringResource(R.string.search_reader_disabled_hint)
        },
    )
}

@Composable
internal fun SearchPausedScreen(state: AppState.SearchPaused, readerReady: Boolean, viewModel: MainViewModel) {
    SearchScreen(
        item = state.item,
        rssi = state.lastRssi,
        instruction = if (readerReady) {
            stringResource(R.string.search_paused_resume_hint)
        } else {
            stringResource(R.string.search_reader_disabled_hint)
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
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.search_title), style = AppStyles.ScreenTitle, textAlign = TextAlign.Center)
        }
        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            // Match the item-card title treatment and size so the selected
            // thing remains equally legible while searching.
            ThingName(item.name, fontSize = 30.sp, includePrefix = false)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                SearchItemPhoto(item, width = 180.dp)
                Spacer(Modifier.width(12.dp))
                LinearSignalMeter(
                    animatedVisualStrength,
                    Modifier.weight(1f).height(240.dp),
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
                style = AppStyles.BodyMedium,
                lineHeight = 27.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(74.dp), contentAlignment = Alignment.Center) {
            if (onFinish != null) {
                Button(onFinish, Modifier.fillMaxSize(), icon = Icons.Filled.Close) { Text(stringResource(R.string.search_finish_action), fontSize = 21.sp) }
            }
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
