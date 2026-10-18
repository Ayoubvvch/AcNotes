package com.acmods.acnotes.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acmods.acnotes.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

enum class NoteFilter { ALL, PINNED }
enum class SyncState { IDLE, SYNCING, SAVED, OFFLINE }

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

    private val _isSetupDone = MutableStateFlow(false)
    val isSetupDone: StateFlow<Boolean> = _isSetupDone.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    init {
        R2Config.init(application)
        _isSetupDone.value = R2Config.isConfigured
        loadData()
        if (R2Config.isConfigured) {
            syncNow()
            startPeriodicSync()
        }
    }

    fun unlockAsOwner(password: String): Boolean {
        val creds = OwnerVaultKey.unlock(password.trim()) ?: return false
        R2Config.save(
            getApplication(),
            creds.endpointHost,
            creds.bucket,
            creds.accessKey,
            creds.secretKey,
            owner = true
        )
        _isSetupDone.value = true
        syncNow()
        startPeriodicSync()
        return true
    }

    fun setupCustomR2(endpoint: String, bucket: String, accessKey: String, secretKey: String): Boolean {
        val ep = endpoint.trim().removePrefix("https://").removePrefix("http://").removeSuffix("/")
        val b = bucket.trim()
        val ak = accessKey.trim()
        val sk = secretKey.trim()
        if (ep.isBlank() || b.isBlank() || ak.isBlank() || sk.isBlank()) return false
        R2Config.save(getApplication(), ep, b, ak, sk, owner = false)
        _isSetupDone.value = true
        syncNow()
        startPeriodicSync()
        return true
    }

    fun resetVaultConfig() {
        R2Config.clear(getApplication())
        _isSetupDone.value = false
        _syncState.value = SyncState.IDLE
    }

    fun checkForUpdates(currentVersion: String = "3.0.0") {
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
        if (!R2Config.isConfigured) return
        periodicSyncJob?.cancel()
        periodicSyncJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(45_000)
                val success = r2Sync.sync(dbHelper)
                if (success) {
                    loadData()
                    _syncState.value = SyncState.SAVED
                }
            }
        }
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

            val preliminaryNote = Note(
                id = finalId,
                title = derivedTitle,
                text = text.trim(),
                preview = com.acmods.acnotes.data.extractPreview(text.trim()),
                folder = folder.trim(),
                date = existing?.date ?: dateStr,
                updatedAt = now,
                isPinned = isPinned,
                syncKey = oldKey
            )

            val allNotes = dbHelper.getAllNotes()
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
}
