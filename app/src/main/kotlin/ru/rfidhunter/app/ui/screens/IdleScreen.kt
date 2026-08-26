package ru.rfidhunter.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.R
import ru.rfidhunter.app.domain.ConnectionState
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.ReaderScanState
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.ui.TriggerGif
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.OutlinedButton
import ru.rfidhunter.app.ui.components.TextButton
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.Dimens

private data class ReaderStatusUi(val text: String, val dotColor: Color, val inProgress: Boolean)

@Composable
private fun ReaderStatusCard(status: ReaderStatusUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCorner),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val transition = rememberInfiniteTransition(label = "reader-status")
            val alpha by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "reader-status-pulse")
            Box(
                Modifier.size(12.dp)
                    .graphicsLayer { this.alpha = if (status.inProgress) alpha else 1f }
                    .clip(CircleShape)
                    .background(status.dotColor),
            )
            Spacer(Modifier.width(10.dp))
            Text(status.text, style = AppStyles.BodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun IdleScreen(
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
    val scanningInstruction = stringResource(R.string.idle_scanning_instruction)
    val connectingInstruction = stringResource(R.string.idle_connecting_instruction)
    val preparingInstruction = stringResource(R.string.idle_preparing_instruction)
    val disconnectedInstruction = stringResource(R.string.idle_disconnected_instruction)
    val readerStatus = when {
        scanState == ReaderScanState.SCANNING -> ReaderStatusUi("Поиск считывателя", MaterialTheme.colorScheme.primary, true)
        connection == ConnectionState.CONNECTING -> ReaderStatusUi("Подключение к IH25", MaterialTheme.colorScheme.primary, true)
        connection == ConnectionState.CONNECTED && !readerReady -> ReaderStatusUi("Подготовка считывателя", MaterialTheme.colorScheme.primary, true)
        else -> null
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.idle_title),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            style = AppStyles.ScreenTitle,
            textAlign = TextAlign.Center,
        )
        if (readerStatus != null) ReaderStatusCard(readerStatus)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (showInstruction) {
                    stringResource(R.string.idle_trigger_hint)
                } else if (scanState == ReaderScanState.SCANNING) {
                    scanningInstruction
                } else if (connection == ConnectionState.CONNECTING) {
                    connectingInstruction
                } else if (connection == ConnectionState.CONNECTED) {
                    preparingInstruction
                } else if (connection == ConnectionState.DISCONNECTED && viewModel.fakeGateway == null) {
                    disconnectedInstruction
                } else {
                    disconnectedInstruction
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
                    Modifier.fillMaxWidth().height(Dimens.PillButtonHeight),
                    icon = Icons.Filled.Info,
                ) {
                    Text(stringResource(R.string.idle_allow_trigger_action), style = AppStyles.ButtonLabelSmall)
                }
            }
            if (viewModel.fakeGateway != null) Button(viewModel::simulateTriggerPress, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight)) { Text(stringResource(R.string.demo_trigger_button), style = AppStyles.ButtonLabel) }
            Button(viewModel::openCatalog, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight), icon = Icons.AutoMirrored.Filled.List) { Text(stringResource(R.string.idle_open_catalog_format, items.size), style = AppStyles.ButtonLabel) }
            OutlinedButton(viewModel::beginAddByVoice, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight), icon = Icons.Filled.Add) { Text(stringResource(R.string.idle_add_by_voice_action), style = AppStyles.ButtonLabel) }
        }
        if (viewModel.fakeGateway != null) TextButton(viewModel::openService) { Text(stringResource(R.string.dev_service_button)) }
    }
}
