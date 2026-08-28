package com.sahidcode404.photonex2.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Capture metadata paired to an already-spooled RAW frame. */
data class CapturedRawFrame(
    val spool: SpoolFrame,
    val result: CaptureResult,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
)

data class MergeOutcome(
    val mergedFile: File,
    val reference: CapturedRawFrame,
    val acceptedCount: Int,
    val rejectedBlurCount: Int,
    val rejectedMotionCount: Int,
)

private data class ShiftedFrame(
    val frame: CapturedRawFrame,
    val dx: Int,
    val dy: Int,
    val alignmentError: Double,
)

/**
 * Raw-domain multi-frame merger.
 *
 * The algorithm intentionally stays in the Bayer mosaic domain: it never demosaics or encodes an
 * intermediate JPEG/YUV image. It rejects low-sharpness samples, estimates only even-pixel global
 * translation (preserving Bayer phase), exposure-normalizes accepted frames, then performs a
 * weighted RAW16 merge row-by-row using bounded heap memory.
 */
object RawBurstMerger {
    fun merge(
        frames: List<CapturedRawFrame>,
        characteristics: CameraCharacteristics,
        outputDir: File,
        onProgress: (String) -> Unit = {},
    ): MergeOutcome {
        require(frames.isNotEmpty()) { "No RAW frames captured" }
        val width = frames.first().spool.width
        val height = frames.first().spool.height
        require(frames.all { it.spool.width == width && it.spool.height == height }) {
            "RAW burst dimensions changed"
        }

        val sharpest = frames.maxBy { it.spool.sharpness }
        val sortedSharpness = frames.map { it.spool.sharpness }.sorted()
        val median = sortedSharpness[sortedSharpness.size / 2]
        val blurFloor = max(sharpest.spool.sharpness * 0.34, median * 0.55)
        val blurAccepted = frames.filter { it === sharpest || it.spool.sharpness >= blurFloor }
        val rejectedBlur = frames.size - blurAccepted.size

        onProgress("Aligning ${blurAccepted.size} sharp frames")
        val shifted = mutableListOf(ShiftedFrame(sharpest, 0, 0, 0.0))
        var rejectedMotion = 0
        for (candidate in blurAccepted) {
            if (candidate === sharpest) continue
            val alignment = estimateAlignment(sharpest, candidate)
            if (alignment.third <= MAX_ALIGNMENT_ERROR) {
                val step = sharpest.spool.sampleStep
                val dx = alignment.first * step
                val dy = alignment.second * step
                // RAW Bayer phase must not change. Round any odd shift to the nearest even pixel.
                shifted += ShiftedFrame(candidate, even(dx), even(dy), alignment.third)
            } else {
                rejectedMotion += 1
            }
        }

        // Never throw away the best frame. A badly moving burst gracefully falls back to one RAW.
        val accepted = shifted.ifEmpty { listOf(ShiftedFrame(sharpest, 0, 0, 0.0)) }
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 65535
        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevel = if (blackPattern != null) {
            (blackPattern.getOffsetForIndex(0, 0) +
                blackPattern.getOffsetForIndex(1, 0) +
                blackPattern.getOffsetForIndex(0, 1) +
                blackPattern.getOffsetForIndex(1, 1)) / 4.0
        } else 0.0

        outputDir.mkdirs()
        val merged = File(outputDir, "merged-${System.nanoTime()}.raw16")
        val rowBytes = width * 2
        val readers = accepted.map { RandomAccessFile(it.frame.spool.file, "r") }
        val rows = Array(accepted.size) { ByteArray(rowBytes) }
        val rowValid = BooleanArray(accepted.size)
        val bestSharpness = sharpest.spool.sharpness.coerceAtLeast(1e-9)
        val weights = accepted.map { shiftedFrame ->
            (shiftedFrame.frame.spool.sharpness / bestSharpness).coerceIn(0.25, 1.0)
        }
        val exposureScales = accepted.map { exposureScale(sharpest, it.frame) }

        try {
            BufferedOutputStream(FileOutputStream(merged), 1024 * 1024).use { out ->
                val outputRow = ByteArray(rowBytes)
                for (y in 0 until height) {
                    for (i in accepted.indices) {
                        val sourceY = y + accepted[i].dy
                        if (sourceY !in 0 until height) {
                            rowValid[i] = false
                            continue
                        }
                        val reader = readers[i]
                        reader.seek(sourceY.toLong() * rowBytes)
                        reader.readFully(rows[i])
                        rowValid[i] = true
                    }

                    for (x in 0 until width) {
                        var weighted = 0.0
                        var totalWeight = 0.0
                        for (i in accepted.indices) {
                            if (!rowValid[i]) continue
                            val sourceX = x + accepted[i].dx
                            if (sourceX !in 0 until width) continue
                            val row = rows[i]
                            val offset = sourceX * 2
                            val raw = (row[offset].toInt() and 0xff) or
                                ((row[offset + 1].toInt() and 0xff) shl 8)
                            val scale = exposureScales[i]
                            val normalized = ((raw - blackLevel) * scale + blackLevel)
                                .coerceIn(0.0, whiteLevel.toDouble())
                            var weight = weights[i]
                            // Downweight samples that are already close to sensor saturation.
                            if (raw >= whiteLevel * 0.985) weight *= 0.15
                            weighted += normalized * weight
                            totalWeight += weight
                        }
                        val value = if (totalWeight > 0.0) {
                            (weighted / totalWeight).roundToInt().coerceIn(0, whiteLevel)
                        } else 0
                        outputRow[x * 2] = (value and 0xff).toByte()
                        outputRow[x * 2 + 1] = ((value ushr 8) and 0xff).toByte()
                    }
                    out.write(outputRow)
                    if (y % 256 == 0) {
                        onProgress("Merging ${accepted.size} frames · ${(y * 100 / height).coerceIn(0, 99)}%")
                    }
                }
            }
        } finally {
            readers.forEach { runCatching { it.close() } }
        }

        onProgress("Writing DNG")
        return MergeOutcome(
            mergedFile = merged,
            reference = sharpest,
            acceptedCount = accepted.size,
            rejectedBlurCount = rejectedBlur,
            rejectedMotionCount = rejectedMotion,
        )
    }

    /** Returns proxy shift X/Y and a scale-normalized absolute-error score. */
    private fun estimateAlignment(reference: CapturedRawFrame, candidate: CapturedRawFrame): Triple<Int, Int, Double> {
        val ref = reference.spool
        val other = candidate.spool
        if (ref.proxyWidth != other.proxyWidth || ref.proxyHeight != other.proxyHeight) {
            return Triple(0, 0, Double.POSITIVE_INFINITY)
        }
        val width = ref.proxyWidth
        val height = ref.proxyHeight
        val exposureScale = exposureScale(reference, candidate)
        var bestX = 0
        var bestY = 0
        var bestError = Double.POSITIVE_INFINITY

        for (dy in -MAX_PROXY_SHIFT..MAX_PROXY_SHIFT) {
            for (dx in -MAX_PROXY_SHIFT..MAX_PROXY_SHIFT) {
                var error = 0.0
                var signal = 0.0
                var count = 0
                val border = MAX_PROXY_SHIFT + 2
                for (y in border until height - border step 2) {
                    val oy = y + dy
                    if (oy !in 0 until height) continue
                    for (x in border until width - border step 2) {
                        val ox = x + dx
                        if (ox !in 0 until width) continue
                        val a = ref.proxy[y * width + x].toDouble()
                        val b = other.proxy[oy * width + ox] * exposureScale
                        error += abs(a - b)
                        signal += abs(a)
                        count += 1
                    }
                }
                if (count == 0) continue
                val normalized = error / (signal + count * 32.0)
                if (normalized < bestError) {
                    bestError = normalized
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return Triple(bestX, bestY, bestError)
    }

    private fun exposureScale(reference: CapturedRawFrame, candidate: CapturedRawFrame): Double {
        val refExposure = reference.exposureTimeNs?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        val refIso = reference.sensitivityIso?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        val candidateExposure = candidate.exposureTimeNs?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        val candidateIso = candidate.sensitivityIso?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        return ((refExposure * refIso) / (candidateExposure * candidateIso)).coerceIn(0.25, 4.0)
    }

    private fun even(value: Int): Int =
        if (value % 2 == 0) value else value + (if (value > 0) -1 else 1)

    private const val MAX_PROXY_SHIFT = 4
    private const val MAX_ALIGNMENT_ERROR = 0.18
}
