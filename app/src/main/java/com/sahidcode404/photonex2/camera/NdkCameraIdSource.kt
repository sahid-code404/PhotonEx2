package com.sahidcode404.photonex2.camera

import android.os.Build

/**
 * Optional Camera-NDK advertised-ID source used only by complete post-first-frame discovery.
 *
 * The native library is compiled at the API-23 baseline and resolves Camera-NDK symbols dynamically,
 * so API 23 devices never gain a strong libcamera2ndk dependency. The IDs are only discovery evidence:
 * PhotonEx2 still requires Java Camera2 characteristics/open control before a route reaches the UI.
 */
internal object NdkCameraIdSource {
    @Volatile private var loadAttempted = false
    @Volatile private var loaded = false

    fun advertisedIds(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        if (!ensureLoaded()) return emptyList()
        return runCatching { nativeCameraIds(Build.VERSION.SDK_INT) }
            .getOrNull()
            ?.asSequence()
            ?.filter { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES }
            ?.distinct()
            ?.take(MAX_IDS)
            ?.toList()
            .orEmpty()
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loadAttempted) return loaded
        loadAttempted = true
        loaded = runCatching {
            System.loadLibrary("photonex2_cameraids")
            true
        }.getOrDefault(false)
        return loaded
    }

    private external fun nativeCameraIds(androidApi: Int): Array<String>?

    private const val MAX_IDS = 64
    private const val MAX_ID_BYTES = 256
}
