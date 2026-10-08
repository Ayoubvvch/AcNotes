package com.acmods.acnotes.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acmods.acnotes.data.Note
import com.acmods.acnotes.ui.*
import com.acmods.acnotes.ui.theme.*

private val CardShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(12.dp)
private val NormalBorder = BorderStroke(1.dp, SurfaceBorder)
private val PinnedBorder = BorderStroke(1.dp, AmberPin.copy(alpha = 0.4f))
private val SelectedChipBorder = BorderStroke(1.dp, EmeraldPrimary)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    viewModel: NotesViewModel,
    modifier: Modifier = Modifier
) {
    val notes by viewModel.notes.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // Fast memoized filtering
    val filteredNotes = remember(notes, selectedFolder, searchQuery, filter) {
        val q = searchQuery.trim()
        notes.filter { note ->
            val matchesFolder = when (selectedFolder) {
                "all" -> true
                "uncategorized" -> note.folder.isBlank()
                else -> note.folder == selectedFolder
            }
            val matchesFilter = when (filter) {
                NoteFilter.ALL -> true
                NoteFilter.PINNED -> note.isPinned
            }
            val matchesSearch = if (q.isEmpty()) {
                true
            } else {
                note.title.contains(q, ignoreCase = true) ||
                        note.preview.contains(q, ignoreCase = true) ||
                        note.folder.contains(q, ignoreCase = true)
            }
            matchesFolder && matchesFilter && matchesSearch
        }
    }

    Scaffold(
        containerColor = BgDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.startNewNote() },
                containerColor = EmeraldPrimary,
                contentColor = BgDark,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 16.dp, end = 8.dp)
                    .size(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Note", modifier = Modifier.size(28.dp))
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Header: DeepSeek / ChatGPT Style
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(EmeraldPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Ac",
                            color = BgDark,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "AcNotes",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Sync status indicator
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    border = NormalBorder,
                    modifier = Modifier.clickable { viewModel.syncNow() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        val dotColor = when (syncState) {
                            SyncState.SAVED -> EmeraldPrimary
                            SyncState.SYNCING -> AmberPin
                            SyncState.OFFLINE -> TextMuted
                            SyncState.IDLE -> EmeraldLight
                        }
                        val textLabel = when (syncState) {
                            SyncState.SAVED -> "Synced"
                            SyncState.SYNCING -> "Syncing..."
                            SyncState.OFFLINE -> "Offline"
                            SyncState.IDLE -> "Connected"
                        }
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = textLabel,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Sync",
                            tint = TextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // Search Bar
            Surface(
                color = SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                border = NormalBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search notes...", color = TextMuted, fontSize = 14.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = EmeraldPrimary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Folder and Filter chips row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FolderChip(
                        title = "All Notes",
                        count = notes.size,
                        isSelected = selectedFolder == "all" && filter == NoteFilter.ALL,
                        onClick = {
                            viewModel.selectFolder("all")
                            viewModel.setFilter(NoteFilter.ALL)
                        }
                    )
                }
                item {
                    FolderChip(
                        title = "Pinned ⭐",
                        count = notes.count { it.isPinned },
                        isSelected = filter == NoteFilter.PINNED,
                        onClick = {
                            viewModel.setFilter(NoteFilter.PINNED)
                        }
                    )
                }
                item {
                    FolderChip(
                        title = "Root 🏠",
                        count = notes.count { it.folder.isBlank() },
                        isSelected = selectedFolder == "uncategorized" && filter == NoteFilter.ALL,
                        onClick = {
                            viewModel.selectFolder("uncategorized")
                            viewModel.setFilter(NoteFilter.ALL)
                        }
                    )
                }
                items(folders) { folderName ->
                    val count = notes.count { it.folder == folderName }
                    FolderChip(
                        title = "📁 $folderName",
                        count = count,
                        isSelected = selectedFolder == folderName && filter == NoteFilter.ALL,
                        onClick = {
                            viewModel.selectFolder(folderName)
                            viewModel.setFilter(NoteFilter.ALL)
                        }
                    )
                }
                item {
                    Surface(
                        color = Color.Transparent,
                        shape = ChipShape,
                        border = BorderStroke(1.dp, EmeraldDark),
                        modifier = Modifier.clickable { showNewFolderDialog = true }
                    ) {
                        Text(
                            text = "+ Folder",
                            color = EmeraldLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // Notes List or Empty state
            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "📝", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matching notes found" else "No notes here yet",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap the + button to create your first note",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = filteredNotes,
                        key = { it.id },
                        contentType = { "note_card" }
                    ) { note ->
                        NoteCardItem(
                            note = note,
                            onClick = { viewModel.openNote(note.id) },
                            onTogglePin = { viewModel.togglePin(note.id) }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Create New Folder", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("Folder name (e.g. Ideas, Work)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = SurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.addFolder(newFolderName.trim())
                            viewModel.selectFolder(newFolderName.trim())
                            newFolderName = ""
                        }
                        showNewFolderDialog = false
                    }
                ) {
                    Text("Create", color = EmeraldPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun FolderChip(
    title: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) Color(0x2210B981) else SurfaceCard,
        shape = ChipShape,
        border = if (isSelected) SelectedChipBorder else NormalBorder,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = title,
                color = if (isSelected) EmeraldLight else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = count.toString(),
                color = if (isSelected) EmeraldPrimary else TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun NoteCardItem(
    note: Note,
    onClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    Surface(
        color = SurfaceCard,
        shape = CardShape,
        border = if (note.isPinned) PinnedBorder else NormalBorder,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (note.title.isNotBlank()) note.title else "Untitled Note",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (note.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "Pin",
                        tint = if (note.isPinned) AmberPin else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Zero-allocation precomputed preview! Instant 120 FPS render
            if (note.preview.isNotBlank()) {
                Text(
                    text = note.preview,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                    style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content)
                )
                Spacer(modifier = Modifier.height(10.dp))
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.date,
                    color = TextMuted,
                    fontSize = 11.sp
                )

                if (note.folder.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x1510B981))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "📁 ${note.folder}",
                            color = EmeraldLight,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
