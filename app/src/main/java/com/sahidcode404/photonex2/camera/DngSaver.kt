package com.sahidcode404.photonex2.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DngSaver(private val context: Context) {
    fun save(
        mergedRaw: File,
        size: Size,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        orientationDegrees: Int,
    ): Uri {
        val name = "Camera_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.dng"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(name, mergedRaw, size, characteristics, captureResult, orientationDegrees)
        } else {
            saveLegacy(name, mergedRaw, size, characteristics, captureResult, orientationDegrees)
        }
    }

    private fun saveScoped(
        name: String,
        raw: File,
        size: Size,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientationDegrees: Int,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, DNG_MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create DNG MediaStore item")
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                writeDng(output, raw, size, characteristics, result, orientationDegrees)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        name: String,
        raw: File,
        size: Size,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientationDegrees: Int,
    ): Uri {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera",
        ).apply { mkdirs() }
        val target = File(directory, name)
        FileOutputStream(target).use { output ->
            writeDng(output, raw, size, characteristics, result, orientationDegrees)
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(DNG_MIME), null)
        return Uri.fromFile(target)
    }

    private fun writeDng(
        output: java.io.OutputStream,
        raw: File,
        size: Size,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientationDegrees: Int,
    ) {
        val creator = DngCreator(characteristics, result)
        try {
            creator.setDescription("PhotonEx2 computational RAW · merged Bayer data")
            creator.setOrientation(exifOrientation(orientationDegrees))
            FileInputStream(raw).channel.use { channel ->
                val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                creator.writeByteBuffer(output, size, mapped, 0L)
            }
        } finally {
            creator.close()
        }
    }

    private fun exifOrientation(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    private companion object {
        const val DNG_MIME = "image/x-adobe-dng"
    }
}
