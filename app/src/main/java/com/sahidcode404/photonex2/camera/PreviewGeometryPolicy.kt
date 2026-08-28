package com.sahidcode404.photonex2.camera

import kotlin.math.max

data class PreviewGeometrySpec(
    val clockwiseRotationDegrees: Int,
    val mirrorHorizontally: Boolean,
    val scale: Float,
    val renderedWidth: Float,
    val renderedHeight: Float,
)

/** Pure preview geometry matching the reference sensor/display rotation and center-crop policy. */
object PreviewGeometryPolicy {
    fun resolve(
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        lensFacing: LensFacing,
        mirrorFrontPreview: Boolean = true,
    ): PreviewGeometrySpec {
        require(viewWidth > 0 && viewHeight > 0)
        require(streamWidth > 0 && streamHeight > 0)
        require(sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0)
        require(displayRotationDegrees in setOf(0, 90, 180, 270))

        val rotation = rotationDegrees(
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
            lensFacing = lensFacing,
        )
        val swapAxes = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapAxes) streamHeight else streamWidth
        val rotatedHeight = if (swapAxes) streamWidth else streamHeight
        val scale = max(
            viewWidth.toDouble() / rotatedWidth.toDouble(),
            viewHeight.toDouble() / rotatedHeight.toDouble(),
        )
        require(scale.isFinite() && scale > 0.0)
        return PreviewGeometrySpec(
            clockwiseRotationDegrees = rotation,
            mirrorHorizontally = lensFacing == LensFacing.FRONT && mirrorFrontPreview,
            scale = scale.toFloat(),
            renderedWidth = (rotatedWidth * scale).toFloat(),
            renderedHeight = (rotatedHeight * scale).toFloat(),
        )
    }

    fun rotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        lensFacing: LensFacing,
    ): Int {
        require(sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0)
        require(displayRotationDegrees in setOf(0, 90, 180, 270))
        return when (lensFacing) {
            LensFacing.FRONT -> Math.floorMod(sensorOrientationDegrees + displayRotationDegrees, 360)
            LensFacing.BACK,
            LensFacing.EXTERNAL,
            LensFacing.UNKNOWN,
            -> Math.floorMod(sensorOrientationDegrees - displayRotationDegrees, 360)
        }
    }

    /** Maps one source-buffer pixel coordinate into the final displayed coordinate system. */
    fun mapBufferPoint(
        x: Float,
        y: Float,
        streamWidth: Int,
        streamHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        geometry: PreviewGeometrySpec,
    ): Pair<Float, Float> {
        val dx = x - streamWidth / 2f
        val dy = y - streamHeight / 2f
        val rotated = when (geometry.clockwiseRotationDegrees) {
            0 -> dx to dy
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> error("Non-orthogonal preview rotation")
        }
        var rx = rotated.first * geometry.scale
        val ry = rotated.second * geometry.scale
        if (geometry.mirrorHorizontally) rx = -rx
        return (viewWidth / 2f + rx) to (viewHeight / 2f + ry)
    }
}
