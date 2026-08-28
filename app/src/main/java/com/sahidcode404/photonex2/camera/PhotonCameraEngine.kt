package com.sahidcode404.photonex2.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single owner of PhotonEx2 Camera2 device/session resources.
 *
 * Startup is intentionally two-phase: a minimal public-ID seed route reaches visible preview first;
 * complete Java/physical/Camera-NDK AUX discovery starts only after that preview is alive. Still
 * capture configures only RAW_SENSOR in addition to the private preview stream.
 */
class PhotonCameraEngine(
    private val context: Context,
    val lensPreferences: LensPreferences,
) : AutoCloseable {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val discovery = CameraDiscovery(cameraManager)
    private val saver = DngSaver(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cameraThread = HandlerThread("PhotonEx2-Camera").apply { start() }
    private val rawThread = HandlerThread("PhotonEx2-RawSpool").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val rawHandler = Handler(rawThread.looper)
    private val cameraExecutor = Executor { command -> cameraHandler.post(command) }

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    @Volatile private var started = false
    @Volatile private var generation = 0L
    @Volatile private var completeDiscoveryStarted = false
    @Volatile private var completeDiscoveryFinished = false

    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var activeRoute: LensRoute? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    @Volatile private var latestPreviewResult: CaptureResult? = null
    @Volatile private var burstCollector: BurstCollector? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val seed = withContext(Dispatchers.Default) { discovery.discoverStartupRawRoute() }
            if (!started) return@launch
            if (seed == null) {
                _state.update { it.copy(error = "No app-accessible RAW camera was found") }
                // A complete pass can still recover vendor/NDK routes that the seed path could not use.
                launchCompleteDiscoveryOnce(recoverIfNeeded = true)
                return@launch
            }
            _state.update {
                it.copy(
                    lenses = listOf(seed),
                    selectedLensKey = seed.key,
                    error = null,
                )
            }
            openSelectedWhenReady()
        }
    }

    fun pauseCamera() {
        if (!started) return
        generation += 1L
        closeCameraResources()
    }

    fun resumeCamera() {
        if (started) openSelectedWhenReady()
    }

    fun attachPreview(view: TextureView) {
        textureView = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openSelectedWhenReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                activeRoute?.let { configureTransform(view, it) }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                generation += 1L
                closeCameraResources()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (view.isAvailable) openSelectedWhenReady()
    }

    fun detachPreview(view: TextureView) {
        if (textureView === view) {
            textureView = null
            generation += 1L
            closeCameraResources()
        }
    }

    fun selectLens(key: String) {
        val target = _state.value.lenses.firstOrNull { it.key == key } ?: return
        if (target.key == _state.value.selectedLensKey || _state.value.capturing) return
        _state.update { it.copy(selectedLensKey = key, previewReady = false, error = null) }
        generation += 1L
        closeCameraResources()
        openSelectedWhenReady()
    }

    fun setSettingsOpen(open: Boolean) {
        _state.update { it.copy(settingsOpen = open) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun setUpdateAvailability(available: Boolean, versionName: String? = null) {
        _state.update { it.copy(updateAvailable = available, updateVersionName = versionName) }
    }

    fun capture() {
        if (_state.value.capturing) return
        val route = activeRoute ?: return
        val session = captureSession ?: return
        val reader = rawReader ?: return
        val device = cameraDevice ?: return
        val dngCharacteristics = activeCharacteristics ?: return
        val view = textureView ?: return
        val frameCount = ExposurePlanner.chooseFrameCount(
            manualOverride = lensPreferences.getManualFrameCount(route.key),
            exposureTimeNs = latestPreviewResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            sensitivityIso = latestPreviewResult?.get(CaptureResult.SENSOR_SENSITIVITY),
        )
        val localGeneration = generation
        val directory = File(context.cacheDir, "raw-bursts/${System.nanoTime()}").apply { mkdirs() }
        val collector = BurstCollector(
            expected = frameCount,
            onComplete = complete@{ frames ->
                if (localGeneration != generation) {
                    frames.forEach { runCatching { it.spool.file.delete() } }
                    directory.deleteRecursively()
                    return@complete
                }
                // Restore a live view immediately after sensor acquisition. Merge/save stays off-camera.
                cameraHandler.post {
                    if (localGeneration == generation) resumePreview()
                }
                processBurst(
                    frames = frames,
                    characteristics = dngCharacteristics,
                    route = route,
                    displayRotation = view.display?.rotation ?: Surface.ROTATION_0,
                    directory = directory,
                    localGeneration = localGeneration,
                )
            },
            onFailure = { message ->
                directory.deleteRecursively()
                cameraHandler.post { resumePreview() }
                _state.update { it.copy(capturing = false, progressText = null, error = message) }
            },
        )
        burstCollector = collector
        _state.update {
            it.copy(
                capturing = true,
                progressText = "Capturing $frameCount RAW frames",
                lastSaved = null,
                error = null,
            )
        }

        reader.setOnImageAvailableListener({ imageReader ->
            val current = burstCollector
            if (current !== collector) {
                imageReader.acquireNextImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = runCatching { imageReader.acquireNextImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val spool = RawSpool.copy(image, directory, collector.nextSequence())
                collector.onImage(spool)
            } catch (t: Throwable) {
                collector.fail("RAW frame copy failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                image.close()
            }
        }, rawHandler)

        val requests = runCatching {
            buildBurstRequests(
                device = device,
                reader = reader,
                route = route,
                frameCount = frameCount,
            )
        }.getOrElse {
            collector.fail("Unable to build RAW burst: ${it.message ?: it.javaClass.simpleName}")
            return
        }

        runCatching {
            // Do not interleave preview requests with a locked computational RAW burst.
            session.stopRepeating()
            session.captureBurst(
                requests,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val rawResult = physicalResultOrTotal(result, route)
                        val timestamp = rawResult.get(CaptureResult.SENSOR_TIMESTAMP)
                            ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                            ?: return
                        collector.onResult(timestamp, rawResult)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        collector.fail("RAW capture failed (${failure.reason})")
                    }
                },
                cameraHandler,
            )
        }.onFailure {
            collector.fail("RAW burst could not start: ${it.message ?: it.javaClass.simpleName}")
        }

        val timeout = (BASE_BURST_TIMEOUT_MS + frameCount * 700L).coerceAtMost(MAX_BURST_TIMEOUT_MS)
        cameraHandler.postDelayed({
            if (burstCollector === collector && !collector.isFinished()) {
                collector.fail("RAW burst timed out")
            }
        }, timeout)
    }

    private fun buildBurstRequests(
        device: CameraDevice,
        reader: ImageReader,
        route: LensRoute,
        frameCount: Int,
    ): List<CaptureRequest> {
        val controls = cameraManager.getCameraCharacteristics(route.openCameraId)
        val preview = latestPreviewResult
        val exposurePlan = manualExposurePlan(controls, preview)
        val focusDistance = preview?.get(CaptureResult.LENS_FOCUS_DISTANCE)
            ?.takeIf { it.isFinite() && it >= 0f }
        return List(frameCount) {
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                applyStabilization(this, controls)
                applyLockedFocus(this, controls, focusDistance)
                applyLockedAwb(this, controls)
                if (exposurePlan != null) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposurePlan.exposureTimeNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, exposurePlan.sensitivityIso)
                } else {
                    val aeModes = controls.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
                    if (aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }
                    if (controls.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                }
            }.build()
        }
    }

    /** Converts a long auto exposure into a faster handheld exposure by trading shutter time for ISO. */
    private fun manualExposurePlan(
        characteristics: CameraCharacteristics,
        preview: CaptureResult?,
    ): ExposureLock? {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return null
        if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) return null
        val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: return null
        if (!aeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)) return null

        val previewExposure = preview?.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L }
            ?: return null
        val previewIso = preview.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
            ?: return null
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: return null
        val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: return null

        val cappedExposure = previewExposure
            .coerceAtMost(HANDHELD_MAX_EXPOSURE_NS)
            .coerceIn(exposureRange.lower, exposureRange.upper)
        val targetSignal = previewExposure.toDouble() * previewIso.toDouble()
        val desiredIso = (targetSignal / cappedExposure.toDouble())
            .coerceIn(sensitivityRange.lower.toDouble(), sensitivityRange.upper.toDouble())
            .roundToInt()
        return ExposureLock(
            exposureTimeNs = cappedExposure,
            sensitivityIso = desiredIso,
        )
    }

    private fun applyLockedFocus(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        previewFocusDistance: Float?,
    ) {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?.takeIf { it.isFinite() && it > 0f }
        if (previewFocusDistance != null && minimumFocusDistance != null &&
            modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)
        ) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, previewFocusDistance.coerceIn(0f, minimumFocusDistance))
            return
        }
        when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
    }

    private fun applyLockedAwb(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
    ) {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        if (modes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    private fun applyStabilization(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
    ) {
        val optical = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?: intArrayOf()
        if (optical.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
        }
        val video = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?: intArrayOf()
        if (video.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
        }
    }

    private fun processBurst(
        frames: List<CapturedRawFrame>,
        characteristics: CameraCharacteristics,
        route: LensRoute,
        displayRotation: Int,
        directory: File,
        localGeneration: Long,
    ) {
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    RawBurstMerger.merge(frames, characteristics, directory) { text ->
                        _state.update { state ->
                            if (state.capturing) state.copy(progressText = text) else state
                        }
                    }
                }
                val uri = withContext(Dispatchers.IO) {
                    saver.save(
                        mergedRaw = outcome.mergedFile,
                        size = route.rawSize,
                        characteristics = characteristics,
                        captureResult = outcome.reference.result,
                        orientationDegrees = captureOrientation(route, displayRotation),
                    )
                }
                _state.update {
                    it.copy(
                        capturing = false,
                        progressText = null,
                        lastSaved = "${outcome.acceptedCount} merged · ${outcome.rejectedBlurCount} blur + ${outcome.rejectedMotionCount} motion rejected",
                        error = null,
                    )
                }
                Log.i(TAG, "Saved computational DNG: $uri")
            } catch (t: Throwable) {
                Log.e(TAG, "RAW merge/save failed", t)
                _state.update {
                    it.copy(
                        capturing = false,
                        progressText = null,
                        error = "DNG processing failed: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
            } finally {
                burstCollector = null
                runCatching { directory.deleteRecursively() }
                if (started && localGeneration == generation && !_state.value.previewReady) {
                    resumePreview()
                }
            }
        }
    }

    private fun openSelectedWhenReady() {
        if (!started) return
        val view = textureView ?: return
        if (!view.isAvailable) return
        if (cameraDevice != null || captureSession != null) return
        val route = _state.value.selectedLens ?: return
        val localGeneration = ++generation
        cameraHandler.post { openRoute(route, view, localGeneration) }
    }

    @SuppressLint("MissingPermission")
    private fun openRoute(route: LensRoute, view: TextureView, localGeneration: Long) {
        if (!started || localGeneration != generation || textureView !== view || !view.isAvailable) return
        try {
            val chars = characteristicsFor(route)
            activeCharacteristics = chars
            activeRoute = route
            configureTransform(view, route)
            cameraManager.openCamera(
                route.openCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (localGeneration != generation || !started) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createSession(camera, route, view, localGeneration)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        onRouteFailure(route, "Camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        onRouteFailure(route, "Camera error $error")
                    }
                },
                cameraHandler,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Open failed", t)
            onRouteFailure(route, "Unable to open this RAW lens: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun createSession(
        device: CameraDevice,
        route: LensRoute,
        view: TextureView,
        localGeneration: Long,
    ) {
        val texture = view.surfaceTexture ?: return
        texture.setDefaultBufferSize(route.previewSize.width, route.previewSize.height)
        val preview = Surface(texture)
        previewSurface = preview
        val reader = ImageReader.newInstance(
            route.rawSize.width,
            route.rawSize.height,
            android.graphics.ImageFormat.RAW_SENSOR,
            RAW_READER_MAX_IMAGES,
        )
        rawReader = reader

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (localGeneration != generation || device != cameraDevice) {
                    session.close()
                    return
                }
                captureSession = session
                startRepeatingPreview(device, session, preview, route)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                onRouteFailure(route, "This RAW lens rejected the preview + RAW session")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && route.isPhysicalRoute) {
                createPhysicalSession(device, route, preview, reader.surface, callback)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(preview, reader.surface), callback, cameraHandler)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Session creation failed", t)
            onRouteFailure(
                route,
                "This RAW lens rejected the preview + RAW session: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createPhysicalSession(
        device: CameraDevice,
        route: LensRoute,
        preview: Surface,
        raw: Surface,
        callback: CameraCaptureSession.StateCallback,
    ) {
        val physicalId = requireNotNull(route.physicalCameraId)
        val previewConfig = OutputConfiguration(preview).apply { setPhysicalCameraId(physicalId) }
        val rawConfig = OutputConfiguration(raw).apply { setPhysicalCameraId(physicalId) }
        device.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewConfig, rawConfig),
                cameraExecutor,
                callback,
            ),
        )
    }

    private fun startRepeatingPreview(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        route: LensRoute,
    ) {
        try {
            val controls = cameraManager.getCameraCharacteristics(route.openCameraId)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                val aeModes = controls.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
                if (aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                val afModes = controls.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                when {
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) ->
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
                val awbModes = controls.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
                if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }
                applyStabilization(this, controls)
            }.build()
            session.setRepeatingRequest(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        latestPreviewResult = physicalResultOrTotal(result, route)
                        if (!_state.value.previewReady) {
                            _state.update { it.copy(previewReady = true, error = null) }
                            launchCompleteDiscoveryOnce(recoverIfNeeded = false)
                        }
                    }
                },
                cameraHandler,
            )
            textureView?.let { configureTransform(it, route) }
        } catch (t: Throwable) {
            onRouteFailure(route, "Preview failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun launchCompleteDiscoveryOnce(recoverIfNeeded: Boolean) {
        synchronized(this) {
            if (completeDiscoveryStarted) return
            completeDiscoveryStarted = true
        }
        scope.launch {
            val routes = withContext(Dispatchers.Default) { discovery.discoverRawRoutes() }
            completeDiscoveryFinished = true
            if (!started || routes.isEmpty()) {
                if (recoverIfNeeded && routes.isEmpty()) {
                    _state.update { it.copy(error = "No usable RAW camera route survived complete discovery") }
                }
                return@launch
            }

            val previousSelected = _state.value.selectedLensKey
            val previousActive = activeRoute?.key
            val selected = routes.firstOrNull { it.key == previousSelected }
                ?: routes.firstOrNull { it.key == previousActive }
                ?: routes.firstOrNull { it.facing == LensFacing.BACK && !it.isPhysicalRoute }
                ?: routes.firstOrNull { it.facing == LensFacing.BACK }
                ?: routes.first()
            _state.update {
                it.copy(
                    lenses = routes,
                    selectedLensKey = selected.key,
                    error = if (recoverIfNeeded) null else it.error,
                )
            }

            if (activeRoute?.key != selected.key && cameraDevice == null && captureSession == null) {
                openSelectedWhenReady()
            }
        }
    }

    private fun onRouteFailure(route: LensRoute, message: String) {
        Log.w(TAG, "Route ${route.key} failed: $message")
        val current = _state.value
        val remaining = if (completeDiscoveryFinished || current.lenses.size > 1) {
            current.lenses.filterNot { it.key == route.key }
        } else {
            current.lenses
        }
        val fallback = remaining.firstOrNull { it.facing == LensFacing.BACK && !it.isPhysicalRoute }
            ?: remaining.firstOrNull { it.facing == LensFacing.BACK }
            ?: remaining.firstOrNull()
        _state.update {
            it.copy(
                lenses = remaining,
                selectedLensKey = fallback?.key ?: it.selectedLensKey,
                previewReady = false,
                error = message,
            )
        }

        if (!completeDiscoveryStarted) {
            launchCompleteDiscoveryOnce(recoverIfNeeded = true)
            return
        }
        if (fallback != null && fallback.key != route.key) {
            generation += 1L
            closeCameraResources()
            _state.update { it.copy(selectedLensKey = fallback.key, error = message) }
            openSelectedWhenReady()
        }
    }

    private fun resumePreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val preview = previewSurface ?: return
        val route = activeRoute ?: return
        cameraHandler.post { startRepeatingPreview(device, session, preview, route) }
    }

    private fun characteristicsFor(route: LensRoute): CameraCharacteristics {
        if (route.physicalCameraId != null) {
            runCatching { cameraManager.getCameraCharacteristics(route.physicalCameraId) }
                .getOrNull()
                ?.let { return it }
        }
        return cameraManager.getCameraCharacteristics(route.openCameraId)
    }

    private fun physicalResultOrTotal(result: TotalCaptureResult, route: LensRoute): CaptureResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && route.physicalCameraId != null) {
            return result.physicalCameraResults[route.physicalCameraId] ?: result
        }
        return result
    }

    /**
     * TextureView defaults to independently scaling buffer X/Y into the view. setPolyToPoly replaces
     * that with the reference's sensor-aware rotation + one uniform center-crop scale, so aspect ratio
     * can never stretch. Front mirroring is applied in final display coordinates.
     */
    private fun configureTransform(view: TextureView, route: LensRoute) {
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val displayDegrees = rotationToDegrees(view.display?.rotation ?: Surface.ROTATION_0)
        val geometry = PreviewGeometryPolicy.resolve(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            streamWidth = route.previewSize.width,
            streamHeight = route.previewSize.height,
            sensorOrientationDegrees = route.sensorOrientationDegrees,
            displayRotationDegrees = displayDegrees,
            lensFacing = route.facing,
            mirrorFrontPreview = true,
        )
        val source = floatArrayOf(
            0f, 0f,
            viewWidth.toFloat(), 0f,
            0f, viewHeight.toFloat(),
        )
        val p0 = PreviewGeometryPolicy.mapBufferPoint(
            0f, 0f,
            route.previewSize.width, route.previewSize.height,
            viewWidth, viewHeight,
            geometry,
        )
        val p1 = PreviewGeometryPolicy.mapBufferPoint(
            route.previewSize.width.toFloat(), 0f,
            route.previewSize.width, route.previewSize.height,
            viewWidth, viewHeight,
            geometry,
        )
        val p2 = PreviewGeometryPolicy.mapBufferPoint(
            0f, route.previewSize.height.toFloat(),
            route.previewSize.width, route.previewSize.height,
            viewWidth, viewHeight,
            geometry,
        )
        val destination = floatArrayOf(
            p0.first, p0.second,
            p1.first, p1.second,
            p2.first, p2.second,
        )
        val matrix = Matrix()
        check(matrix.setPolyToPoly(source, 0, destination, 0, 3)) {
            "Unable to resolve preview transform"
        }
        view.setTransform(matrix)
    }

    private fun captureOrientation(route: LensRoute, displayRotation: Int): Int {
        val device = rotationToDegrees(displayRotation)
        return if (route.facing == LensFacing.FRONT) {
            (route.sensorOrientationDegrees - device + 360) % 360
        } else {
            (route.sensorOrientationDegrees + device) % 360
        }
    }

    private fun rotationToDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun closeCameraResources() {
        burstCollector?.fail("Capture cancelled because the camera changed")
        burstCollector = null
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        activeRoute = null
        activeCharacteristics = null
        latestPreviewResult = null
        _state.update { it.copy(previewReady = false, capturing = false, progressText = null) }
    }

    override fun close() {
        started = false
        generation += 1L
        closeCameraResources()
        cameraThread.quitSafely()
        rawThread.quitSafely()
        scope.cancel()
    }

    private inner class BurstCollector(
        private val expected: Int,
        private val onComplete: (List<CapturedRawFrame>) -> Unit,
        private val onFailure: (String) -> Unit,
    ) {
        private val sequence = AtomicInteger(0)
        private val images = LinkedHashMap<Long, SpoolFrame>()
        private val results = LinkedHashMap<Long, CaptureResult>()
        private var finished = false

        fun nextSequence(): Int = sequence.incrementAndGet()

        @Synchronized
        fun onImage(frame: SpoolFrame) {
            if (finished) {
                frame.file.delete()
                return
            }
            images[frame.timestampNs] = frame
            updateCaptureProgress()
            maybeFinish()
        }

        @Synchronized
        fun onResult(timestamp: Long, result: CaptureResult) {
            if (finished) return
            results[timestamp] = result
            updateCaptureProgress()
            maybeFinish()
        }

        @Synchronized
        fun isFinished(): Boolean = finished

        @Synchronized
        fun fail(message: String) {
            if (finished) return
            finished = true
            images.values.forEach { runCatching { it.file.delete() } }
            images.clear()
            results.clear()
            onFailure(message)
        }

        @Synchronized
        private fun maybeFinish() {
            if (finished || images.size < expected || results.size < expected) return
            val paired = images.entries.mapNotNull { (timestamp, spool) ->
                val result = results[timestamp] ?: return@mapNotNull null
                CapturedRawFrame(
                    spool = spool,
                    result = result,
                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                )
            }
            if (paired.size < expected) {
                fail("RAW image/result timestamp pairing failed")
                return
            }
            finished = true
            onComplete(paired.sortedBy { it.spool.timestampNs })
        }

        private fun updateCaptureProgress() {
            val pairedCount = images.keys.count(results::containsKey)
            _state.update { it.copy(progressText = "Capturing RAW · $pairedCount/$expected") }
        }
    }

    private data class ExposureLock(
        val exposureTimeNs: Long,
        val sensitivityIso: Int,
    )

    private companion object {
        const val TAG = "PhotonCameraEngine"
        const val RAW_READER_MAX_IMAGES = 4
        const val HANDHELD_MAX_EXPOSURE_NS = 33_333_333L
        const val BASE_BURST_TIMEOUT_MS = 5_000L
        const val MAX_BURST_TIMEOUT_MS = 14_000L
    }
}
