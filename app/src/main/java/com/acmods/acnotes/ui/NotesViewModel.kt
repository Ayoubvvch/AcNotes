package com.acmods.acnotes.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acmods.acnotes.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class NoteFilter { ALL, PINNED }
enum class SyncState { IDLE, SYNCING, SAVED, OFFLINE }

data class IncomingShareItem(
    val uri: Uri? = null,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val textContent: String? = null
)

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class Available(val update: UpdateCheckResult.UpdateAvailable) : UpdateUiState()
    object UpToDate : UpdateUiState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

sealed class Screen {
    object List : Screen()
    data class View(val noteId: Long) : Screen()
    data class Edit(val noteId: Long?, val initialFolder: String = "") : Screen()
}

class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = NotesDbHelper(application)
    private val r2Sync = R2SyncEngine()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _selectedFolder = MutableStateFlow("")
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(NoteFilter.ALL)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.List)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _backlinks = MutableStateFlow<List<Note>>(emptyList())
    val backlinks: StateFlow<List<Note>> = _backlinks.asStateFlow()

    private val navStack = mutableListOf<Screen>()

    private fun navigateTo(newScreen: Screen) {
        if (_screen.value != newScreen) {
            navStack.add(_screen.value)
            _screen.value = newScreen
        }
    }

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var periodicSyncJob: Job? = null

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _isCloudConnected = MutableStateFlow(false)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    private val _isInitialSetupDone = MutableStateFlow(false)
    val isInitialSetupDone: StateFlow<Boolean> = _isInitialSetupDone.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    init {
        R2Config.init(application)
        _userProfile.value = UserProfileManager.load(application)
        _isCloudConnected.value = R2Config.isCloudConnected
        _isInitialSetupDone.value = R2Config.isInitialSetupDone(application)
        loadData()
        if (R2Config.isCloudConnected) {
            syncNow()
        }
    }

    fun fetchBucketsForAccount(
        endpoint: String,
        accessKey: String,
        secretKey: String,
        onResult: (Boolean, List<String>, String) -> Unit
    ) {
        viewModelScope.launch {
            val (ok, buckets) = r2Sync.listBuckets(endpoint, accessKey, secretKey)
            if (ok) {
                onResult(true, buckets, "")
            } else {
                onResult(false, emptyList(), "Could not retrieve buckets. Please verify Account ID and API keys.")
            }
        }
    }

    fun createAndSelectBucket(
        endpoint: String,
        bucketName: String,
        accessKey: String,
        secretKey: String,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val (ok, msg) = r2Sync.createBucket(endpoint, bucketName, accessKey, secretKey)
            onResult(ok, msg)
        }
    }

    fun setProfileAvatarUri(uri: android.net.Uri) {
        val path = UserProfileManager.saveAvatarFromUri(getApplication(), uri)
        if (path != null) {
            val p = _userProfile.value.copy(avatarImagePath = path)
            _userProfile.value = p
            UserProfileManager.save(getApplication(), p)
            if (R2Config.isCloudConnected) {
                viewModelScope.launch(Dispatchers.IO) {
                    r2Sync.uploadAvatar(java.io.File(path))
                }
            }
        }
    }

    fun connectCloudVault(
        endpoint: String,
        bucket: String,
        accessKey: String,
        secretKey: String,
        name: String = "",
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val (ok, msg) = r2Sync.testConnection(endpoint, bucket, accessKey, secretKey)
            if (ok) {
                R2Config.saveCloud(getApplication(), endpoint, bucket, accessKey, secretKey)
                _isCloudConnected.value = true
                _isInitialSetupDone.value = true

                // Sync profile
                if (name.isNotBlank()) {
                    val p = _userProfile.value.copy(name = name.trim())
                    _userProfile.value = p
                    UserProfileManager.save(getApplication(), p)
                    r2Sync.saveRemoteProfile(p)
                } else {
                    val remoteP = r2Sync.fetchRemoteProfile()
                    if (remoteP != null && remoteP.name.isNotBlank()) {
                        _userProfile.value = remoteP
                        UserProfileManager.save(getApplication(), remoteP)
                    }
                }

                // Sync avatar
                val localAvatar = UserProfileManager.getLocalAvatarFile(getApplication())
                if (localAvatar.exists() && localAvatar.length() > 0) {
                    r2Sync.uploadAvatar(localAvatar)
                } else {
                    if (r2Sync.downloadAvatar(localAvatar)) {
                        _userProfile.value = _userProfile.value.copy(avatarImagePath = localAvatar.absolutePath)
                    }
                }

                syncNow()
                onResult(true, "Connected successfully")
            } else {
                onResult(false, msg)
            }
        }
    }

    fun continueOffline(name: String = "") {
        R2Config.setOfflineMode(getApplication())
        if (name.isNotBlank()) {
            val p = _userProfile.value.copy(name = name.trim())
            _userProfile.value = p
            UserProfileManager.save(getApplication(), p)
        }
        _isCloudConnected.value = false
        _isInitialSetupDone.value = true
        _syncState.value = SyncState.IDLE
        loadData()
    }

    fun updateProfile(name: String, emoji: String) {
        val p = _userProfile.value.copy(name = name.trim(), avatarEmoji = emoji)
        _userProfile.value = p
        UserProfileManager.save(getApplication(), p)
        if (R2Config.isCloudConnected) {
            viewModelScope.launch(Dispatchers.IO) {
                r2Sync.saveRemoteProfile(p)
                val avatarFile = UserProfileManager.getLocalAvatarFile(getApplication())
                if (avatarFile.exists() && avatarFile.length() > 0) {
                    r2Sync.uploadAvatar(avatarFile)
                }
            }
        }
    }

    fun disconnectCloud() {
        R2Config.clear(getApplication())
        _isCloudConnected.value = false
        _isInitialSetupDone.value = false
        _syncState.value = SyncState.IDLE
        periodicSyncJob?.cancel()
    }

    fun checkForUpdates(currentVersion: String = "3.5.0") {
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            val result = AppUpdater.checkForUpdate(currentVersion)
            _updateState.value = when (result) {
                is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(result)
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.Error -> UpdateUiState.Error(result.message)
            }
        }
    }

    fun startDownloadUpdate(context: Context, downloadUrl: String) {
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Downloading(0f, 0L, 0L)
            AppUpdater.downloadAndInstall(
                context = context,
                downloadUrl = downloadUrl,
                onProgress = { progress, downloaded, total ->
                    _updateState.value = UpdateUiState.Downloading(progress, downloaded, total)
                },
                onError = { msg ->
                    _updateState.value = UpdateUiState.Error(msg)
                }
            )
        }
    }

    fun dismissUpdateDialog() {
        _updateState.value = UpdateUiState.Idle
    }

    fun startPeriodicSync() {
        // Disabled: periodic background sync removed per architectural overhaul to preserve battery/bandwidth.
        // Sync is handled on demand (syncNow, saveNote, deleteNote) or on app launch.
        periodicSyncJob?.cancel()
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            val localNotes = dbHelper.getAllNotes()
            val localFolders = dbHelper.getFolders()
            _notes.value = localNotes
            _folders.value = localFolders
        }
    }

    fun loadBacklinksForNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            _backlinks.value = dbHelper.getBacklinks(note.title, note.id)
        }
    }

    fun selectFolder(folder: String) {
        _selectedFolder.value = folder
        _searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(newFilter: NoteFilter) {
        _filter.value = newFilter
    }

    fun openNote(id: Long) {
        navigateTo(Screen.View(id))
    }

    fun openNoteByTitle(targetTitle: String) {
        val cleanTitle = targetTitle.trim()
        if (cleanTitle.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val existing = dbHelper.getNoteByTitle(cleanTitle)
            if (existing != null) {
                navigateTo(Screen.View(existing.id))
            } else {
                val now = System.currentTimeMillis()
                val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(now))
                val preliminaryNote = Note(
                    id = now,
                    title = cleanTitle,
                    text = "# $cleanTitle\n\n",
                    preview = "",
                    folder = "",
                    date = dateStr,
                    updatedAt = now,
                    isPinned = false
                )
                val allNotes = dbHelper.getAllNotes()
                val targetKey = r2Sync.getNoteKey(preliminaryNote, allNotes)
                val newNote = preliminaryNote.copy(syncKey = targetKey)
                dbHelper.saveNote(newNote)
                loadData()
                navigateTo(Screen.View(newNote.id))
                uploadNoteToR2(newNote)
            }
        }
    }

    fun startNewNote() {
        val folder = if (_selectedFolder.value.isNotBlank() && !_selectedFolder.value.equals("home", ignoreCase = true) && _selectedFolder.value != "all") {
            _selectedFolder.value
        } else {
            ""
        }
        navigateTo(Screen.Edit(null, folder))
    }

    fun editCurrentNote() {
        val current = _screen.value
        if (current is Screen.View) {
            val note = _notes.value.find { it.id == current.noteId }
            if (note?.isWhiteboard == true) return
            navigateTo(Screen.Edit(current.noteId))
        }
    }

    fun navigateBack(): Boolean {
        if (navStack.isNotEmpty()) {
            val prev = navStack.removeAt(navStack.size - 1)
            _screen.value = prev
            return true
        }
        val current = _screen.value
        if (current is Screen.List && _selectedFolder.value.isNotEmpty()) {
            _selectedFolder.value = ""
            return true
        }
        return false
    }

    fun saveNote(id: Long?, title: String, text: String, folder: String) {
        if (title.isBlank() && text.isBlank()) {
            navigateBack()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(now))
            val finalId = id ?: now
            val existing = if (id != null) dbHelper.getNote(id) else null
            val oldKey = existing?.syncKey
            val isPinned = existing?.isPinned ?: false

            val derivedTitle = if (title.isNotBlank()) {
                title.trim()
            } else {
                val firstLine = text.trim().lines().firstOrNull { it.isNotBlank() }
                    ?.trimStart('#', ' ', '-', '*', '>')
                    ?.trim() ?: ""
                if (firstLine.isNotBlank()) {
                    if (firstLine.length > 40) firstLine.substring(0, 40) + "…" else firstLine
                } else {
                    "Untitled Note"
                }
            }

            val allNotes = dbHelper.getAllNotes()
            val uniqueTitle = getUniqueTitle(derivedTitle, folder.trim(), allNotes, id)

            val preliminaryNote = Note(
                id = finalId,
                title = uniqueTitle,
                text = text.trim(),
                preview = com.acmods.acnotes.data.extractPreview(text.trim()),
                folder = folder.trim(),
                date = existing?.date ?: dateStr,
                updatedAt = now,
                isPinned = isPinned,
                syncKey = oldKey
            )

            val targetKey = r2Sync.getNoteKey(preliminaryNote, allNotes)
            val note = preliminaryNote.copy(syncKey = targetKey)

            dbHelper.saveNote(note)
            if (folder.isNotBlank()) {
                dbHelper.addFolder(folder.trim())
            }

            loadData()
            _screen.value = Screen.View(finalId)

            // Trigger silent background upload and delete old key on R2 if path/title changed
            uploadNoteToR2(note, oldKey)
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = dbHelper.getNote(id)
            dbHelper.deleteNote(id)
            loadData()
            navStack.removeAll { (it is Screen.View && it.noteId == id) || (it is Screen.Edit && it.noteId == id) }
            _screen.value = Screen.List

            if (note != null) {
                r2Sync.deleteNoteRemote(note)
            }
        }
    }

    fun togglePin(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = dbHelper.getNote(id) ?: return@launch
            val newPin = !note.isPinned
            dbHelper.togglePin(id, newPin)
            loadData()
        }
    }

    fun toggleTaskInNote(noteId: Long, taskIndex: Int, isChecked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = dbHelper.getNote(noteId) ?: return@launch
            val lines = note.text.lines().toMutableList()
            var currentTaskIdx = 0
            var modified = false
            for (i in lines.indices) {
                val line = lines[i]
                if (line.trimStart().matches(Regex("^[-*+]\\s*\\[[ xX]\\].*"))) {
                    if (currentTaskIdx == taskIndex) {
                        lines[i] = if (isChecked) {
                            line.replaceFirst(Regex("\\[ \\]"), "[x]")
                        } else {
                            line.replaceFirst(Regex("\\[[xX]\\]"), "[ ]")
                        }
                        modified = true
                        break
                    }
                    currentTaskIdx++
                }
            }
            if (modified) {
                val updatedText = lines.joinToString("\n")
                val updatedNote = note.copy(
                    text = updatedText,
                    preview = com.acmods.acnotes.data.extractPreview(updatedText),
                    updatedAt = System.currentTimeMillis()
                )
                dbHelper.saveNote(updatedNote)
                loadData()
                uploadNoteToR2(updatedNote, updatedNote.syncKey)
            }
        }
    }

    fun addFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.addFolder(name)
            loadData()
        }
    }

    fun deleteFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.deleteFolder(name)
            if (_selectedFolder.value == name) {
                _selectedFolder.value = ""
            }
            loadData()
            syncNow()
        }
    }

    fun syncNow() {
        if (!R2Config.isCloudConnected) {
            _syncState.value = SyncState.IDLE
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _syncState.value = SyncState.SYNCING
            val success = r2Sync.sync(dbHelper)
            if (success) {
                loadData()
                _syncState.value = SyncState.SAVED
            } else {
                _syncState.value = SyncState.OFFLINE
            }
        }
    }

    private fun uploadNoteToR2(note: Note, oldKey: String? = null) {
        if (!R2Config.isCloudConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            _syncState.value = SyncState.SYNCING
            if (oldKey != null && oldKey != note.syncKey) {
                r2Sync.deleteKeyRemote(oldKey)
            }
            val ok = r2Sync.putNote(note)
            if (ok) {
                dbHelper.saveNote(note)
            }
            _syncState.value = if (ok) SyncState.SAVED else SyncState.OFFLINE
        }
    }

    fun handleIncomingShare(intent: android.content.Intent, context: Context) {
        val action = intent.action ?: return
        if (action != android.content.Intent.ACTION_SEND && action != android.content.Intent.ACTION_SEND_MULTIPLE) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val items = mutableListOf<IncomingShareItem>()
            val cr = context.contentResolver

            if (action == android.content.Intent.ACTION_SEND) {
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                }

                if (streamUri != null) {
                    resolveItemFromUri(streamUri, cr, context)?.let { items.add(it) }
                } else {
                    val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        val subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT)
                        val name = if (!subject.isNullOrBlank()) subject.trim() else {
                            val firstLine = sharedText.trim().lineSequence().firstOrNull()?.trim() ?: "Shared Note"
                            if (firstLine.length > 50) firstLine.take(47) + "…" else firstLine
                        }
                        items.add(
                            IncomingShareItem(
                                uri = null,
                                displayName = name,
                                mimeType = "text/plain",
                                sizeBytes = sharedText.toByteArray(Charsets.UTF_8).size.toLong(),
                                textContent = sharedText
                            )
                        )
                    }
                }
            } else if (action == android.content.Intent.ACTION_SEND_MULTIPLE) {
                val streamUris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                }

                if (!streamUris.isNullOrEmpty()) {
                    for (uri in streamUris) {
                        resolveItemFromUri(uri, cr, context)?.let { items.add(it) }
                    }
                } else {
                    val clipData = intent.clipData
                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            val uri = clipData.getItemAt(i).uri
                            if (uri != null) {
                                resolveItemFromUri(uri, cr, context)?.let { items.add(it) }
                            }
                        }
                    }
                }
            }

            if (items.isNotEmpty()) {
                val today = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
                val newNotes = mutableListOf<Note>()

                items.forEachIndexed { index, item ->
                    val baseTitle = item.displayName
                        .removeSuffix(".md")
                        .removeSuffix(".markdown")
                        .removeSuffix(".txt")
                        .trim()
                    val title = if (baseTitle.isNotBlank()) baseTitle else "Shared Note"
                    val text = item.textContent ?: ""
                    val id = System.currentTimeMillis() + index

                    val note = Note(
                        id = id,
                        title = title,
                        text = text,
                        preview = extractPreview(text),
                        folder = "", // Directly into Home!
                        date = today,
                        updatedAt = id,
                        isPinned = false,
                        syncKey = null
                    )
                    newNotes.add(note)
                }

                dbHelper.saveAll(newNotes)
                loadData()

                // Switch to Home screen
                _screen.value = Screen.List
                _selectedFolder.value = ""

                // Show feedback Toast on main thread
                withContext(Dispatchers.Main) {
                    val msg = if (newNotes.size == 1) {
                        "📥 Saved \"${newNotes[0].title}\" to Home & Syncing..."
                    } else {
                        "📥 Saved ${newNotes.size} notes to Home & Syncing..."
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }

                // Sync directly to .Vault on Cloudflare R2!
                if (R2Config.isCloudConnected) {
                    syncNow()
                }
            }
        }
    }

    private fun resolveItemFromUri(uri: android.net.Uri, cr: ContentResolver, context: Context): IncomingShareItem? {
        return try {
            var displayName = "Shared File"
            var size = 0L
            val mimeType = cr.getType(uri)

            cr.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }

            val isImage = mimeType?.startsWith("image/") == true ||
                displayName.endsWith(".jpg", ignoreCase = true) ||
                displayName.endsWith(".jpeg", ignoreCase = true) ||
                displayName.endsWith(".png", ignoreCase = true) ||
                displayName.endsWith(".webp", ignoreCase = true)

            val textContent = if (isImage) {
                try {
                    val attachDir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
                    val cleanFileName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val targetFile = File(attachDir, "${System.currentTimeMillis()}_$cleanFileName")
                    cr.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (targetFile.exists() && targetFile.length() > 0) {
                        "![${displayName}](file://${targetFile.absolutePath})\n"
                    } else null
                } catch (e: Exception) {
                    null
                }
            } else {
                try {
                    cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            }

            IncomingShareItem(
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = size,
                textContent = textContent
            )
        } catch (e: Exception) {
            Log.w("NotesViewModel", "resolveItemFromUri error: ${e.message}")
            null
        }
    }
}
