package com.sahidcode404.photonex2.camera

/** Resolves burst length from the actual preview exposure, unless the selected lens has an override. */
object ExposurePlanner {
    fun chooseFrameCount(
        manualOverride: Int?,
        exposureTimeNs: Long?,
        sensitivityIso: Int?,
    ): Int = resolveFrameCount(
        setting = if (manualOverride == null) FrameCountSetting(true, LensPreferences.DEFAULT_MANUAL)
        else FrameCountSetting(false, manualOverride),
        exposureTimeNs = exposureTimeNs,
        sensitivityIso = sensitivityIso,
    )

    fun resolveFrameCount(
        setting: FrameCountSetting,
        exposureTimeNs: Long?,
        sensitivityIso: Int?,
    ): Int {
        if (!setting.automatic) {
            return setting.count.coerceIn(LensPreferences.MIN_FRAMES, LensPreferences.MAX_FRAMES)
        }

        val exposureMs = (exposureTimeNs ?: 8_000_000L).coerceAtLeast(100_000L) / 1_000_000.0
        val iso = (sensitivityIso ?: 200).coerceAtLeast(25)
        val lightLoad = exposureMs * (iso / 100.0)
        return when {
            lightLoad <= 3.0 -> 3
            lightLoad <= 12.0 -> 4
            lightLoad <= 40.0 -> 5
            lightLoad <= 120.0 -> 6
            else -> 8
        }
    }
}
