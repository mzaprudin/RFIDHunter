package ru.rfidhunter.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min

/**
 * Small dependency-free GIF renderer for the trigger hint on the home screen.
 * The project deliberately does not add an image-loading library just for one
 * local animation; [Movie] is available on the app's minSdk (28).
 */
@Composable
fun TriggerGif(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> TriggerGifView(context) },
        modifier = modifier,
    )
}

@Suppress("DEPRECATION")
private class TriggerGifView(context: Context) : View(context) {
    private val movie: Movie? = runCatching {
        context.assets.open("rfid-reader-trigger.gif").use(Movie::decodeStream)
    }.getOrNull()
    private var startedAt = 0L

    init {
        // Movie draws through the software canvas on older Android releases.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMovie = movie ?: return
        val sourceWidth = currentMovie.width()
        val sourceHeight = currentMovie.height()
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return

        val duration = currentMovie.duration().coerceAtLeast(1)
        val elapsed = ((SystemClock.uptimeMillis() - startedAt) % duration).toInt()
        currentMovie.setTime(elapsed)
        val scale = min(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val drawWidth = sourceWidth * scale
        val drawHeight = sourceHeight * scale
        canvas.save()
        canvas.translate((width - drawWidth) / 2f, (height - drawHeight) / 2f)
        canvas.scale(scale, scale)
        currentMovie.draw(canvas, 0f, 0f)
        canvas.restore()
        postInvalidateDelayed(16L)
    }
}
