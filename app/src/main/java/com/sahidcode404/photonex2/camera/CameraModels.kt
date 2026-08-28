package com.sahidcode404.photonex2.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

enum class LensFacing { BACK, FRONT, EXTERNAL, UNKNOWN }

data class LensRoute(
    val key: String,
    val openCameraId: String,
    val physicalCameraId: String?,
    val facing: LensFacing,
    val focalLengthMm: Float?,
    val equivalentFocalLengthMm: Float?,
    val rawSize: Size,
    val previewSize: Size,
    val sensorOrientationDegrees: Int,
    val isLogicalRoute: Boolean,
) {
    val isPhysicalRoute: Boolean get() = physicalCameraId != null
}

data class CameraUiState(
    val lenses: List<LensRoute> = emptyList(),
    val selectedLensKey: String? = null,
    val previewReady: Boolean = false,
    val capturing: Boolean = false,
    val progressText: String? = null,
    val lastSaved: String? = null,
    val error: String? = null,
    val settingsOpen: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateVersionName: String? = null,
) {
    val selectedLens: LensRoute? get() = lenses.firstOrNull { it.key == selectedLensKey }
}

internal fun Int.toLensFacing(): LensFacing = when (this) {
    CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
    CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
    CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
    else -> LensFacing.UNKNOWN
}
