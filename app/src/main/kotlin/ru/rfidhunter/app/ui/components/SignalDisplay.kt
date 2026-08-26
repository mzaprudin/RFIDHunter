package ru.rfidhunter.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rfidhunter.app.R
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.ui.formatSearchRssi
import ru.rfidhunter.app.ui.normalizedSignalStrengthPercent
import ru.rfidhunter.app.ui.theme.AppStyles
import ru.rfidhunter.app.ui.theme.SignalMedium
import ru.rfidhunter.app.ui.theme.SignalStrong
import ru.rfidhunter.app.ui.theme.SignalWeak

@Composable
internal fun LinearSignalMeter(signalStrength: Float, modifier: Modifier = Modifier) {
    // DrawScope runs outside composition; resolve themed colors up front.
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Canvas(modifier) {
        val barWidth = size.width * 0.34f
        val barLeft = size.width * 0.58f - barWidth / 2f
        val barTop = size.height * 0.08f
        val barBottom = size.height * 0.82f
        val barHeight = barBottom - barTop
        val cornerRadius = barWidth / 2f
        val fillHeight = barHeight * signalStrength.coerceIn(0f, 1f)

        drawRoundRect(
            color = trackColor,
            topLeft = androidx.compose.ui.geometry.Offset(barLeft, barTop),
            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
        )
        if (fillHeight > 0f) {
            drawRoundRect(
                color = signalColor(signalStrength),
                topLeft = androidx.compose.ui.geometry.Offset(barLeft, barBottom - fillHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, fillHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            )
        }
        repeat(5) { index ->
            val y = barBottom - barHeight * index / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = androidx.compose.ui.geometry.Offset(barLeft, y),
                end = androidx.compose.ui.geometry.Offset(barLeft + barWidth, y),
                strokeWidth = 4f,
            )
        }

        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = (size.width * 0.20f).coerceIn(30f, 52f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        listOf(
            barBottom to "0",
            (barTop + barBottom) / 2f to "50",
            barTop to "100",
        ).forEach { (y, label) ->
            drawContext.canvas.nativeCanvas.drawText(label, size.width * 0.16f, y + labelPaint.textSize * 0.35f, labelPaint)
        }
    }
}

@Composable
internal fun SignalReadout(animatedStrength: Float, rssi: Int?) {
    val percent = normalizedSignalStrengthPercent(animatedStrength)
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.search_signal_strength_label),
            modifier = Modifier.weight(1f),
            fontSize = 21.sp,
            fontWeight = FontWeight.Medium,
        )
        Column(
            modifier = Modifier.width(128.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "$percent%",
                modifier = Modifier.fillMaxWidth(),
                color = signalColor(animatedStrength),
                fontSize = 31.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
                textAlign = TextAlign.End,
            )
            Text(
                formatSearchRssi(rssi),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = AppStyles.Caption,
                lineHeight = 18.sp,
                textAlign = TextAlign.End,
            )
        }
    }
}

internal fun signalColor(strength: Float): Color = when {
    strength <= 0f -> SignalWeak
    strength < 0.35f -> SignalWeak
    strength < 0.70f -> SignalMedium
    else -> SignalStrong
}

@Composable
internal fun SearchItemPhoto(item: Item, width: androidx.compose.ui.unit.Dp = 160.dp) {
    val bitmap = remember(item.photoPath) {
        item.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    Box(
        Modifier
            .width(width)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            PhotoPlaceholder(Modifier.fillMaxSize(), cornerRadius = 14.dp)
        }
    }
}
