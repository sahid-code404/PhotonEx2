package com.sahidcode404.photonex2.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import android.view.SurfaceTexture
import kotlin.math.abs

/**
 * Public Camera2 discovery with logical-camera physical-member routing.
 *
 * Opaque camera IDs never reach the UI. A route is exposed only when it has both a preview stream
 * and RAW_SENSOR output. Physical members are represented as (logical open ID + physical target ID)
 * instead of pretending every physical ID can be opened directly.
 */
class CameraDiscovery(private val cameraManager: CameraManager) {
    fun discoverRawRoutes(): List<LensRoute> {
        val routes = buildList {
            cameraManager.cameraIdList
                .asSequence()
                .distinct()
                .take(MAX_PUBLIC_IDS)
                .forEach publicLoop@{ publicId ->
                    val parent = runCatching { cameraManager.getCameraCharacteristics(publicId) }.getOrNull()
                        ?: return@publicLoop
                    buildRoute(publicId, null, parent, isLogical = isLogical(parent))?.let(::add)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLogical(parent)) {
                        parent.physicalCameraIds
                            .asSequence()
                            .distinct()
                            .take(MAX_PHYSICAL_IDS)
                            .forEach physicalLoop@{ physicalId ->
                                val physical = runCatching {
                                    cameraManager.getCameraCharacteristics(physicalId)
                                }.getOrNull() ?: return@physicalLoop
                                buildRoute(publicId, physicalId, physical, isLogical = false)?.let(::add)
                            }
                    }
                }
        }

        return canonicalize(routes)
            .sortedWith(
                compareBy<LensRoute>({ facingRank(it.facing) })
                    .thenBy { it.equivalentFocalLengthMm ?: Float.MAX_VALUE }
                    .thenBy { it.focalLengthMm ?: Float.MAX_VALUE }
                    .thenBy { it.key },
            )
    }

    private fun buildRoute(
        openId: String,
        physicalId: String?,
        characteristics: CameraCharacteristics,
        isLogical: Boolean,
    ): LensRoute? {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return null

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.filterValid().orEmpty()
        val previewSizes = streamMap.getOutputSizes(SurfaceTexture::class.java)?.filterValid().orEmpty()
        if (rawSizes.isEmpty() || previewSizes.isEmpty()) return null

        val raw = rawSizes.maxWithOrNull(compareBy<Size>({ it.width.toLong() * it.height }, { it.width })) ?: return null
        val preview = choosePreview(previewSizes, raw)
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.asSequence()?.filter { it.isFinite() && it > 0f }?.minOrNull()
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equivalent = if (focal != null && sensorSize != null && sensorSize.width > 0f) {
            focal * 36f / sensorSize.width
        } else null
        val facing = (characteristics.get(CameraCharacteristics.LENS_FACING) ?: -1).toLensFacing()
        val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?.takeIf { it in 0..270 && it % 90 == 0 } ?: 0
        val routeKey = buildString {
            append(openId.length).append(':').append(openId)
            append('|')
            if (physicalId == null) append("public")
            else append(physicalId.length).append(':').append(physicalId)
        }
        return LensRoute(
            key = routeKey,
            openCameraId = openId,
            physicalCameraId = physicalId,
            facing = facing,
            focalLengthMm = focal,
            equivalentFocalLengthMm = equivalent,
            rawSize = raw,
            previewSize = preview,
            sensorOrientationDegrees = orientation,
            isLogicalRoute = isLogical,
        )
    }

    /**
     * Removes the common logical-camera/main-physical duplicate while retaining distinct auxiliaries.
     * Physical routes win when their optical fingerprint matches a logical route because they provide
     * explicit sensor targeting. Public single-camera routes are retained.
     */
    private fun canonicalize(input: List<LensRoute>): List<LensRoute> {
        val output = mutableListOf<LensRoute>()
        val groups = input.groupBy { it.openCameraId }
        for ((_, routes) in groups) {
            val physical = routes.filter { it.isPhysicalRoute }
            val public = routes.filterNot { it.isPhysicalRoute }
            output += physical
            for (logical in public) {
                val duplicate = physical.any { sameOpticalLens(logical, it) }
                if (!duplicate) output += logical
            }
        }
        return output.distinctBy { route ->
            listOf(
                route.facing.name,
                (route.equivalentFocalLengthMm?.times(10)?.toInt() ?: -1).toString(),
                route.rawSize.width.toString(),
                route.rawSize.height.toString(),
                route.physicalCameraId ?: route.openCameraId,
            ).joinToString("|")
        }
    }

    private fun sameOpticalLens(a: LensRoute, b: LensRoute): Boolean {
        if (a.facing != b.facing) return false
        val ae = a.equivalentFocalLengthMm
        val be = b.equivalentFocalLengthMm
        if (ae != null && be != null) return abs(ae - be) <= 1.5f
        val af = a.focalLengthMm
        val bf = b.focalLengthMm
        return af != null && bf != null && abs(af - bf) <= 0.15f && a.rawSize == b.rawSize
    }

    private fun choosePreview(options: List<Size>, raw: Size): Size {
        val rawRatio = raw.width.toDouble() / raw.height.toDouble()
        return options
            .filter { it.width <= 1920 && it.height <= 1440 }
            .minWithOrNull(
                compareBy<Size> {
                    abs((it.width.toDouble() / it.height.toDouble()) - rawRatio)
                }.thenByDescending { it.width.toLong() * it.height },
            )
            ?: options.maxWith(compareBy<Size> { it.width.toLong() * it.height })
    }

    private fun Array<Size>.filterValid(): List<Size> =
        asSequence().filter { it.width > 0 && it.height > 0 }.distinct().take(MAX_STREAM_SIZES).toList()

    private fun isLogical(c: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }

    private fun facingRank(facing: LensFacing): Int = when (facing) {
        LensFacing.BACK -> 0
        LensFacing.FRONT -> 1
        LensFacing.EXTERNAL -> 2
        LensFacing.UNKNOWN -> 3
    }

    private companion object {
        const val MAX_PUBLIC_IDS = 64
        const val MAX_PHYSICAL_IDS = 64
        const val MAX_STREAM_SIZES = 96
    }
}

fun lensDisplayName(route: LensRoute, all: List<LensRoute>): String {
    return when (route.facing) {
        LensFacing.FRONT -> "Front"
        LensFacing.EXTERNAL -> "External"
        LensFacing.UNKNOWN -> "Camera"
        LensFacing.BACK -> {
            val backs = all.filter { it.facing == LensFacing.BACK }
            val primary = backs
                .filter { (it.equivalentFocalLengthMm ?: 99f) in 20f..35f }
                .minByOrNull { kotlin.math.abs((it.equivalentFocalLengthMm ?: 26f) - 26f) }
                ?: backs.minByOrNull { it.equivalentFocalLengthMm ?: it.focalLengthMm ?: 99f }
            val base = primary?.equivalentFocalLengthMm ?: primary?.focalLengthMm
            val current = route.equivalentFocalLengthMm ?: route.focalLengthMm
            if (base != null && current != null && base > 0f) {
                val zoom = current / base
                val rounded = kotlin.math.round(zoom * 10f) / 10f
                "${rounded}×"
            } else "Rear"
        }
    }
}
