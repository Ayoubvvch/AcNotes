package com.acmods.acnotes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val version: String,
        val releaseName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val sizeBytes: Long
    ) : UpdateCheckResult()

    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val GITHUB_REPO = "Ayoubvvch/AcNotes"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "AcNotes-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Empty response")
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").trim()
                val releaseName = json.optString("name", "New Update")
                val releaseNotes = json.optString("body", "")

                val remoteVersion = tagName.removePrefix("v").trim()
                val cleanCurrent = currentVersion.removePrefix("v").trim()

                // Find APK asset
                val assets = json.optJSONArray("assets")
                var downloadUrl = ""
                var sizeBytes = 0L

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            sizeBytes = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (downloadUrl.isBlank()) {
                    return@withContext UpdateCheckResult.Error("No APK found in release")
                }

                if (isNewerVersion(remoteVersion, cleanCurrent)) {
                    UpdateCheckResult.UpdateAvailable(
                        version = remoteVersion,
                        releaseName = releaseName,
                        releaseNotes = releaseNotes,
                        downloadUrl = downloadUrl,
                        sizeBytes = sizeBytes
                    )
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking update", e)
            UpdateCheckResult.Error(e.message ?: "Failed to check update")
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (progress: Float, downloaded: Long, total: Long) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "AcNotes-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { onError("Download failed: HTTP ${response.code}") }
                    return@withContext
                }

                val body = response.body ?: run {
                    withContext(Dispatchers.Main) { onError("Empty download body") }
                    return@withContext
                }

                val totalBytes = body.contentLength()
                val outputFile = File(context.cacheDir, "AcNotes-latest.apk")
                if (outputFile.exists()) outputFile.delete()

                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var downloaded: Long = 0

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes.toFloat() else 0f
                            withContext(Dispatchers.Main) {
                                onProgress(progress, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    installApk(context, outputFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            withContext(Dispatchers.Main) {
                onError(e.message ?: "Download failed")
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
        }
    }
}
