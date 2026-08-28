package com.sahidcode404.photonex2.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryPolicyTest {
    @Test
    fun backCameraUsesSensorMinusDisplayRotation() {
        assertEquals(90, PreviewGeometryPolicy.rotationDegrees(90, 0, LensFacing.BACK))
        assertEquals(0, PreviewGeometryPolicy.rotationDegrees(90, 90, LensFacing.BACK))
        assertEquals(270, PreviewGeometryPolicy.rotationDegrees(90, 180, LensFacing.BACK))
    }

    @Test
    fun frontCameraUsesSensorPlusDisplayRotation() {
        assertEquals(270, PreviewGeometryPolicy.rotationDegrees(270, 0, LensFacing.FRONT))
        assertEquals(0, PreviewGeometryPolicy.rotationDegrees(270, 90, LensFacing.FRONT))
        assertEquals(90, PreviewGeometryPolicy.rotationDegrees(270, 180, LensFacing.FRONT))
    }

    @Test
    fun centerCropUsesOneUniformScale() {
        val geometry = PreviewGeometryPolicy.resolve(
            viewWidth = 1080,
            viewHeight = 1800,
            streamWidth = 1920,
            streamHeight = 1080,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            lensFacing = LensFacing.BACK,
        )
        assertEquals(90, geometry.clockwiseRotationDegrees)
        assertEquals(1800f, geometry.renderedHeight, 0.01f)
        assertTrue(geometry.renderedWidth >= 1080f)
    }

    @Test
    fun frontPreviewMirrorsOnlyWhenRequested() {
        val mirrored = PreviewGeometryPolicy.resolve(1080, 1800, 1920, 1080, 270, 0, LensFacing.FRONT, true)
        val plain = PreviewGeometryPolicy.resolve(1080, 1800, 1920, 1080, 270, 0, LensFacing.FRONT, false)
        assertTrue(mirrored.mirrorHorizontally)
        assertTrue(!plain.mirrorHorizontally)
    }
}
