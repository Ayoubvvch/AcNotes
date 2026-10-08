package com.acmods.acnotes.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acmods.acnotes.data.Note
import com.acmods.acnotes.data.NotesDbHelper
import com.acmods.acnotes.data.R2SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class NoteFilter { ALL, PINNED }
enum class SyncState { IDLE, SYNCING, SAVED, OFFLINE }

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

    private val _selectedFolder = MutableStateFlow("all")
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(NoteFilter.ALL)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.List)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        loadData()
        syncNow()
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            val localNotes = dbHelper.getAllNotes()
            val localFolders = dbHelper.getFolders()
            _notes.value = localNotes
            _folders.value = localFolders
        }
    }

    fun selectFolder(folder: String) {
        _selectedFolder.value = folder
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(newFilter: NoteFilter) {
        _filter.value = newFilter
    }

    fun openNote(id: Long) {
        _screen.value = Screen.View(id)
    }

    fun startNewNote() {
        val folder = if (_selectedFolder.value != "all" && _selectedFolder.value != "uncategorized") {
            _selectedFolder.value
        } else {
            ""
        }
        _screen.value = Screen.Edit(null, folder)
    }

    fun editCurrentNote() {
        val current = _screen.value
        if (current is Screen.View) {
            _screen.value = Screen.Edit(current.noteId)
        }
    }

    fun navigateBack(): Boolean {
        return when (val current = _screen.value) {
            is Screen.Edit -> {
                if (current.noteId != null) {
                    _screen.value = Screen.View(current.noteId)
                } else {
                    _screen.value = Screen.List
                }
                true
            }
            is Screen.View -> {
                _screen.value = Screen.List
                true
            }
            is Screen.List -> {
                if (_selectedFolder.value != "all") {
                    _selectedFolder.value = "all"
                    true
                } else {
                    false
                }
            }
        }
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

            val note = Note(
                id = finalId,
                title = if (title.isNotBlank()) title.trim() else "Untitled Note",
                text = text.trim(),
                preview = com.acmods.acnotes.data.extractPreview(text.trim()),
                folder = folder.trim(),
                date = dateStr,
                updatedAt = now,
                isPinned = false
            )

            dbHelper.saveNote(note)
            if (folder.isNotBlank()) {
                dbHelper.addFolder(folder.trim())
            }

            loadData()
            _screen.value = Screen.View(finalId)

            // Trigger silent background upload
            uploadNoteToR2(note)
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = dbHelper.getNote(id)
            dbHelper.deleteNote(id)
            loadData()
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

    fun toggleTaskInNote(noteId: Long, lineIndex: Int, isChecked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = dbHelper.getNote(noteId) ?: return@launch
            val lines = note.text.lines().toMutableList()
            if (lineIndex in 0 until lines.size) {
                val line = lines[lineIndex]
                val updatedLine = if (isChecked) {
                    line.replace(Regex("\\[ \\]"), "[x]")
                } else {
                    line.replace(Regex("\\[[xX]\\]"), "[ ]")
                }
                val updatedText = lines.joinToString("\n")
                val updatedNote = note.copy(
                    text = updatedText,
                    preview = com.acmods.acnotes.data.extractPreview(updatedText),
                    updatedAt = System.currentTimeMillis()
                )
                dbHelper.saveNote(updatedNote)
                loadData()
                uploadNoteToR2(updatedNote)
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
                _selectedFolder.value = "all"
            }
            loadData()
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

    private fun uploadNoteToR2(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            _syncState.value = SyncState.SYNCING
            val ok = r2Sync.putNote(note)
            _syncState.value = if (ok) SyncState.SAVED else SyncState.OFFLINE
        }
    }
}
