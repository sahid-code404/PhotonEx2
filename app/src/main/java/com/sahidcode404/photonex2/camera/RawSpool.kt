package com.sahidcode404.photonex2.camera

import android.media.Image
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.abs

/** A compact, app-private RAW16 spool. Camera Images are copied and closed immediately. */
data class SpoolFrame(
    val file: File,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val sampleStep: Int,
    val proxyWidth: Int,
    val proxyHeight: Int,
    val proxy: IntArray,
    val sharpness: Double,
)

object RawSpool {
    fun copy(image: Image, directory: File, sequence: Int): SpoolFrame {
        require(image.format == android.graphics.ImageFormat.RAW_SENSOR) { "Expected RAW_SENSOR" }
        require(image.planes.size == 1) { "RAW_SENSOR must expose one plane" }
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 2) { "Unsupported RAW pixel stride: $pixelStride" }
        require(rowStride >= width * pixelStride) { "Invalid RAW row stride" }

        directory.mkdirs()
        val file = File(directory, "frame-${sequence.toString().padStart(2, '0')}-${image.timestamp}.raw16")
        val step = chooseSampleStep(width, height)
        val proxyWidth = (width + step - 1) / step
        val proxyHeight = (height + step - 1) / step
        val proxy = IntArray(proxyWidth * proxyHeight)
        val source = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val baseOffset = source.position()
        val requiredEnd = baseOffset.toLong() + (height - 1L) * rowStride + width.toLong() * pixelStride
        require(requiredEnd <= source.limit().toLong()) { "RAW plane buffer is smaller than its strides" }

        // Most RAW_SENSOR buffers are already tightly packed RAW16. Copy that memory in large channel
        // writes instead of issuing one Java stream write per sensor row. Proxy samples are collected
        // with absolute reads so the Camera Image can still be closed as soon as this function returns.
        if (pixelStride == 2 && rowStride == width * 2) {
            FileOutputStream(file).channel.use { channel ->
                val contiguous = source.duplicate()
                contiguous.position(baseOffset)
                contiguous.limit(baseOffset + width * height * 2)
                while (contiguous.hasRemaining()) channel.write(contiguous)
            }
            fillProxyFromSource(
                source = source,
                baseOffset = baseOffset,
                rowStride = rowStride,
                pixelStride = pixelStride,
                width = width,
                height = height,
                step = step,
                proxyWidth = proxyWidth,
                proxy = proxy,
            )
        } else {
            val compactRow = ByteArray(width * 2)
            BufferedOutputStream(FileOutputStream(file), 1024 * 1024).use { out ->
                for (y in 0 until height) {
                    val rowStart = baseOffset + y * rowStride
                    var x = 0
                    while (x < width) {
                        val sourceOffset = rowStart + x * pixelStride
                        compactRow[x * 2] = source.get(sourceOffset)
                        compactRow[x * 2 + 1] = source.get(sourceOffset + 1)
                        x += 1
                    }
                    out.write(compactRow)

                    if (y % step == 0) {
                        val py = y / step
                        var px = 0
                        x = 0
                        while (x < width) {
                            val lo = compactRow[x * 2].toInt() and 0xff
                            val hi = compactRow[x * 2 + 1].toInt() and 0xff
                            proxy[py * proxyWidth + px] = lo or (hi shl 8)
                            px += 1
                            x += step
                        }
                    }
                }
            }
        }

        return SpoolFrame(
            file = file,
            timestampNs = image.timestamp,
            width = width,
            height = height,
            sampleStep = step,
            proxyWidth = proxyWidth,
            proxyHeight = proxyHeight,
            proxy = proxy,
            sharpness = normalizedSharpness(proxy, proxyWidth, proxyHeight),
        )
    }

    private fun fillProxyFromSource(
        source: java.nio.ByteBuffer,
        baseOffset: Int,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        step: Int,
        proxyWidth: Int,
        proxy: IntArray,
    ) {
        var y = 0
        while (y < height) {
            val py = y / step
            val rowStart = baseOffset + y * rowStride
            var px = 0
            var x = 0
            while (x < width) {
                val offset = rowStart + x * pixelStride
                val lo = source.get(offset).toInt() and 0xff
                val hi = source.get(offset + 1).toInt() and 0xff
                proxy[py * proxyWidth + px] = lo or (hi shl 8)
                px += 1
                x += step
            }
            y += step
        }
    }

    /** Finer even sampling materially reduces registration blur while keeping proxy memory bounded. */
    private fun chooseSampleStep(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        return when {
            pixels > 48_000_000L -> 8
            pixels > 24_000_000L -> 6
            else -> 4
        }
    }

    private fun normalizedSharpness(values: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var laplacian = 0.0
        var mean = 0.0
        var count = 0L
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val c = values[i]
                val l = values[i - 1]
                val r = values[i + 1]
                val u = values[i - width]
                val d = values[i + width]
                laplacian += abs(4.0 * c - l - r - u - d)
                mean += c
                count += 1
            }
        }
        if (count == 0L) return 0.0
        val avgSignal = mean / count
        return (laplacian / count) / (avgSignal + 32.0)
    }
}
