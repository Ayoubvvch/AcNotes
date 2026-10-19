package com.acmods.acnotes.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

// Uses dynamic R2Config from R2Config.kt (loaded securely from private preferences)


class R2SyncEngine {

    companion object {
        private const val TAG = "R2SyncEngine"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun encodeKeyForUrl(key: String): String {
        return key.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    suspend fun sync(dbHelper: NotesDbHelper): Boolean = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured) return@withContext false
        try {
            val localNotes = dbHelper.getAllNotes()
            val deletedIds = dbHelper.getDeletedIds()

            // Step 1: List all remote objects in .Vault/
            val remoteObjects = listVaultObjects() ?: return@withContext false
            val mdObjects = remoteObjects.filter { it.key.endsWith(".md") }

            Log.i(TAG, "Found ${mdObjects.size} markdown files on Cloudflare R2")

            // Index local notes by key
            val localByKey = mutableMapOf<String, Note>()
            for (note in localNotes) {
                val key = note.syncKey ?: getNoteKey(note)
                localByKey[key] = note
            }

            // Step 2: Download ONLY files that are missing locally or newer on remote
            val toDownload = mdObjects.filter { remote ->
                val local = localByKey[remote.key]
                if (local == null) {
                    true // New note from another device
                } else {
                    // Only download if remote is strictly newer than local edit
                    remote.lastModifiedMs > (local.updatedAt + 2000L)
                }
            }

            Log.i(TAG, "Incremental sync: ${toDownload.size} files need downloading out of ${mdObjects.size}")

            val semaphore = Semaphore(8)
            val newlyDownloadedNotes = coroutineScope {
                toDownload.map { obj ->
                    async {
                        semaphore.withPermit {
                            try {
                                val content = getObject(obj.key)
                                if (content != null) {
                                    val parsed = parseMdFile(obj.key, content)
                                    if (parsed != null && !deletedIds.contains(parsed.id)) {
                                        return@withPermit parsed
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed downloading note ${obj.key}: ${e.message}")
                            }
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            // Build complete remote map: fresh downloads + unchanged existing local notes
            val remoteKeySet = mdObjects.map { it.key }.toSet()
            val remoteMap = mutableMapOf<Long, Note>()
            for (dn in newlyDownloadedNotes) {
                remoteMap[dn.id] = dn
            }
            for (obj in mdObjects) {
                val local = localByKey[obj.key]
                if (local != null && !remoteMap.containsKey(local.id)) {
                    remoteMap[local.id] = local
                }
            }

            // Step 3: Two-way merge with deletion synchronization
            val mergedMap = mutableMapOf<Long, Note>()
            val notesToDeleteLocally = mutableListOf<Long>()

            for (local in localNotes) {
                if (deletedIds.contains(local.id)) {
                    val remKey = local.syncKey ?: getNoteKey(local)
                    deleteKeyRemote(remKey)
                    continue
                }

                val rem = remoteMap[local.id]
                if (rem != null) {
                    if (rem.updatedAt > local.updatedAt) {
                        mergedMap[local.id] = rem
                    } else {
                        mergedMap[local.id] = local
                    }
                } else {
                    val key = local.syncKey ?: getNoteKey(local)
                    if (local.syncKey != null && !remoteKeySet.contains(key)) {
                        // Previously synced to R2, but no longer on R2 -> deleted remotely!
                        notesToDeleteLocally.add(local.id)
                    } else {
                        // Brand new local note, keep it to upload
                        mergedMap[local.id] = local
                    }
                }
            }

            for (remote in remoteMap.values) {
                if (!mergedMap.containsKey(remote.id) && !deletedIds.contains(remote.id)) {
                    mergedMap[remote.id] = remote
                }
            }

            for (delId in notesToDeleteLocally) {
                dbHelper.deleteNote(delId)
            }

            // Save all merged notes to local SQLite
            val finalNotes = mergedMap.values.toList()
            dbHelper.saveAll(finalNotes)

            // Extract and register all folders
            for (note in finalNotes) {
                if (note.folder.isNotBlank()) {
                    dbHelper.addFolder(note.folder.trim())
                }
            }

            // Step 4: Upload notes that are newer locally, or renamed/moved, or new
            coroutineScope {
                finalNotes.map { note ->
                    async {
                        val rem = remoteMap[note.id]
                        val targetKey = getNoteKey(note, finalNotes)
                        val isNewer = rem == null || note.updatedAt > rem.updatedAt
                        val oldKey = note.syncKey
                        val keyChanged = oldKey != null && oldKey != targetKey

                        oldKey?.let { k ->
                            if (k != targetKey) {
                                semaphore.withPermit {
                                    deleteKeyRemote(k)
                                }
                            }
                        }

                        if (isNewer || keyChanged) {
                            semaphore.withPermit {
                                val updatedNote = note.copy(syncKey = targetKey)
                                if (putNote(updatedNote)) {
                                    dbHelper.saveNote(updatedNote)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            // Step 5: Sync avatar from R2 if not present locally
            try {
                val localAvatar = UserProfileManager.getLocalAvatarFile(dbHelper.context)
                if (!localAvatar.exists() || localAvatar.length() == 0L) {
                    downloadAvatar(localAvatar)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Avatar sync error: ${e.message}")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            false
        }
    }

    suspend fun putNote(note: Note): Boolean = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured) return@withContext false
        try {
            val key = getNoteKey(note)
            val encodedKey = encodeKeyForUrl(key)
            val content = serializeNoteToMd(note)
            val date = getAmzDate()
            val shortDate = getShortDate()

            val url = "${R2Config.baseUrl}/$encodedKey"
            val body = content.toByteArray(Charsets.UTF_8)
            val payloadHash = sha256Hex(body)

            val canonicalHeaders = "host:${R2Config.endpointHost}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "PUT\n/${R2Config.bucket}/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url(url)
                .put(body.toRequestBody("text/markdown; charset=utf-8".toMediaType()))
                .header("Host", R2Config.endpointHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "putNote error: ${e.message}")
            false
        }
    }

    suspend fun deleteKeyRemote(key: String): Boolean = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured) return@withContext false
        try {
            val encodedKey = encodeKeyForUrl(key)
            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:${R2Config.endpointHost}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "DELETE\n/${R2Config.bucket}/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("${R2Config.baseUrl}/$encodedKey")
                .delete()
                .header("Host", R2Config.endpointHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteNoteRemote(note: Note): Boolean = withContext(Dispatchers.IO) {
        val key = note.syncKey ?: getNoteKey(note)
        deleteKeyRemote(key)
    }

    data class RemoteObjectMeta(val key: String, val lastModifiedMs: Long, val etag: String)

    private data class ListVaultResult(val items: List<RemoteObjectMeta>, val isTruncated: Boolean, val nextMarker: String?)

    private fun fetchVaultPage(query: String): ListVaultResult? {
        return try {
            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:${R2Config.endpointHost}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/${R2Config.bucket}\n$query\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("${R2Config.baseUrl}?$query")
                .get()
                .header("Host", R2Config.endpointHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                parseListResultFromXml(xml)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchVaultPage error: ${e.message}")
            null
        }
    }

    private fun listObjectsWithPrefix(prefixEncoded: String): List<RemoteObjectMeta> {
        val allItems = mutableListOf<RemoteObjectMeta>()
        var marker: String? = null
        var iterations = 0
        val maxIterations = 50 // Safeguard up to 50,000 files

        do {
            val query = if (marker.isNullOrBlank()) {
                "prefix=$prefixEncoded"
            } else {
                "marker=" + URLEncoder.encode(marker, "UTF-8").replace("+", "%20") + "&prefix=$prefixEncoded"
            }
            val result = fetchVaultPage(query) ?: break
            allItems.addAll(result.items)

            marker = if (result.isTruncated && result.items.isNotEmpty()) {
                result.nextMarker ?: result.items.lastOrNull()?.key
            } else {
                null
            }
            iterations++
        } while (marker != null && iterations < maxIterations)

        return allItems
    }

    private fun listVaultObjects(): List<RemoteObjectMeta>? {
        if (!R2Config.isConfigured) return null
        return listObjectsWithPrefix(".Vault%2F")
    }

    private fun getObject(key: String): String? {
        if (!R2Config.isConfigured) return null
        return try {
            val encodedKey = encodeKeyForUrl(key)
            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:${R2Config.endpointHost}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/${R2Config.bucket}/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("${R2Config.baseUrl}/$encodedKey")
                .get()
                .header("Host", R2Config.endpointHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "getObject failed for $key: ${e.message}")
            null
        }
    }

    suspend fun putObject(key: String, content: String, contentType: String = "text/plain"): Boolean = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured) return@withContext false
        try {
            val encodedKey = encodeKeyForUrl(key)
            val date = getAmzDate()
            val shortDate = getShortDate()
            val body = content.toByteArray(Charsets.UTF_8)
            val payloadHash = sha256Hex(body)

            val canonicalHeaders = "host:${R2Config.endpointHost}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "PUT\n/${R2Config.bucket}/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("${R2Config.baseUrl}/$encodedKey")
                .put(body.toRequestBody(contentType.toMediaType()))
                .header("Host", R2Config.endpointHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "putObject failed for $key: ${e.message}")
            false
        }
    }

    suspend fun fetchRemoteProfile(): UserProfile? = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured) return@withContext null
        try {
            val raw = getObject(".Vault/.profile.json") ?: return@withContext null
            val json = org.json.JSONObject(raw)
            UserProfile(
                name = json.optString("name", ""),
                email = json.optString("email", ""),
                avatarEmoji = json.optString("avatarEmoji", "👤")
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveRemoteProfile(profile: UserProfile): Boolean = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured) return@withContext false
        try {
            val json = org.json.JSONObject().apply {
                put("name", profile.name)
                put("email", profile.email)
                put("avatarEmoji", profile.avatarEmoji)
                put("updatedAt", System.currentTimeMillis())
            }
            putObject(".Vault/.profile.json", json.toString(), "application/json")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun testConnection(host: String, bucket: String, accessKey: String, secretKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val cleanHost = R2Config.normalizeEndpoint(host)
            val cleanBucket = bucket.trim()
            val cleanAk = accessKey.trim()
            val cleanSk = secretKey.trim()

            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:$cleanHost\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/$cleanBucket\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val signingKey = getSignatureKey(cleanSk, shortDate, R2Config.region, R2Config.service)
            val stringToSign = "AWS4-HMAC-SHA256\n$date\n$shortDate/${R2Config.region}/${R2Config.service}/aws4_request\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
            val signature = hmacSha256Hex(signingKey, stringToSign)
            val auth = "AWS4-HMAC-SHA256 Credential=$cleanAk/$shortDate/${R2Config.region}/${R2Config.service}/aws4_request, SignedHeaders=$signedHeaders, Signature=$signature"

            val request = Request.Builder()
                .url("https://$cleanHost/$cleanBucket")
                .get()
                .header("Host", cleanHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Pair(true, "Connected successfully")
                } else {
                    Pair(false, "HTTP ${resp.code}: ${resp.message}")
                }
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Connection failed")
        }
    }

    suspend fun listBuckets(host: String, accessKey: String, secretKey: String): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        try {
            val cleanHost = R2Config.normalizeEndpoint(host)
            val cleanAk = accessKey.trim()
            val cleanSk = secretKey.trim()

            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:$cleanHost\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val signingKey = getSignatureKey(cleanSk, shortDate, R2Config.region, R2Config.service)
            val stringToSign = "AWS4-HMAC-SHA256\n$date\n$shortDate/${R2Config.region}/${R2Config.service}/aws4_request\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
            val signature = hmacSha256Hex(signingKey, stringToSign)
            val auth = "AWS4-HMAC-SHA256 Credential=$cleanAk/$shortDate/${R2Config.region}/${R2Config.service}/aws4_request, SignedHeaders=$signedHeaders, Signature=$signature"

            val request = Request.Builder()
                .url("https://$cleanHost/")
                .get()
                .header("Host", cleanHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Pair(false, emptyList<String>())
                }
                val xml = resp.body?.string() ?: return@withContext Pair(true, emptyList<String>())
                val buckets = parseBucketsFromXml(xml)
                Pair(true, buckets)
            }
        } catch (e: Exception) {
            Log.e(TAG, "listBuckets error: ${e.message}", e)
            Pair(false, emptyList<String>())
        }
    }

    private fun parseBucketsFromXml(xml: String): List<String> {
        val buckets = mutableListOf<String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val bucketNodes = doc.getElementsByTagName("Bucket")
            for (i in 0 until bucketNodes.length) {
                val node = bucketNodes.item(i)
                if (node is org.w3c.dom.Element) {
                    val nameNodes = node.getElementsByTagName("Name")
                    if (nameNodes.length > 0) {
                        val name = nameNodes.item(0).textContent.trim()
                        if (name.isNotEmpty()) {
                            buckets.add(name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseBucketsFromXml error: ${e.message}")
        }
        return buckets
    }

    suspend fun createBucket(host: String, bucketName: String, accessKey: String, secretKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val cleanHost = R2Config.normalizeEndpoint(host)
            val cleanBucket = bucketName.trim().lowercase()
            val cleanAk = accessKey.trim()
            val cleanSk = secretKey.trim()

            if (cleanBucket.isBlank()) return@withContext Pair(false, "Bucket name cannot be empty")

            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:$cleanHost\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "PUT\n/$cleanBucket\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val signingKey = getSignatureKey(cleanSk, shortDate, R2Config.region, R2Config.service)
            val stringToSign = "AWS4-HMAC-SHA256\n$date\n$shortDate/${R2Config.region}/${R2Config.service}/aws4_request\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
            val signature = hmacSha256Hex(signingKey, stringToSign)
            val auth = "AWS4-HMAC-SHA256 Credential=$cleanAk/$shortDate/${R2Config.region}/${R2Config.service}/aws4_request, SignedHeaders=$signedHeaders, Signature=$signature"

            val request = Request.Builder()
                .url("https://$cleanHost/$cleanBucket")
                .put(ByteArray(0).toRequestBody(null))
                .header("Host", cleanHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Pair(true, "Bucket created successfully")
                } else {
                    val err = resp.body?.string() ?: ""
                    Pair(false, "HTTP ${resp.code}: $err")
                }
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Failed to create bucket")
        }
    }

    suspend fun uploadAvatar(file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured || !file.exists()) return@withContext false
        try {
            val key = ".Vault/.profile_avatar.jpg"
            val encodedKey = encodeKeyForUrl(key)
            val date = getAmzDate()
            val shortDate = getShortDate()
            val body = file.readBytes()
            val payloadHash = sha256Hex(body)

            val canonicalHeaders = "host:${R2Config.endpointHost}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "PUT\n/${R2Config.bucket}/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("${R2Config.baseUrl}/$encodedKey")
                .put(body.toRequestBody("image/jpeg".toMediaType()))
                .header("Host", R2Config.endpointHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadAvatar error: ${e.message}")
            false
        }
    }

    private fun downloadFileByKey(key: String, destFile: java.io.File): Boolean {
        return try {
            val encodedKey = encodeKeyForUrl(key)
            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:${R2Config.endpointHost}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/${R2Config.bucket}/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("${R2Config.baseUrl}/$encodedKey")
                .get()
                .header("Host", R2Config.endpointHost)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        destFile.outputStream().use { it.write(bytes) }
                        true
                    } else false
                } else false
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadAvatar error for $key: ${e.message}")
            false
        }
    }

    suspend fun downloadAvatar(destFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        if (!R2Config.isConfigured) return@withContext false
        downloadFileByKey(".Vault/.profile_avatar.jpg", destFile)
    }

    private fun parseListResultFromXml(xml: String): ListVaultResult {
        val items = mutableListOf<RemoteObjectMeta>()
        var isTruncated = false
        var nextMarker: String? = null
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

            val truncNodes = doc.getElementsByTagName("IsTruncated")
            if (truncNodes.length > 0) {
                isTruncated = truncNodes.item(0).textContent.trim().equals("true", ignoreCase = true)
            }

            val markerNodes = doc.getElementsByTagName("NextMarker")
            if (markerNodes.length > 0) {
                nextMarker = markerNodes.item(0).textContent.trim()
            }

            val contentsNodes = doc.getElementsByTagName("Contents")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            for (i in 0 until contentsNodes.length) {
                val node = contentsNodes.item(i)
                if (node is org.w3c.dom.Element) {
                    val key = node.getElementsByTagName("Key").item(0)?.textContent?.trim() ?: ""
                    val lastModStr = node.getElementsByTagName("LastModified").item(0)?.textContent?.trim() ?: ""
                    val etag = node.getElementsByTagName("ETag").item(0)?.textContent?.trim()?.removeSurrounding("\"") ?: ""

                    var lastModifiedMs = 0L
                    if (lastModStr.isNotBlank()) {
                        try {
                            val cleanStr = lastModStr.substringBefore(".")
                            lastModifiedMs = sdf.parse(cleanStr)?.time ?: 0L
                        } catch (e: Exception) {
                            lastModifiedMs = 0L
                        }
                    }

                    if (key.isNotBlank()) {
                        items.add(RemoteObjectMeta(key, lastModifiedMs, etag))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "XML parse error: ${e.message}")
        }
        return ListVaultResult(items, isTruncated, nextMarker)
    }

    fun getNoteKey(note: Note, allNotes: List<Note> = emptyList()): String {
        val targetFolder = if (note.folder.isNotBlank() && note.folder != "Home" && note.folder != "uncategorized") {
            note.folder.trim()
        } else {
            ""
        }
        val folderPath = if (targetFolder.isNotEmpty()) ".Vault/$targetFolder" else ".Vault"
        var baseName = (if (note.title.isNotBlank()) note.title else "Untitled")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

        // Reuse existing syncKey only if it already belongs to .Vault and folder & title haven't changed
        note.syncKey?.let { currentKey ->
            if (currentKey.startsWith(".Vault/")) {
                val parts = currentKey.split("/").filter { it.isNotBlank() }
                val curFolder = if (parts.size > 2) parts.subList(1, parts.size - 1).joinToString("/") else ""
                val curBase = parts.lastOrNull()?.removeSuffix(".md") ?: ""
                if (curFolder == targetFolder && (curBase == baseName || curBase.startsWith("${baseName}_"))) {
                    return currentKey
                }
            }
        }

        // Prevent title collisions: if another note in the same folder shares the exact same title, add a unique short ID suffix
        if (allNotes.isNotEmpty()) {
            val hasCollision = allNotes.any { other ->
                other.id != note.id &&
                other.folder.trim() == targetFolder &&
                (if (other.title.isNotBlank()) other.title else "Untitled").trim().equals(if (note.title.isNotBlank()) note.title else "Untitled", ignoreCase = true)
            }
            if (hasCollision) {
                val shortId = (note.id.toString()).takeLast(6)
                baseName += "_$shortId"
            }
        }

        return "$folderPath/$baseName.md"
    }

    private fun serializeNoteToMd(note: Note): String {
        val sanitizeStr = { s: String -> s.replace("\n", " ").replace("\"", "\\\"") }
        val isWhiteboard = note.text.contains("\"type\":\"excalidraw\"") || note.text.contains("\"type\": \"excalidraw\"")
        val fm = """
            ---
            id: ${note.id}
            title: "${sanitizeStr(if (note.title.isNotBlank()) note.title else "Untitled")}"
            folder: "${sanitizeStr(note.folder)}"
            date: "${sanitizeStr(note.date)}"
            updatedAt: ${if (note.updatedAt > 0) note.updatedAt else note.id}
            pinned: ${note.isPinned}
            isWhiteboard: $isWhiteboard
            ---

        """.trimIndent()
        return fm + note.text
    }

    private fun parseMdFile(key: String, raw: String): Note? {
        try {
            val parts = key.split("/").filter { it.isNotBlank() }
            val fileFolder = if (parts.size > 2) parts.subList(1, parts.size - 1).joinToString("/") else ""
            val fileTitle = parts.lastOrNull()?.removeSuffix(".md") ?: "Untitled"
            var folder = fileFolder
            var title = fileTitle

            val keyHash = key + title
            var hash = 0L
            for (ch in keyHash) {
                hash = ((hash shl 5) - hash) + ch.code.toLong()
            }
            var id = kotlin.math.abs(hash) % 900000000000L + 1000000000000L
            var date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
            var updatedAt = id
            var pinned = false
            var text = raw

            // Safe frontmatter extraction: only parse if bounded by --- and contains standard metadata fields
            val fmRegex = Regex("^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n?")
            val fmMatch = fmRegex.find(text)
            if (fmMatch != null && Regex("(?m)^(?:id|title|folder|date|updatedAt|pinned|isWhiteboard):").containsMatchIn(fmMatch.groupValues[1])) {
                val fmText = fmMatch.groupValues[1]
                text = text.substring(fmMatch.range.last + 1).trim()
                fmText.lines().forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx != -1) {
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                        when (k) {
                            "id" -> id = v.toLongOrNull() ?: id
                            "title" -> {
                                // Respect external rename from Drive / Cloudflare:
                                // If the file was renamed in Drive, fileTitle is the new authoritative title.
                                // Only fall back to frontmatter title if the file was untitled.
                                if (fileTitle == "Untitled" || fileTitle == "Untitled Document" || fileTitle.isBlank()) {
                                    if (v.isNotBlank()) title = v
                                }
                            }
                            "folder" -> {
                                if (fileFolder.isBlank() && v.isNotBlank()) folder = v
                            }
                            "date" -> if (v.isNotBlank()) date = v
                            "updatedAt" -> updatedAt = v.toLongOrNull() ?: updatedAt
                            "pinned" -> pinned = v.toBoolean()
                        }
                    }
                }
            }

            return Note(
                id = id,
                title = title,
                text = text,
                preview = extractPreview(text),
                folder = folder,
                date = date,
                updatedAt = updatedAt,
                isPinned = pinned,
                syncKey = key
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun buildAuthHeader(canonicalRequest: String, shortDate: String, amzDate: String, signedHeaders: String): String {
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$shortDate/${R2Config.region}/${R2Config.service}/aws4_request\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val signingKey = getSignatureKey(R2Config.secretKey, shortDate, R2Config.region, R2Config.service)
        val signature = hmacSha256Hex(signingKey, stringToSign)
        return "AWS4-HMAC-SHA256 Credential=${R2Config.accessKey}/$shortDate/${R2Config.region}/${R2Config.service}/aws4_request, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kSecret = ("AWS4$key").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun getAmzDate(): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun getShortDate(): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
