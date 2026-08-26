package ru.rfidhunter.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.rfidhunter.app.ui.theme.Dimens

@Composable
internal fun PhotoPlaceholder(modifier: Modifier = Modifier, cornerRadius: Dp = Dimens.PhotoCorner) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val figureColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Box(
        modifier.clip(RoundedCornerShape(cornerRadius)).background(backgroundColor),
        Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.4f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val lineStyle = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(
                color = figureColor,
                topLeft = Offset(left, top),
                size = Size(side, side),
                cornerRadius = CornerRadius(side * 0.18f, side * 0.18f),
                style = lineStyle,
            )
            drawCircle(
                color = figureColor,
                radius = side * 0.10f,
                center = Offset(left + side * 0.33f, top + side * 0.33f),
                style = lineStyle,
            )
            val mountains = Path().apply {
                moveTo(left + side * 0.16f, top + side * 0.76f)
                lineTo(left + side * 0.40f, top + side * 0.46f)
                lineTo(left + side * 0.56f, top + side * 0.63f)
                lineTo(left + side * 0.70f, top + side * 0.51f)
                lineTo(left + side * 0.86f, top + side * 0.67f)
            }
            drawPath(mountains, figureColor, style = lineStyle)
        }
    }
}
