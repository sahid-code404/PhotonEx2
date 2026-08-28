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
 * Sharpness-first Bayer-domain computational RAW merger.
 *
 * Frames remain RAW16 throughout. The sharpest frame anchors geometry; blurry or badly aligned
 * samples are rejected, remaining shifts stay even-pixel to preserve CFA phase, and a per-pixel
 * residual gate prevents moving edges/subjects from being averaged into a soft ghosted result.
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
        // Do not allow a large noisy burst to drag a genuinely sharp reference into softness.
        val blurFloor = max(sharpest.spool.sharpness * 0.60, median * 0.78)
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
                val dx = even(alignment.first * step)
                val dy = even(alignment.second * step)
                shifted += ShiftedFrame(candidate, dx, dy, alignment.third)
            } else {
                rejectedMotion += 1
            }
        }

        val accepted = shifted.ifEmpty { listOf(ShiftedFrame(sharpest, 0, 0, 0.0)) }
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 65535
        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)

        outputDir.mkdirs()
        val merged = File(outputDir, "merged-${System.nanoTime()}.raw16")
        val rowBytes = width * 2
        val readers = accepted.map { RandomAccessFile(it.frame.spool.file, "r") }
        val rows = Array(accepted.size) { ByteArray(rowBytes) }
        val rowValid = BooleanArray(accepted.size)
        val bestSharpness = sharpest.spool.sharpness.coerceAtLeast(1e-9)
        val frameWeights = accepted.mapIndexed { index, shiftedFrame ->
            if (index == 0) {
                REFERENCE_WEIGHT
            } else {
                val sharpnessWeight = (shiftedFrame.frame.spool.sharpness / bestSharpness)
                    .coerceIn(0.0, 1.0)
                val alignmentWeight = (1.0 - shiftedFrame.alignmentError / MAX_ALIGNMENT_ERROR)
                    .coerceIn(0.15, 1.0)
                (sharpnessWeight * alignmentWeight).coerceIn(MIN_CANDIDATE_WEIGHT, 1.0)
            }
        }
        val exposureScales = accepted.map { exposureScale(sharpest, it.frame) }
        val sensorRange = whiteLevel.toDouble().coerceAtLeast(1.0)

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
                        readers[i].seek(sourceY.toLong() * rowBytes)
                        readers[i].readFully(rows[i])
                        rowValid[i] = true
                    }

                    for (x in 0 until width) {
                        val blackLevel = blackPattern
                            ?.getOffsetForIndex(x and 1, y and 1)
                            ?.toDouble()
                            ?: 0.0
                        val referenceRaw = readRaw(rows[0], x)
                        val referenceNormalized = normalizeRaw(
                            raw = referenceRaw,
                            blackLevel = blackLevel,
                            whiteLevel = whiteLevel,
                            exposureScale = exposureScales[0],
                        )

                        var weighted = referenceNormalized * frameWeights[0]
                        var totalWeight = frameWeights[0]
                        for (i in 1 until accepted.size) {
                            if (!rowValid[i]) continue
                            val sourceX = x + accepted[i].dx
                            if (sourceX !in 0 until width) continue
                            val raw = readRaw(rows[i], sourceX)
                            val normalized = normalizeRaw(
                                raw = raw,
                                blackLevel = blackLevel,
                                whiteLevel = whiteLevel,
                                exposureScale = exposureScales[i],
                            )
                            var weight = frameWeights[i]

                            // Saturated samples contain little reconstructive information.
                            if (raw >= whiteLevel * 0.985) weight *= SATURATED_WEIGHT

                            // Local residual is the deghosting gate. Static fine detail still averages;
                            // shifted hands/leaves/people stay dominated by the sharp reference frame.
                            val residual = abs(normalized - referenceNormalized) / sensorRange
                            weight *= when {
                                residual >= HARD_RESIDUAL -> HARD_MOTION_WEIGHT
                                residual >= MEDIUM_RESIDUAL -> MEDIUM_MOTION_WEIGHT
                                residual >= SOFT_RESIDUAL -> SOFT_MOTION_WEIGHT
                                else -> 1.0
                            }
                            weighted += normalized * weight
                            totalWeight += weight
                        }

                        val value = (weighted / totalWeight)
                            .roundToInt()
                            .coerceIn(0, whiteLevel)
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
    private fun estimateAlignment(
        reference: CapturedRawFrame,
        candidate: CapturedRawFrame,
    ): Triple<Int, Int, Double> {
        val ref = reference.spool
        val other = candidate.spool
        if (ref.proxyWidth != other.proxyWidth || ref.proxyHeight != other.proxyHeight ||
            ref.sampleStep != other.sampleStep
        ) {
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

    private fun readRaw(row: ByteArray, x: Int): Int {
        val offset = x * 2
        return (row[offset].toInt() and 0xff) or
            ((row[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun normalizeRaw(
        raw: Int,
        blackLevel: Double,
        whiteLevel: Int,
        exposureScale: Double,
    ): Double = ((raw - blackLevel) * exposureScale + blackLevel)
        .coerceIn(0.0, whiteLevel.toDouble())

    private fun exposureScale(reference: CapturedRawFrame, candidate: CapturedRawFrame): Double {
        val refExposure = reference.exposureTimeNs?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        val refIso = reference.sensitivityIso?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        val candidateExposure = candidate.exposureTimeNs?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        val candidateIso = candidate.sensitivityIso?.toDouble()?.takeIf { it > 0 } ?: return 1.0
        return ((refExposure * refIso) / (candidateExposure * candidateIso)).coerceIn(0.25, 4.0)
    }

    private fun even(value: Int): Int =
        if (value % 2 == 0) value else value + (if (value > 0) -1 else 1)

    private const val MAX_PROXY_SHIFT = 6
    private const val MAX_ALIGNMENT_ERROR = 0.115
    private const val REFERENCE_WEIGHT = 1.80
    private const val MIN_CANDIDATE_WEIGHT = 0.12
    private const val SATURATED_WEIGHT = 0.12
    private const val SOFT_RESIDUAL = 0.018
    private const val MEDIUM_RESIDUAL = 0.040
    private const val HARD_RESIDUAL = 0.075
    private const val SOFT_MOTION_WEIGHT = 0.55
    private const val MEDIUM_MOTION_WEIGHT = 0.20
    private const val HARD_MOTION_WEIGHT = 0.035
}
