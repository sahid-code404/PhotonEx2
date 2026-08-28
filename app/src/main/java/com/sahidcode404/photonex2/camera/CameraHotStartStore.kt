package com.sahidcode404.photonex2.camera

import android.content.Context
import android.os.Build
import android.util.Size

/**
 * Tiny verified-route snapshot used only to get a previously working preview back on screen quickly.
 * Full camera topology is never persisted here. Build fingerprint/API changes invalidate the entry.
 */
class CameraHotStartStore(context: Context) {
    private val prefs = context.getSharedPreferences("camera_hot_start", Context.MODE_PRIVATE)

    fun load(): LensRoute? {
        if (prefs.getInt(KEY_SCHEMA, 0) != SCHEMA) return null
        if (prefs.getString(KEY_BUILD, null) != Build.FINGERPRINT) return null
        if (prefs.getInt(KEY_API, -1) != Build.VERSION.SDK_INT) return null

        val key = prefs.getString(KEY_ROUTE_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val openId = prefs.getString(KEY_OPEN_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val physicalId = prefs.getString(KEY_PHYSICAL_ID, null)?.takeIf { it.isNotBlank() }
        val rawWidth = prefs.getInt(KEY_RAW_WIDTH, 0)
        val rawHeight = prefs.getInt(KEY_RAW_HEIGHT, 0)
        val previewWidth = prefs.getInt(KEY_PREVIEW_WIDTH, 0)
        val previewHeight = prefs.getInt(KEY_PREVIEW_HEIGHT, 0)
        if (rawWidth <= 0 || rawHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return null
        val orientation = prefs.getInt(KEY_ORIENTATION, -1)
        if (orientation !in setOf(0, 90, 180, 270)) return null
        val facing = runCatching {
            LensFacing.valueOf(prefs.getString(KEY_FACING, LensFacing.UNKNOWN.name).orEmpty())
        }.getOrDefault(LensFacing.UNKNOWN)
        val focal = prefs.getFloat(KEY_FOCAL, Float.NaN).takeIf { it.isFinite() && it > 0f }
        val equivalent = prefs.getFloat(KEY_EQUIVALENT, Float.NaN).takeIf { it.isFinite() && it > 0f }

        return LensRoute(
            key = key,
            openCameraId = openId,
            physicalCameraId = physicalId,
            facing = facing,
            focalLengthMm = focal,
            equivalentFocalLengthMm = equivalent,
            rawSize = Size(rawWidth, rawHeight),
            previewSize = Size(previewWidth, previewHeight),
            sensorOrientationDegrees = orientation,
            isLogicalRoute = prefs.getBoolean(KEY_LOGICAL, false),
        )
    }

    fun save(route: LensRoute) {
        prefs.edit()
            .putInt(KEY_SCHEMA, SCHEMA)
            .putString(KEY_BUILD, Build.FINGERPRINT)
            .putInt(KEY_API, Build.VERSION.SDK_INT)
            .putString(KEY_ROUTE_KEY, route.key)
            .putString(KEY_OPEN_ID, route.openCameraId)
            .putString(KEY_PHYSICAL_ID, route.physicalCameraId)
            .putString(KEY_FACING, route.facing.name)
            .putInt(KEY_RAW_WIDTH, route.rawSize.width)
            .putInt(KEY_RAW_HEIGHT, route.rawSize.height)
            .putInt(KEY_PREVIEW_WIDTH, route.previewSize.width)
            .putInt(KEY_PREVIEW_HEIGHT, route.previewSize.height)
            .putInt(KEY_ORIENTATION, route.sensorOrientationDegrees)
            .putBoolean(KEY_LOGICAL, route.isLogicalRoute)
            .apply {
                if (route.focalLengthMm != null) putFloat(KEY_FOCAL, route.focalLengthMm)
                else remove(KEY_FOCAL)
                if (route.equivalentFocalLengthMm != null) putFloat(KEY_EQUIVALENT, route.equivalentFocalLengthMm)
                else remove(KEY_EQUIVALENT)
            }
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val SCHEMA = 1
        const val KEY_SCHEMA = "schema"
        const val KEY_BUILD = "build"
        const val KEY_API = "api"
        const val KEY_ROUTE_KEY = "route_key"
        const val KEY_OPEN_ID = "open_id"
        const val KEY_PHYSICAL_ID = "physical_id"
        const val KEY_FACING = "facing"
        const val KEY_RAW_WIDTH = "raw_width"
        const val KEY_RAW_HEIGHT = "raw_height"
        const val KEY_PREVIEW_WIDTH = "preview_width"
        const val KEY_PREVIEW_HEIGHT = "preview_height"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_LOGICAL = "logical"
        const val KEY_FOCAL = "focal"
        const val KEY_EQUIVALENT = "equivalent"
    }
}
