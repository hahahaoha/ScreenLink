package com.screenlink.app.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * 把 ImageReader 出来的 RGBA 数据转成 JPEG。
 * Bitmap 和输出缓冲都复用，避免每帧都产生几 MB 垃圾。
 */
class JpegEncoder {

    private var padded: Bitmap? = null
    private var cropped: Bitmap? = null
    private var canvas: Canvas? = null
    private val stream = ByteArrayOutputStream(256 * 1024)
    private val srcRect = Rect()
    private val dstRect = Rect()

    fun encode(image: Image, quality: Int): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        var source = padded
        if (source == null || source.width != paddedWidth || source.height != height) {
            source?.recycle()
            cropped?.recycle()
            cropped = null
            canvas = null
            source = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded = source
        }

        return try {
            buffer.rewind()
            source.copyPixelsFromBuffer(buffer)

            var target = cropped
            if (target == null || target.width != width || target.height != height) {
                target?.recycle()
                target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                cropped = target
                canvas = Canvas(target)
            }
            val targetCanvas = canvas ?: Canvas(target).also { canvas = it }
            srcRect.set(0, 0, width, height)
            dstRect.set(0, 0, width, height)
            targetCanvas.drawBitmap(source, srcRect, dstRect, null)

            stream.reset()
            if (!target.compress(Bitmap.CompressFormat.JPEG, quality, stream)) return null
            stream.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    fun release() {
        padded?.recycle()
        cropped?.recycle()
        padded = null
        cropped = null
        canvas = null
    }
}
