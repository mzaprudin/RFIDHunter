package ru.rfidhunter.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.R
import ru.rfidhunter.app.ui.AppState
import ru.rfidhunter.app.ui.ReaderPreparationStage
import ru.rfidhunter.app.ui.components.ThingName
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.LocalExtendedColors

@Composable
internal fun ReaderPreparationScreen(state: AppState.ReaderPreparation) {
    val stage = when (state.stage) {
        ReaderPreparationStage.SEARCHING -> stringResource(R.string.reader_preparation_stage_searching)
        ReaderPreparationStage.CONNECTING -> stringResource(R.string.reader_preparation_stage_connecting)
        ReaderPreparationStage.PREPARING -> stringResource(R.string.reader_preparation_stage_preparing)
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.reader_preparation_title), style = AppStyles.Headline, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        ThingName(state.item.name, fontSize = 30.sp)
        Spacer(Modifier.height(28.dp))
        ReaderPreparationStepper(state.stage)
        Spacer(Modifier.height(20.dp))
        Text(stage, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.reader_preparation_hint),
            modifier = Modifier.fillMaxWidth(),
            style = AppStyles.BodyMedium,
            lineHeight = 28.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReaderPreparationStepper(stage: ReaderPreparationStage) {
    val steps = listOf(
        ReaderPreparationStage.SEARCHING to stringResource(R.string.reader_preparation_step_searching),
        ReaderPreparationStage.CONNECTING to stringResource(R.string.reader_preparation_step_connecting),
        ReaderPreparationStage.PREPARING to stringResource(R.string.reader_preparation_step_preparing),
    )
    val currentIndex = steps.indexOfFirst { it.first == stage }.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { index, (_, label) ->
            if (index > 0) {
                Box(
                    Modifier.weight(1f)
                        .padding(top = 11.5.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (index <= currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StepperCircle(completed = index < currentIndex, active = index == currentIndex)
                Spacer(Modifier.height(6.dp))
                val isCurrent = index == currentIndex
                Text(
                    label,
                    style = AppStyles.Hint,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isCurrent) FontWeight.Bold else null,
                )
            }
        }
    }
}

@Composable
private fun StepperCircle(completed: Boolean, active: Boolean) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val successColor = LocalExtendedColors.current.success
    val upcomingColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(26.dp)) {
        when {
            completed -> {
                drawCircle(successColor)
                drawCircle(Color.White, radius = 4.dp.toPx())
            }
            active -> {
                drawCircle(primaryColor)
                drawCircle(primaryColor.copy(alpha = 0.35f), radius = 10.dp.toPx(), style = Stroke(width = 6.dp.toPx()))
            }
            else -> drawCircle(upcomingColor)
        }
    }
}
