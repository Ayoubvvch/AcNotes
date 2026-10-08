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

class R2SyncEngine {

    companion object {
        private const val TAG = "R2SyncEngine"
        private const val BUCKET = "my-vault"
        private const val ENDPOINT_HOST = "5d0d1d468b161e84470798a691e7b5b9.r2.cloudflarestorage.com"
        private const val BASE_URL = "https://$ENDPOINT_HOST/$BUCKET"
        private const val ACCESS_KEY = "d1fc280a9699258034101a4df1da8d02"
        private const val SECRET_KEY = "7869df9a17d82189529af41b026f01038bbf838e422d4d282c2c9aa8f13d08f1"
        private const val REGION = "auto"
        private const val SERVICE = "s3"
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

            // Step 3: Timestamp-based merge with local database
            val mergedMap = mutableMapOf<Long, Note>()
            for (local in localNotes) {
                if (!deletedIds.contains(local.id)) {
                    mergedMap[local.id] = local
                }
            }
            for (remote in downloadedNotes) {
                val existing = mergedMap[remote.id]
                if (existing == null) {
                    mergedMap[remote.id] = remote
                } else {
                    if (remote.updatedAt > existing.updatedAt) {
                        mergedMap[remote.id] = remote
                    }
                }
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

            // Step 4: Upload notes that are newer locally or missing remotely
            val remoteMap = downloadedNotes.associateBy { it.id }
            coroutineScope {
                finalNotes.map { note ->
                    async {
                        val rem = remoteMap[note.id]
                        if (rem == null || note.updatedAt > rem.updatedAt) {
                            semaphore.withPermit {
                                putNote(note)
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
        try {
            val key = getNoteKey(note)
            val encodedKey = encodeKeyForUrl(key)
            val content = serializeNoteToMd(note)
            val date = getAmzDate()
            val shortDate = getShortDate()

            val url = "$BASE_URL/$encodedKey"
            val body = content.toByteArray(Charsets.UTF_8)
            val payloadHash = sha256Hex(body)

            val canonicalHeaders = "host:$ENDPOINT_HOST\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "PUT\n/$BUCKET/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url(url)
                .put(body.toRequestBody("text/markdown; charset=utf-8".toMediaType()))
                .header("Host", ENDPOINT_HOST)
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

    suspend fun deleteNoteRemote(note: Note): Boolean = withContext(Dispatchers.IO) {
        try {
            val key = getNoteKey(note)
            val encodedKey = encodeKeyForUrl(key)
            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:$ENDPOINT_HOST\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "DELETE\n/$BUCKET/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("$BASE_URL/$encodedKey")
                .delete()
                .header("Host", ENDPOINT_HOST)
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

    private fun listVaultKeys(): List<String>? {
        return try {
            val query = "prefix=.Vault%2F"
            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:$ENDPOINT_HOST\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/$BUCKET\n$query\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("$BASE_URL?$query")
                .get()
                .header("Host", ENDPOINT_HOST)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", auth)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                parseKeysFromXml(xml)
            }
        } catch (e: Exception) {
            Log.w(TAG, "listVaultKeys error: ${e.message}")
            null
        }
    }

    private fun getObject(key: String): String? {
        return try {
            val encodedKey = encodeKeyForUrl(key)
            val date = getAmzDate()
            val shortDate = getShortDate()
            val payloadHash = sha256Hex(ByteArray(0))

            val canonicalHeaders = "host:$ENDPOINT_HOST\nx-amz-content-sha256:$payloadHash\nx-amz-date:$date\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/$BUCKET/$encodedKey\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val auth = buildAuthHeader(canonicalRequest, shortDate, date, signedHeaders)

            val request = Request.Builder()
                .url("$BASE_URL/$encodedKey")
                .get()
                .header("Host", ENDPOINT_HOST)
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

    private fun parseKeysFromXml(xml: String): List<String> {
        val keys = mutableListOf<String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val keyNodes = doc.getElementsByTagName("Key")
            for (i in 0 until keyNodes.length) {
                keys.add(keyNodes.item(i).textContent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "XML parse error: ${e.message}")
        }
        return keys
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
            var id = System.currentTimeMillis()
            var title = parts.lastOrNull()?.removeSuffix(".md") ?: "Untitled"
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
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$shortDate/$REGION/$SERVICE/aws4_request\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val signingKey = getSignatureKey(SECRET_KEY, shortDate, REGION, SERVICE)
        val signature = hmacSha256Hex(signingKey, stringToSign)
        return "AWS4-HMAC-SHA256 Credential=$ACCESS_KEY/$shortDate/$REGION/$SERVICE/aws4_request, SignedHeaders=$signedHeaders, Signature=$signature"
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
