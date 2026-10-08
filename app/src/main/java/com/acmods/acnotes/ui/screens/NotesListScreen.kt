package com.acmods.acnotes.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acmods.acnotes.data.Note
import com.acmods.acnotes.ui.*
import com.acmods.acnotes.ui.theme.*

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

    // Filter notes
    val filteredNotes = remember(notes, selectedFolder, searchQuery, filter) {
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
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val q = searchQuery.lowercase()
                note.title.lowercase().contains(q) ||
                        note.text.lowercase().contains(q) ||
                        note.folder.lowercase().contains(q)
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
                Icon(Icons.Default.Add, contentDescription = "ملاحظة جديدة", modifier = Modifier.size(28.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
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
                                SyncState.SAVED -> "متزامن"
                                SyncState.SYNCING -> "مزامنة..."
                                SyncState.OFFLINE -> "أوفلاين"
                                SyncState.IDLE -> "متصل"
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
                                contentDescription = "مزامنة",
                                tint = TextSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            // Search Bar
            Surface(
                color = SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
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
                        contentDescription = "بحث",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("بحث في الملاحظات...", color = TextMuted, fontSize = 14.sp) },
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
                            Icon(Icons.Default.Close, contentDescription = "مسح", tint = TextSecondary, modifier = Modifier.size(16.dp))
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
                        title = "جميع الملاحظات",
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
                        title = "المثبتة ⭐",
                        count = notes.count { it.isPinned },
                        isSelected = filter == NoteFilter.PINNED,
                        onClick = {
                            viewModel.setFilter(NoteFilter.PINNED)
                        }
                    )
                }
                item {
                    FolderChip(
                        title = "الرئيسي 🏠",
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
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldDark),
                        modifier = Modifier.clickable { showNewFolderDialog = true }
                    ) {
                        Text(
                            text = "+ مجلد",
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
                            text = if (searchQuery.isNotEmpty()) "لا توجد نتائج بحث" else "لا توجد ملاحظات هنا",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "انقر على زر + للبدء في كتابة أول فكرة",
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
                    items(filteredNotes, key = { it.id }) { note ->
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
            title = { Text("إنشاء مجلد جديد", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("اسم المجلد (مثال: أفكار، عمل)") },
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
                    Text("إضافة", color = EmeraldPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("إلغاء", color = TextSecondary)
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
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) EmeraldPrimary else SurfaceBorder
        ),
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
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (note.isPinned) AmberPin.copy(alpha = 0.4f) else SurfaceBorder
        ),
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
                    text = if (note.title.isNotBlank()) note.title else "بدون عنوان",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (note.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "تثبيت",
                        tint = if (note.isPinned) AmberPin else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val preview = note.text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.take(3).joinToString(" ")
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
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
