package ru.rfidhunter.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import ru.rfidhunter.app.R
import ru.rfidhunter.app.ui.AppState
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.ui.NewTagWaitReason
import ru.rfidhunter.app.ui.components.Button
import ru.rfidhunter.app.ui.components.ThingName
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.Dimens
import ru.rfidhunter.app.ui.theme.LocalExtendedColors

@Composable
internal fun WaitForTagScreen(state: AppState.WaitForNewTag, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when (val reason = state.reason) {
            NewTagWaitReason.Initial -> Text(stringResource(R.string.wait_tag_initial_hint), style = AppStyles.Headline, textAlign = TextAlign.Center)
            NewTagWaitReason.NoTag -> Text(stringResource(R.string.wait_tag_no_tag_hint), style = AppStyles.Subtitle, textAlign = TextAlign.Center)
            NewTagWaitReason.MultipleTags -> Text(stringResource(R.string.wait_tag_multiple_tags_hint), style = AppStyles.Subtitle, textAlign = TextAlign.Center)
            is NewTagWaitReason.AlreadyAssigned -> {
                Text(stringResource(R.string.wait_tag_already_assigned_hint), style = AppStyles.Subtitle, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                ThingName(reason.item.name, fontSize = 30.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        ThingName(state.name, fontSize = 24.sp)
        if (viewModel.fakeGateway != null) Button(viewModel::simulateTriggerPress, Modifier.fillMaxWidth().height(Dimens.PillButtonHeightCompact)) { Text(stringResource(R.string.demo_read_tag_button), fontSize = 19.sp) }
    }
}

@Composable
internal fun AddSuccessScreen(state: AppState.AddSuccess) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AnimatedSuccessCheck()
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.add_success_title), style = AppStyles.ScreenTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        ThingName(state.name, fontSize = 32.sp)
    }
}

@Composable
private fun AnimatedSuccessCheck() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
    }
    val successColor = LocalExtendedColors.current.success
    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val value = progress.value
            drawCircle(color = successColor, radius = (size.minDimension / 2f) * value)
            val checkAlpha = ((value - 0.5f) * 2f).coerceIn(0f, 1f)
            if (checkAlpha > 0f) {
                val unit = size.minDimension
                val cx = size.width / 2f
                val cy = size.height / 2f
                val stroke = (unit * 0.03f).coerceIn(10f, 16f)
                val checkColor = Color.White.copy(alpha = checkAlpha)
                drawLine(
                    checkColor,
                    Offset(cx - unit * 0.17f, cy + unit * 0.02f),
                    Offset(cx - unit * 0.04f, cy + unit * 0.15f),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    checkColor,
                    Offset(cx - unit * 0.04f, cy + unit * 0.15f),
                    Offset(cx + unit * 0.17f, cy - unit * 0.13f),
                    stroke,
                    StrokeCap.Round,
                )
            }
        }
    }
}
