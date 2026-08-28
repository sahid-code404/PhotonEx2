package com.sahidcode404.photonex2.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sahidcode404.photonex2.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/**
 * Development-OTA client. APK package, version, SHA-256, size and pinned signing certificate are
 * revalidated before Android's visible package installer can be opened.
 */
class DevelopmentUpdateManager(private val activity: Activity) {
    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val gitSha: String,
        val apkSha256: String,
        val apkSizeBytes: Long,
        val signerSha256: String,
        val downloadUrl: String,
    )

    fun check(): UpdateInfo? {
        val connection = open(BuildConfig.DEV_MANIFEST_URL)
        val text = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(text)
        require(json.optInt("schema", -1) == 1) { "Unknown update manifest schema" }
        require(json.getString("channel") == "development") { "Wrong update channel" }
        require(json.getString("applicationId") == BuildConfig.APPLICATION_ID) { "Wrong application ID" }
        require(json.getInt("minSdk") <= Build.VERSION.SDK_INT) { "Update requires newer Android" }
        val code = json.getLong("versionCode")
        if (code <= BuildConfig.VERSION_CODE.toLong()) return null
        val assetName = json.getString("assetName")
        require(assetName == APK_NAME) { "Unexpected APK asset name" }
        val signer = normalizeSha(json.getString("signingCertSha256"))
        require(signer == normalizeSha(BuildConfig.DEV_SIGNER_SHA256)) { "Update signer anchor changed" }
        val sha = normalizeSha(json.getString("sha256"))
        require(sha.length == 64) { "Invalid APK digest" }
        val size = json.getLong("sizeBytes")
        require(size in 1..MAX_APK_BYTES) { "Invalid APK size" }
        val gitSha = json.getString("gitSha")
        require(gitSha.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid source SHA" }
        return UpdateInfo(
            versionCode = code,
            versionName = json.getString("versionName"),
            gitSha = gitSha,
            apkSha256 = sha,
            apkSizeBytes = size,
            signerSha256 = signer,
            downloadUrl = RELEASE_APK_URL,
        )
    }

    fun downloadAndVerify(info: UpdateInfo, onProgress: (Int) -> Unit = {}): File {
        val updates = File(activity.cacheDir, "updates").apply { mkdirs() }
        val partial = File(updates, "$APK_NAME.part")
        val verified = File(updates, APK_NAME)
        partial.delete()
        verified.delete()

        val connection = open(info.downloadUrl)
        try {
            val advertisedLength = connection.contentLengthLong
            if (advertisedLength > MAX_APK_BYTES ||
                (advertisedLength > 0 && advertisedLength != info.apkSizeBytes)
            ) {
                error("Update download size does not match manifest")
            }
            var written = 0L
            BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                FileOutputStream(partial).buffered(128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > MAX_APK_BYTES || written > info.apkSizeBytes) {
                            error("Update exceeded bounded size")
                        }
                        output.write(buffer, 0, count)
                        onProgress(((written * 100L) / info.apkSizeBytes).toInt().coerceIn(0, 100))
                    }
                }
            }
            require(written == info.apkSizeBytes) { "Downloaded APK is incomplete" }
            require(sha256(partial) == info.apkSha256) { "Downloaded APK hash mismatch" }
            inspectPackage(partial, info)
            require(partial.renameTo(verified)) { "Could not promote verified APK" }
            return verified
        } catch (t: Throwable) {
            partial.delete()
            verified.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    fun launchInstaller(apk: File): Boolean {
        require(apk.canonicalFile.parentFile == File(activity.cacheDir, "updates").canonicalFile) {
            "Installer accepts only app-private verified updates"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        return true
    }

    private fun inspectPackage(apk: File, info: UpdateInfo) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Android could not inspect downloaded APK")
        require(archive.packageName == BuildConfig.APPLICATION_ID) { "Downloaded APK package mismatch" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        require(
            archiveVersion == info.versionCode && archiveVersion > BuildConfig.VERSION_CODE.toLong(),
        ) { "Downloaded APK version mismatch" }

        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = archive.signingInfo ?: error("Downloaded APK has no signing information")
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners.toList()
            else signingInfo.signingCertificateHistory.toList()
        } else {
            @Suppress("DEPRECATION")
            archive.signatures?.toList().orEmpty()
        }
        require(certs.isNotEmpty()) { "Downloaded APK has no signing certificate" }
        val signerDigests = certs.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        require(signerDigests.any { normalizeSha(it) == info.signerSha256 }) {
            "Downloaded APK signing certificate mismatch"
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "PhotonEx2/${BuildConfig.VERSION_NAME}")
        connection.connect()
        require(connection.responseCode in 200..299) {
            "Update server returned HTTP ${connection.responseCode}"
        }
        return connection
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalizeSha(value: String): String =
        value.replace(":", "").trim().lowercase(Locale.US)

    private companion object {
        const val APK_NAME = "PhotonEx2-dev.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val MAX_APK_BYTES = 250L * 1024L * 1024L
        const val RELEASE_APK_URL =
            "https://github.com/sahid-code404/PhotonEx2/releases/download/dev-latest/PhotonEx2-dev.apk"
    }
}
