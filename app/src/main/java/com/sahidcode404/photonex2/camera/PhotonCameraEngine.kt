package com.sahidcode404.photonex2.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
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
 * Single owner of Camera2 device/session resources for PhotonEx2.
 *
 * Preview is the only non-RAW stream. Still capture adds one RAW_SENSOR ImageReader. No JPEG, YUV,
 * HEIF, DEPTH or vendor encoded still surface is ever configured by this class.
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
    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var activeRoute: LensRoute? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    @Volatile private var latestPreviewResult: TotalCaptureResult? = null
    @Volatile private var burstCollector: BurstCollector? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val routes = withContext(Dispatchers.Default) { discovery.discoverRawRoutes() }
            if (routes.isEmpty()) {
                _state.update { it.copy(error = "No app-accessible RAW camera was found") }
                return@launch
            }
            val selected = routes.firstOrNull { it.facing == LensFacing.BACK } ?: routes.first()
            _state.update {
                it.copy(lenses = routes, selectedLensKey = selected.key, error = null)
            }
            openSelectedWhenReady()
        }
    }

    fun pauseCamera() {
        if (!started) return
        generation += 1
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
                generation += 1
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
            generation += 1
            closeCameraResources()
        }
    }

    fun selectLens(key: String) {
        val target = _state.value.lenses.firstOrNull { it.key == key } ?: return
        if (target.key == _state.value.selectedLensKey || _state.value.capturing) return
        _state.update { it.copy(selectedLensKey = key, previewReady = false, error = null) }
        generation += 1
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
        val characteristics = activeCharacteristics ?: return
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
                processBurst(
                    frames,
                    characteristics,
                    route,
                    view.display?.rotation ?: Surface.ROTATION_0,
                    directory,
                )
            },
            onFailure = { message ->
                directory.deleteRecursively()
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
            List(frameCount) {
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }.build()
            }
        }.getOrElse {
            collector.fail("Unable to build RAW burst: ${it.message}")
            return
        }

        runCatching {
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

        cameraHandler.postDelayed({
            if (burstCollector === collector && !collector.isFinished()) {
                collector.fail("RAW burst timed out")
            }
        }, BURST_TIMEOUT_MS)
    }

    private fun processBurst(
        frames: List<CapturedRawFrame>,
        characteristics: CameraCharacteristics,
        route: LensRoute,
        displayRotation: Int,
        directory: File,
    ) {
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    RawBurstMerger.merge(frames, characteristics, directory) { text ->
                        _state.update { it.copy(progressText = text) }
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
                if (started) resumePreview()
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
                        _state.update { it.copy(previewReady = false, error = "Camera disconnected") }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        _state.update { it.copy(previewReady = false, error = "Camera error $error") }
                    }
                },
                cameraHandler,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Open failed", t)
            _state.update {
                it.copy(
                    previewReady = false,
                    error = "Unable to open this RAW lens: ${t.message ?: t.javaClass.simpleName}",
                )
            }
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
                _state.update {
                    it.copy(previewReady = false, error = "This RAW lens rejected the preview + RAW session")
                }
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
            _state.update {
                it.copy(
                    previewReady = false,
                    error = "This RAW lens rejected the preview + RAW session: ${t.message ?: t.javaClass.simpleName}",
                )
            }
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
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }.build()
            session.setRepeatingRequest(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        latestPreviewResult = result
                        if (!_state.value.previewReady) {
                            _state.update { it.copy(previewReady = true, error = null) }
                        }
                    }
                },
                cameraHandler,
            )
            textureView?.let { configureTransform(it, route) }
        } catch (t: Throwable) {
            _state.update { it.copy(previewReady = false, error = "Preview failed: ${t.message}") }
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
        val id = route.physicalCameraId ?: route.openCameraId
        return cameraManager.getCameraCharacteristics(id)
    }

    private fun physicalResultOrTotal(result: TotalCaptureResult, route: LensRoute): CaptureResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && route.physicalCameraId != null) {
            return result.physicalCameraResults[route.physicalCameraId] ?: result
        }
        return result
    }

    private fun configureTransform(view: TextureView, route: LensRoute) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val buffer = route.previewSize
        val swapped = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val bufferRect = if (swapped) {
            RectF(0f, 0f, buffer.height.toFloat(), buffer.width.toFloat())
        } else {
            RectF(0f, 0f, buffer.width.toFloat(), buffer.height.toFloat())
        }
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(
            height.toFloat() / bufferRect.height(),
            width.toFloat() / bufferRect.width(),
        )
        matrix.postScale(scale, scale, centerX, centerY)
        val displayDegrees = rotationToDegrees(rotation)
        val rotationDegrees = if (route.facing == LensFacing.FRONT) {
            (route.sensorOrientationDegrees + displayDegrees) % 360
        } else {
            (route.sensorOrientationDegrees - displayDegrees + 360) % 360
        }
        matrix.postRotate(rotationDegrees.toFloat(), centerX, centerY)
        if (route.facing == LensFacing.FRONT) matrix.postScale(-1f, 1f, centerX, centerY)
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
        generation += 1
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

    private companion object {
        const val TAG = "PhotonCameraEngine"
        const val RAW_READER_MAX_IMAGES = 4
        const val BURST_TIMEOUT_MS = 12_000L
    }
}
