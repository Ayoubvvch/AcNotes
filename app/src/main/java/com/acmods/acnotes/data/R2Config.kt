package com.acmods.acnotes.data

import android.content.Context

object R2Config {
    private const val PREFS_NAME = "acnotes_r2_prefs"
    private const val KEY_ENDPOINT = "endpointHost"
    private const val KEY_BUCKET = "bucket"
    private const val KEY_ACCESS_KEY = "accessKey"
    private const val KEY_SECRET_KEY = "secretKey"
    private const val KEY_SYNC_MODE = "sync_mode" // "CLOUD" or "OFFLINE"

    var endpointHost: String = ""
    var bucket: String = ""
    var accessKey: String = ""
    var secretKey: String = ""
    var syncMode: String = "OFFLINE"
    const val region: String = "auto"
    const val service: String = "s3"

    val baseUrl: String get() = "https://$endpointHost/$bucket"

    val isCloudConnected: Boolean
        get() = syncMode == "CLOUD" && accessKey.isNotBlank() && secretKey.isNotBlank() && endpointHost.isNotBlank() && bucket.isNotBlank()

    val isConfigured: Boolean get() = isCloudConnected

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        endpointHost = prefs.getString(KEY_ENDPOINT, "") ?: ""
        bucket = prefs.getString(KEY_BUCKET, "") ?: ""
        accessKey = prefs.getString(KEY_ACCESS_KEY, "") ?: ""
        secretKey = prefs.getString(KEY_SECRET_KEY, "") ?: ""
        syncMode = prefs.getString(KEY_SYNC_MODE, if (accessKey.isNotBlank()) "CLOUD" else "OFFLINE") ?: "OFFLINE"
    }

    fun isInitialSetupDone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_SYNC_MODE)
    }

    fun normalizeEndpoint(input: String): String {
        var clean = input.trim().removePrefix("https://").removePrefix("http://").removeSuffix("/")
        // If user enters raw 32-character Cloudflare account ID, append .r2.cloudflarestorage.com
        if (clean.matches(Regex("^[a-fA-F0-9]{32}$"))) {
            clean = "$clean.r2.cloudflarestorage.com"
        }
        return clean
    }

    fun saveCloud(context: Context, host: String, b: String, ak: String, sk: String) {
        endpointHost = normalizeEndpoint(host)
        bucket = b.trim()
        accessKey = ak.trim()
        secretKey = sk.trim()
        syncMode = "CLOUD"

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENDPOINT, endpointHost)
            .putString(KEY_BUCKET, bucket)
            .putString(KEY_ACCESS_KEY, accessKey)
            .putString(KEY_SECRET_KEY, secretKey)
            .putString(KEY_SYNC_MODE, "CLOUD")
            .apply()
    }

    fun setOfflineMode(context: Context) {
        syncMode = "OFFLINE"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SYNC_MODE, "OFFLINE")
            .apply()
    }

    fun clear(context: Context) {
        endpointHost = ""
        bucket = ""
        accessKey = ""
        secretKey = ""
        syncMode = "OFFLINE"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
