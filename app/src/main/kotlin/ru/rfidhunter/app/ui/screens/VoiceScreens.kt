package ru.rfidhunter.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.R
import ru.rfidhunter.app.ui.AppState
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.ui.VoiceInputMode
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.PillRole
import ru.rfidhunter.app.ui.components.ThingName
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.Dimens
import ru.rfidhunter.app.ui.theme.LocalExtendedColors
import ru.rfidhunter.app.voice.VoiceBackend

@Composable
internal fun VoiceListeningScreen(state: AppState.VoiceListening, backend: VoiceBackend, onOpenCatalog: () -> Unit) {
    val isNewItem = state.mode == VoiceInputMode.ADD && state.renameItem == null
    val prompt = state.instruction
        ?: when {
            isNewItem -> stringResource(R.string.voice_say_name)
            state.renameItem != null -> stringResource(R.string.voice_say_new_name)
            state.mode == VoiceInputMode.SEARCH -> stringResource(R.string.voice_say_what_to_find)
            else -> stringResource(R.string.voice_say_name)
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!state.isProcessing) {
            if (isNewItem) {
                Text(stringResource(R.string.voice_adding_new_title), style = AppStyles.ScreenTitle, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.voice_say_name), style = AppStyles.Headline, textAlign = TextAlign.Center)
            } else if (state.renameItem != null) {
                Text(stringResource(R.string.voice_renaming_title), style = AppStyles.ScreenTitle, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                ThingName(state.renameItem.name, fontSize = 30.sp)
                Spacer(Modifier.height(12.dp))
                Text(prompt, fontSize = 28.sp, textAlign = TextAlign.Center)
            } else if (state.missingSearchQuery != null) {
                Text(stringResource(R.string.voice_missing_in_catalog), style = AppStyles.Subtitle, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.voice_missing_query_format, state.missingSearchQuery),
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.voice_name_another_item), style = AppStyles.Subtitle, textAlign = TextAlign.Center)
            } else {
                Text(prompt, style = AppStyles.ScreenTitle, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(28.dp))
        if (state.isProcessing) AnimatedProcessingIcon() else AnimatedMicrophone()
        Spacer(Modifier.height(20.dp))
        val status = when {
            state.isProcessing -> stringResource(R.string.voice_status_recognizing)
            state.isReady -> stringResource(R.string.voice_status_listening)
            else -> when (backend) {
                VoiceBackend.WHISPER -> stringResource(R.string.voice_status_preparing_whisper)
                VoiceBackend.VOSK -> stringResource(R.string.voice_status_preparing_vosk)
            }
        }
        Text(status, style = AppStyles.Headline, color = if (state.isReady) LocalExtendedColors.current.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (state.missingSearchQuery != null) {
            Spacer(Modifier.height(24.dp))
            Button(onOpenCatalog, Modifier.fillMaxWidth().height(Dimens.PillButtonHeight), icon = Icons.AutoMirrored.Filled.List) {
                Text(stringResource(R.string.voice_open_catalog_action), style = AppStyles.ButtonLabel, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AnimatedProcessingIcon() {
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        // DrawScope lambdas run outside composition, so resolve colors first.
        val circleColor = MaterialTheme.colorScheme.primaryContainer
        Canvas(Modifier.fillMaxSize()) { drawCircle(circleColor) }
        CircularProgressIndicator(
            modifier = Modifier.size(88.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 10.dp,
        )
    }
}

@Composable
private fun AnimatedMicrophone() {
    val transition = rememberInfiniteTransition(label = "microphone")
    val pulse by transition.animateFloat(.92f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "microphone-pulse")
    val extendedColors = LocalExtendedColors.current
    Canvas(Modifier.size(132.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }) {
        val center = size.minDimension / 2f
        drawCircle(extendedColors.successContainer, center)
        drawRoundRect(extendedColors.success, androidx.compose.ui.geometry.Offset(center - 18f, center - 34f), androidx.compose.ui.geometry.Size(36f, 62f), androidx.compose.ui.geometry.CornerRadius(18f, 18f))
        drawArc(extendedColors.success, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(center - 34f, center - 9f), size = androidx.compose.ui.geometry.Size(68f, 54f), style = Stroke(7f))
        drawLine(extendedColors.success, androidx.compose.ui.geometry.Offset(center, center + 45f), androidx.compose.ui.geometry.Offset(center, center + 59f), 7f)
        drawLine(extendedColors.success, androidx.compose.ui.geometry.Offset(center - 20f, center + 59f), androidx.compose.ui.geometry.Offset(center + 20f, center + 59f), 7f)
    }
}

@Composable
internal fun VoiceConfirmationScreen(state: AppState.VoiceConfirmation, viewModel: MainViewModel) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
            if (state.suggestedItem != null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.voice_confirm_heard_label), fontSize = 25.sp, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.voice_confirm_heard_quote_format, state.text), fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.voice_confirm_suggestion_label), fontSize = 25.sp, textAlign = TextAlign.Center)
                    ThingName(state.suggestedItem.name, fontSize = 36.sp)
                }
            } else {
                ThingName(state.text, fontSize = 40.sp)
            }
        }
        Button(
            viewModel::editVoiceConfirmation,
            Modifier.fillMaxWidth().height(Dimens.PillButtonHeight),
            role = PillRole.Warning,
            icon = Icons.Filled.Edit,
        ) { Text(stringResource(R.string.voice_confirm_edit_action), fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        Button(
            viewModel::acceptVoiceConfirmation,
            Modifier.fillMaxWidth().height(Dimens.PillButtonHeight),
            role = PillRole.Success,
            icon = if (state.suggestedItem != null) Icons.Filled.Search else Icons.Filled.Check,
        ) { Text(if (state.suggestedItem != null) stringResource(R.string.voice_confirm_yes_search_action) else stringResource(R.string.voice_confirm_done_action), fontSize = 23.sp, fontWeight = FontWeight.Bold) }
    }
}
