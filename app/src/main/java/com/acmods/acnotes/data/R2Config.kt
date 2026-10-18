package com.acmods.acnotes.data

import android.content.Context

object R2Config {
    private const val PREFS_NAME = "acnotes_r2_prefs"
    private const val KEY_ENDPOINT = "endpointHost"
    private const val KEY_BUCKET = "bucket"
    private const val KEY_ACCESS_KEY = "accessKey"
    private const val KEY_SECRET_KEY = "secretKey"
    private const val KEY_IS_OWNER = "is_owner"

    var endpointHost: String = ""
    var bucket: String = ""
    var accessKey: String = ""
    var secretKey: String = ""
    var isOwner: Boolean = false
    const val region: String = "auto"
    const val service: String = "s3"

    val baseUrl: String get() = "https://$endpointHost/$bucket"
    val isConfigured: Boolean get() = accessKey.isNotBlank() && secretKey.isNotBlank() && endpointHost.isNotBlank() && bucket.isNotBlank()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        endpointHost = prefs.getString(KEY_ENDPOINT, "") ?: ""
        bucket = prefs.getString(KEY_BUCKET, "") ?: ""
        accessKey = prefs.getString(KEY_ACCESS_KEY, "") ?: ""
        secretKey = prefs.getString(KEY_SECRET_KEY, "") ?: ""
        isOwner = prefs.getBoolean(KEY_IS_OWNER, false)
    }

    fun save(context: Context, host: String, b: String, ak: String, sk: String, owner: Boolean) {
        endpointHost = host.trim()
        bucket = b.trim()
        accessKey = ak.trim()
        secretKey = sk.trim()
        isOwner = owner
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENDPOINT, endpointHost)
            .putString(KEY_BUCKET, bucket)
            .putString(KEY_ACCESS_KEY, accessKey)
            .putString(KEY_SECRET_KEY, secretKey)
            .putBoolean(KEY_IS_OWNER, isOwner)
            .apply()
    }

    fun clear(context: Context) {
        endpointHost = ""
        bucket = ""
        accessKey = ""
        secretKey = ""
        isOwner = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
