package com.sahidcode404.photonex2.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import android.view.SurfaceHolder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln

/**
 * Two-phase Camera2 discovery modeled after the CamX startup/topology split.
 *
 * Startup only inspects the public Java camera list and the minimum metadata needed to choose one
 * credible RAW route quickly. Complete AUX discovery is deliberately deferred until after the first
 * preview frame and reconciles Java public IDs, logical-camera physical members, and Camera-NDK IDs.
 *
 * Physical members remain expressed as (logical open ID + physical target ID). A physical member is
 * not discarded just because its standalone characteristics omit a RAW/private stream inventory:
 * vendor implementations commonly expose that inventory on the logical parent. In that case parent
 * stream evidence is used provisionally and the real session is the final usability test.
 */
class CameraDiscovery(private val cameraManager: CameraManager) {

    /** Fast first-frame seed. No physical-camera walk and no native discovery occurs here. */
    fun discoverStartupRawRoute(): LensRoute? {
        val ids = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())
            .distinct()
            .take(MAX_PUBLIC_IDS)
        if (ids.isEmpty()) return null

        val ranked = ids.mapNotNull { id -> readSeed(id) }
            .sortedWith(seedComparator)

        // Full stream inspection is performed only for ranked candidates until one RAW route works.
        for (seed in ranked) {
            val characteristics = runCatching {
                cameraManager.getCameraCharacteristics(seed.id)
            }.getOrNull() ?: continue
            buildPublicRoute(seed.id, characteristics)?.let { return it }
        }
        return null
    }

    /** Complete post-first-frame discovery. */
    fun discoverRawRoutes(): List<LensRoute> {
        val javaIds = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())
        val ndkIds = NdkCameraIdSource.advertisedIds()
        val publicIds = LinkedHashSet<String>()
        javaIds.asSequence().take(MAX_PUBLIC_IDS).filter { it.isNotBlank() }.forEach(publicIds::add)
        ndkIds.asSequence().take(MAX_NDK_IDS).filter { it.isNotBlank() }.forEach(publicIds::add)

        val routes = ArrayList<LensRoute>()
        for (publicId in publicIds.take(MAX_PUBLIC_IDS + MAX_NDK_IDS)) {
            val parent = runCatching { cameraManager.getCameraCharacteristics(publicId) }.getOrNull()
                ?: continue
            buildPublicRoute(publicId, parent)?.let(routes::add)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLogical(parent)) {
                parent.physicalCameraIds
                    .asSequence()
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(MAX_PHYSICAL_IDS)
                    .forEach { physicalId ->
                        val physical = runCatching {
                            cameraManager.getCameraCharacteristics(physicalId)
                        }.getOrNull()
                        buildPhysicalRoute(
                            openId = publicId,
                            physicalId = physicalId,
                            parent = parent,
                            physical = physical,
                        )?.let(routes::add)
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

    private fun readSeed(id: String): SeedEvidence? {
        if (id.isBlank()) return null
        val characteristics = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
            ?: return null
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val hasPrivatePreview = runCatching {
            !streamMap.getOutputSizes(SurfaceHolder::class.java).isNullOrEmpty() ||
                !streamMap.getOutputSizes(SurfaceTexture::class.java).isNullOrEmpty()
        }.getOrDefault(false)
        if (!hasPrivatePreview) return null

        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val backwardCompatible = caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
        )
        val focals = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.asSequence()
            ?.filter { it.isFinite() && it > 0f }
            ?.distinct()
            ?.take(MAX_FOCAL_LENGTHS)
            ?.toList()
            .orEmpty()
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
        return SeedEvidence(
            id = id,
            facing = (characteristics.get(CameraCharacteristics.LENS_FACING) ?: -1).toLensFacing(),
            backwardCompatible = backwardCompatible,
            opticalEvidenceRank = when {
                focals.isNotEmpty() && sensorSize != null -> 2
                focals.isNotEmpty() || sensorSize != null -> 1
                else -> 0
            },
            opaqueOrder = opaqueOrderKey(id),
        )
    }

    private fun buildPublicRoute(
        openId: String,
        characteristics: CameraCharacteristics,
    ): LensRoute? {
        val evidence = streamEvidence(characteristics)
        if (evidence.rawSizes.isEmpty() || evidence.previewSizes.isEmpty()) return null
        return routeFromEvidence(
            openId = openId,
            physicalId = null,
            metadata = characteristics,
            fallbackMetadata = null,
            previewSizes = evidence.previewSizes,
            rawSizes = evidence.rawSizes,
            isLogical = isLogical(characteristics),
        )
    }

    private fun buildPhysicalRoute(
        openId: String,
        physicalId: String,
        parent: CameraCharacteristics,
        physical: CameraCharacteristics?,
    ): LensRoute? {
        val parentStreams = streamEvidence(parent)
        val physicalStreams = physical?.let(::streamEvidence)

        // Preserve the relationship even on vendor HALs where the member's standalone metadata is
        // incomplete. The targeted session below is the authoritative usability test.
        val previewSizes = physicalStreams?.previewSizes.orEmpty()
            .ifEmpty { parentStreams.previewSizes }
        val rawSizes = physicalStreams?.rawSizes.orEmpty()
            .ifEmpty { parentStreams.rawSizes }
        if (previewSizes.isEmpty() || rawSizes.isEmpty()) return null

        return routeFromEvidence(
            openId = openId,
            physicalId = physicalId,
            metadata = physical ?: parent,
            fallbackMetadata = parent,
            previewSizes = previewSizes,
            rawSizes = rawSizes,
            isLogical = false,
        )
    }

    private fun routeFromEvidence(
        openId: String,
        physicalId: String?,
        metadata: CameraCharacteristics,
        fallbackMetadata: CameraCharacteristics?,
        previewSizes: List<Size>,
        rawSizes: List<Size>,
        isLogical: Boolean,
    ): LensRoute? {
        val raw = rawSizes.maxWithOrNull(compareBy<Size>({ it.width.toLong() * it.height }, { it.width }))
            ?: return null
        val preview = choosePreview(previewSizes, raw)

        val focal = metadata.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.asSequence()?.filter { it.isFinite() && it > 0f }?.minOrNull()
            ?: fallbackMetadata?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.asSequence()?.filter { it.isFinite() && it > 0f }?.minOrNull()
        val sensorSize = metadata.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.takeIf { it.width.isFinite() && it.width > 0f }
            ?: fallbackMetadata?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.takeIf { it.width.isFinite() && it.width > 0f }
        val equivalent = if (focal != null && sensorSize != null) focal * 36f / sensorSize.width else null

        val facing = (metadata.get(CameraCharacteristics.LENS_FACING) ?: -1).toLensFacing()
            .takeUnless { it == LensFacing.UNKNOWN }
            ?: (fallbackMetadata?.get(CameraCharacteristics.LENS_FACING) ?: -1).toLensFacing()
        val orientation = metadata.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?.takeIf(::validOrientation)
            ?: fallbackMetadata?.get(CameraCharacteristics.SENSOR_ORIENTATION)?.takeIf(::validOrientation)
            ?: 0

        val routeKey = buildString {
            append(openId.toByteArray(Charsets.UTF_8).size).append(':').append(openId)
            append('|')
            if (physicalId == null) append("public")
            else append(physicalId.toByteArray(Charsets.UTF_8).size).append(':').append(physicalId)
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

    private fun streamEvidence(characteristics: CameraCharacteristics): StreamEvidence {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return StreamEvidence(emptyList(), emptyList())
        val texture = runCatching { map.getOutputSizes(SurfaceTexture::class.java) }
            .getOrNull()?.filterValid().orEmpty()
        val holder = runCatching { map.getOutputSizes(SurfaceHolder::class.java) }
            .getOrNull()?.filterValid().orEmpty()
        val preview = (texture + holder).distinct().take(MAX_STREAM_SIZES)

        // Some vendor HALs under-report REQUEST_AVAILABLE_CAPABILITIES while still advertising a
        // valid RAW_SENSOR stream. Stream evidence wins over that optional capability bit.
        val raw = runCatching { map.getOutputSizes(ImageFormat.RAW_SENSOR) }
            .getOrNull()?.filterValid().orEmpty()
        return StreamEvidence(preview, raw)
    }

    /**
     * Keep every distinct physical AUX route. Only suppress the common main-sensor physical duplicate
     * when the same logical parent already exposes an optically equivalent direct route. Direct public
     * control is preferred for that duplicate, matching the reference topology projector.
     */
    private fun canonicalize(input: List<LensRoute>): List<LensRoute> {
        val direct = input.filterNot { it.isPhysicalRoute }
        val physical = input.filter { it.isPhysicalRoute }
        val output = ArrayList<LensRoute>(input.size)
        output += direct
        for (candidate in physical) {
            val duplicateOfDirect = direct.any { publicRoute ->
                publicRoute.openCameraId == candidate.openCameraId && sameOpticalLens(publicRoute, candidate)
            }
            if (!duplicateOfDirect) output += candidate
        }
        return output.distinctBy(LensRoute::key)
    }

    private fun sameOpticalLens(a: LensRoute, b: LensRoute): Boolean {
        if (a.facing != b.facing) return false
        val ae = a.equivalentFocalLengthMm
        val be = b.equivalentFocalLengthMm
        if (ae != null && be != null) return abs(ae - be) <= 1.0f
        val af = a.focalLengthMm
        val bf = b.focalLengthMm
        return af != null && bf != null && abs(af - bf) <= 0.10f && a.rawSize == b.rawSize
    }

    /** Responsive private-preview choice with center-crop-aware scoring. */
    private fun choosePreview(options: List<Size>, raw: Size): Size {
        val unique = options.filter { it.width > 0 && it.height > 0 }.distinct()
        require(unique.isNotEmpty())
        val rawRatio = raw.width.toDouble() / raw.height.toDouble()
        val responsive = unique.filter { size ->
            val longEdge = maxOf(size.width, size.height)
            val shortEdge = minOf(size.width, size.height)
            longEdge <= 1920 && shortEdge <= 1440
        }.ifEmpty { unique }
        val targetArea = 1920.0 * 1080.0
        return responsive.minWith(
            compareBy<Size>(
                { abs((it.width.toDouble() / it.height.toDouble()) - rawRatio) },
                { abs(ln(((it.width.toDouble() * it.height.toDouble()) / targetArea).coerceAtLeast(1e-6))) },
                { -it.width.toLong() * it.height.toLong() },
                { it.width },
                { it.height },
            ),
        )
    }

    private fun Array<Size>.filterValid(): List<Size> =
        asSequence().filter { it.width > 0 && it.height > 0 }.distinct().take(MAX_STREAM_SIZES).toList()

    private fun isLogical(c: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }

    private fun validOrientation(value: Int): Boolean = value in 0..270 && value % 90 == 0

    private fun facingRank(facing: LensFacing): Int = when (facing) {
        LensFacing.BACK -> 0
        LensFacing.FRONT -> 1
        LensFacing.EXTERNAL -> 2
        LensFacing.UNKNOWN -> 3
    }

    private data class StreamEvidence(
        val previewSizes: List<Size>,
        val rawSizes: List<Size>,
    )

    private data class SeedEvidence(
        val id: String,
        val facing: LensFacing,
        val backwardCompatible: Boolean,
        val opticalEvidenceRank: Int,
        val opaqueOrder: String,
    )

    private val seedComparator = compareBy<SeedEvidence>(
        { facingRank(it.facing) },
        { if (it.backwardCompatible) 0 else 1 },
        { -it.opticalEvidenceRank },
        { it.opaqueOrder },
    )

    private fun opaqueOrderKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("photonex2-seed|$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_PUBLIC_IDS = 64
        const val MAX_NDK_IDS = 64
        const val MAX_PHYSICAL_IDS = 64
        const val MAX_FOCAL_LENGTHS = 16
        const val MAX_STREAM_SIZES = 128
    }
}

fun lensDisplayName(route: LensRoute, all: List<LensRoute>): String {
    return when (route.facing) {
        LensFacing.FRONT -> "Front"
        LensFacing.EXTERNAL -> "External"
        LensFacing.UNKNOWN -> route.focalLengthMm?.let { "${formatFocal(it)} mm" } ?: "Camera"
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
            } else route.focalLengthMm?.let { "${formatFocal(it)} mm" } ?: "Rear"
        }
    }
}

private fun formatFocal(value: Float): String {
    val rounded = kotlin.math.round(value * 100f) / 100f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}
