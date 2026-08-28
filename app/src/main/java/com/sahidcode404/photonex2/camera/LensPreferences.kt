package com.sahidcode404.photonex2.camera

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FrameCountSetting(val automatic: Boolean, val count: Int)

/** Persistent per-route burst length. Opaque route keys remain internal app data. */
class LensPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("lens_capture_settings", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun setting(routeKey: String): FrameCountSetting {
        val stored = prefs.getInt("frames_$routeKey", AUTO_SENTINEL)
        return if (stored == AUTO_SENTINEL) FrameCountSetting(true, DEFAULT_MANUAL)
        else FrameCountSetting(false, stored.coerceIn(MIN_FRAMES, MAX_FRAMES))
    }

    fun getManualFrameCount(routeKey: String): Int? {
        val setting = setting(routeKey)
        return if (setting.automatic) null else setting.count
    }

    fun setManualFrameCount(routeKey: String, count: Int?) {
        if (count == null) setAutomatic(routeKey) else setManual(routeKey, count)
    }

    fun setAutomatic(routeKey: String) {
        prefs.edit().putInt("frames_$routeKey", AUTO_SENTINEL).apply()
        _version.value += 1L
    }

    fun setManual(routeKey: String, count: Int) {
        prefs.edit().putInt("frames_$routeKey", count.coerceIn(MIN_FRAMES, MAX_FRAMES)).apply()
        _version.value += 1L
    }

    companion object {
        const val MIN_FRAMES = 2
        const val MAX_FRAMES = 12
        const val DEFAULT_MANUAL = 5
        private const val AUTO_SENTINEL = -1
    }
}
