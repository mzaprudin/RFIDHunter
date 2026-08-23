package ru.rfidhunter.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Keeps catalog photos in a predictable, bandwidth-friendly format: portrait
 * 3:4, at most 1080x1440, JPEG quality 82. Smaller photos are never enlarged.
 * The source is never kept after the
 * destination has been written successfully.
 */
object PhotoProcessor {
    const val TARGET_WIDTH = 1080
    const val TARGET_HEIGHT = 1440
    const val JPEG_QUALITY = 82

    fun normalize(source: File, destination: File = source): Boolean {
        if (!source.isFile) return false
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var cropped: Bitmap? = null
        var resized: Bitmap? = null
        var temporary: File? = null
        var completed = false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val orientation = runCatching {
                ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val swapsDimensions = orientation in setOf(
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE,
            )
            val orientedWidth = if (swapsDimensions) bounds.outHeight else bounds.outWidth
            val orientedHeight = if (swapsDimensions) bounds.outWidth else bounds.outHeight
            val sample = calculateSample(orientedWidth, orientedHeight)
            decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return false
            oriented = applyOrientation(decoded!!, orientation)
            cropped = cropToThreeFour(oriented!!)
            val output = outputSize(cropped!!.width, cropped!!.height)
            resized = if (cropped!!.width != output.width || cropped!!.height != output.height) {
                Bitmap.createScaledBitmap(cropped!!, output.width, output.height, true)
            } else cropped

            destination.parentFile?.mkdirs()
            temporary = destination.parentFile?.let { File(it, ".${destination.name}.normalized") }
                ?: File(destination.absolutePath + ".normalized")
            temporary!!.outputStream().use { outputStream ->
                if (!resized!!.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                    error("JPEG compression failed")
                }
            }
            // Keep the source until the compressed file has been finalized. If
            // the platform refuses to rename over an existing file, move that
            // file aside first and restore it when finalization fails.
            val renamed = temporary!!.renameTo(destination) || run {
                val backup = File(destination.parentFile ?: destination.absoluteFile.parentFile!!, ".${destination.name}.backup")
                if (backup.exists() && !backup.delete()) {
                    false
                } else if (!destination.renameTo(backup)) {
                    false
                } else {
                    val finalized = temporary!!.renameTo(destination)
                    if (finalized) backup.delete() else backup.renameTo(destination)
                    finalized
                }
            }
            if (!renamed) error("Could not finalize compressed photo")
            completed = true
            true
        } catch (_: Exception) {
            false
        } finally {
            resized?.recycle()
            if (cropped != null && cropped !== resized) cropped!!.recycle()
            if (oriented != null && oriented !== cropped && oriented !== resized) oriented!!.recycle()
            if (decoded != null && decoded !== oriented && decoded !== cropped && decoded !== resized) decoded!!.recycle()
            temporary?.delete()
            if (completed && source != destination) source.delete()
        }
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= TARGET_WIDTH && height / (sample * 2) >= TARGET_HEIGHT) sample *= 2
        return sample
    }

    internal data class OutputSize(val width: Int, val height: Int)

    /** The crop already has a 3:4 ratio, so downscaling only needs one side. */
    internal fun outputSize(width: Int, height: Int): OutputSize {
        if (width <= TARGET_WIDTH && height <= TARGET_HEIGHT) return OutputSize(width, height)
        val scale = minOf(TARGET_WIDTH.toFloat() / width, TARGET_HEIGHT.toFloat() / height)
        return OutputSize(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropToThreeFour(bitmap: Bitmap): Bitmap {
        val targetRatio = TARGET_WIDTH.toFloat() / TARGET_HEIGHT
        val sourceRatio = bitmap.width.toFloat() / bitmap.height
        if (kotlin.math.abs(sourceRatio - targetRatio) < 0.001f) return bitmap
        val cropWidth: Int
        val cropHeight: Int
        if (sourceRatio > targetRatio) {
            cropHeight = bitmap.height
            cropWidth = (cropHeight * targetRatio).toInt()
        } else {
            cropWidth = bitmap.width
            cropHeight = (cropWidth / targetRatio).toInt()
        }
        val left = (bitmap.width - cropWidth) / 2
        val top = (bitmap.height - cropHeight) / 2
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }
}
