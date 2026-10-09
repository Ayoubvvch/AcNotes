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

object R2Config {
    var endpointHost: String = "5d0d1d468b161e84470798a691e7b5b9.r2.cloudflarestorage.com"
    var bucket: String = "my-vault"
    var accessKey: String = "d1fc280a9699258034101a4df1da8d02"
    var secretKey: String = "7869df9a17d82189529af41b026f01038bbf838e422d4d282c2c9aa8f13d08f1"
    const val region: String = "auto"
    const val service: String = "s3"

    val baseUrl: String get() = "https://$endpointHost/$bucket"
    val isConfigured: Boolean get() = accessKey.isNotBlank() && secretKey.isNotBlank() && endpointHost.isNotBlank()
}

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
        try {
            val localNotes = dbHelper.getAllNotes()
            val deletedIds = dbHelper.getDeletedIds()

            // Step 1: List all remote keys in .Vault/
            val remoteKeys = listVaultKeys() ?: return@withContext false
            val mdKeys = remoteKeys.filter { it.endsWith(".md") }

            Log.i(TAG, "Found ${mdKeys.size} markdown files on Cloudflare R2")

            // Step 2: Download remote files concurrently (up to 8 in parallel)
            val semaphore = Semaphore(8)
            val downloadedNotes = coroutineScope {
                mdKeys.map { key ->
                    async {
                        semaphore.withPermit {
                            try {
                                val content = getObject(key)
                                if (content != null) {
                                    val parsed = parseMdFile(key, content)
                                    if (parsed != null && !deletedIds.contains(parsed.id)) {
                                        return@withPermit parsed
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed downloading note $key: ${e.message}")
                            }
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            Log.i(TAG, "Successfully downloaded ${downloadedNotes.size} notes from R2")

            // Step 3: Two-way merge with deletion synchronization
            val remoteMap = downloadedNotes.associateBy { it.id }
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
                    if (local.syncKey != null) {
                        // Previously synced to R2, but no longer on R2 -> deleted remotely!
                        notesToDeleteLocally.add(local.id)
                    } else {
                        // Brand new local note, keep it to upload
                        mergedMap[local.id] = local
                    }
                }
            }

            for (remote in downloadedNotes) {
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
                        val targetKey = getNoteKey(note)
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

    private data class ListVaultResult(val keys: List<String>, val isTruncated: Boolean, val nextMarker: String?)

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

    private fun listVaultKeys(): List<String>? {
        if (!R2Config.isConfigured) return null
        val allKeys = mutableListOf<String>()
        var marker: String? = null
        var iterations = 0
        val maxIterations = 50 // Safeguard up to 50,000 files

        do {
            val query = if (marker.isNullOrBlank()) {
                "prefix=.Vault%2F"
            } else {
                "marker=" + URLEncoder.encode(marker, "UTF-8").replace("+", "%20") + "&prefix=.Vault%2F"
            }
            val result = fetchVaultPage(query) ?: return if (allKeys.isEmpty()) null else allKeys
            allKeys.addAll(result.keys)

            marker = if (result.isTruncated && result.keys.isNotEmpty()) {
                result.nextMarker ?: result.keys.lastOrNull()
            } else {
                null
            }
            iterations++
        } while (marker != null && iterations < maxIterations)

        return allKeys
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

    private fun parseListResultFromXml(xml: String): ListVaultResult {
        val keys = mutableListOf<String>()
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

            val keyNodes = doc.getElementsByTagName("Key")
            for (i in 0 until keyNodes.length) {
                keys.add(keyNodes.item(i).textContent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "XML parse error: ${e.message}")
        }
        return ListVaultResult(keys, isTruncated, nextMarker)
    }

    private fun getNoteKey(note: Note): String {
        val sanitizeName = (if (note.title.isNotBlank()) note.title else "Untitled")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val fileName = "$sanitizeName.md"
        val folder = note.folder.trim()
        return if (folder.isNotBlank() && folder != "Home" && folder != "uncategorized") {
            ".Vault/$folder/$fileName"
        } else {
            ".Vault/$fileName"
        }
    }

    private fun serializeNoteToMd(note: Note): String {
        val sanitizeStr = { s: String -> s.replace("\n", " ").replace("\"", "\\\"") }
        val fm = """
            ---
            id: ${note.id}
            title: "${sanitizeStr(if (note.title.isNotBlank()) note.title else "Untitled")}"
            folder: "${sanitizeStr(note.folder)}"
            date: "${sanitizeStr(note.date)}"
            updatedAt: ${if (note.updatedAt > 0) note.updatedAt else note.id}
            pinned: ${note.isPinned}
            ---

        """.trimIndent()
        return fm + note.text
    }

    private fun parseMdFile(key: String, raw: String): Note? {
        try {
            val parts = key.split("/").filter { it.isNotBlank() }
            val folder = if (parts.size > 2) parts.subList(1, parts.size - 1).joinToString("/") else ""
            var title = parts.lastOrNull()?.removeSuffix(".md") ?: "Untitled"
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

            if (text.startsWith("---")) {
                val endFm = text.indexOf("---", 3)
                if (endFm != -1) {
                    val fmText = text.substring(3, endFm)
                    text = text.substring(endFm + 3).trim()
                    fmText.lines().forEach { line ->
                        val idx = line.indexOf(':')
                        if (idx != -1) {
                            val k = line.substring(0, idx).trim()
                            val v = line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                            when (k) {
                                "id" -> id = v.toLongOrNull() ?: id
                                "title" -> if (v.isNotBlank()) title = v
                                "date" -> if (v.isNotBlank()) date = v
                                "updatedAt" -> updatedAt = v.toLongOrNull() ?: updatedAt
                                "pinned" -> pinned = v.toBoolean()
                            }
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
