package com.acmods.acnotes.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acmods.acnotes.data.Note
import com.acmods.acnotes.data.R2Config
import com.acmods.acnotes.ui.*
import com.acmods.acnotes.ui.theme.*
import java.util.Locale

private val CardShape = RoundedCornerShape(16.dp)
private val FolderCardShape = RoundedCornerShape(14.dp)
private val NormalBorder = BorderStroke(1.dp, SurfaceBorder)
private val PinnedBorder = BorderStroke(1.dp, AmberPin.copy(alpha = 0.45f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    viewModel: NotesViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notes by viewModel.notes.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val isSetupDone by viewModel.isSetupDone.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showDeleteFolderDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Onboarding Auth State
    var setupMode by remember { mutableStateOf<String?>(null) } // null, "owner", "custom"
    var ownerPassword by remember { mutableStateOf("") }
    var showOwnerPassword by remember { mutableStateOf(false) }
    var ownerAuthError by remember { mutableStateOf<String?>(null) }

    var customEndpoint by remember { mutableStateOf("") }
    var customBucket by remember { mutableStateOf("") }
    var customAccessKey by remember { mutableStateOf("") }
    var customSecretKey by remember { mutableStateOf("") }
    var showCustomSecret by remember { mutableStateOf(false) }
    var customAuthError by remember { mutableStateOf<String?>(null) }

    val isHome = selectedFolder.isBlank() || selectedFolder.equals("home", ignoreCase = true)

    // User-created folders, excluding Home and uncategorized
    val displayFolders = remember(folders) {
        folders.filter { it.isNotBlank() && !it.equals("home", ignoreCase = true) && !it.equals("uncategorized", ignoreCase = true) }
    }

    // Memoized, strict filtering and sorting:
    // - On Home: ONLY notes in Home (folder == "" or "Home")
    // - In Folder: ONLY notes belonging to this specific folder
    // - Order: Newest added / modified first (updatedAt / id DESC), pinned at top
    val filteredNotes = remember(notes, selectedFolder, searchQuery, filter, isHome) {
        val q = searchQuery.trim()
        val list = notes.filter { note ->
            val noteFolder = note.folder.trim()
            val matchesFolder = if (q.isNotEmpty()) {
                if (isHome) true else noteFolder.equals(selectedFolder, ignoreCase = true)
            } else if (isHome) {
                noteFolder.isBlank() || noteFolder.equals("home", ignoreCase = true)
            } else {
                noteFolder.equals(selectedFolder, ignoreCase = true)
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
                note.text.contains(q, ignoreCase = true) ||
                note.folder.contains(q, ignoreCase = true)
            }

            matchesFolder && matchesFilter && matchesSearch
        }

        list.sortedWith(
            compareByDescending<Note> { it.isPinned }
                .thenByDescending { maxOf(it.updatedAt, it.id) }
                .thenByDescending { it.id }
        )
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
            // Header: Switches dynamically between Home View and Folder View
            if (isHome) {
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
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color(0x2210B981)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Ac",
                                color = EmeraldLight,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "AcNotes",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Home",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyncStatusBadge(syncState = syncState, onSyncClick = { viewModel.syncNow() })
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings & Updates",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.selectFolder("") }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Home", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "📁 ", fontSize = 16.sp)
                            Text(
                                text = selectedFolder,
                                color = TextPrimary,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = if (filteredNotes.size == 1) "1 note" else "${filteredNotes.size} notes",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = { showDeleteFolderDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Folder",
                            tint = RedDelete.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    SyncStatusBadge(syncState = syncState, onSyncClick = { viewModel.syncNow() })
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings & Updates",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
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
                        placeholder = {
                            Text(
                                text = if (isHome) "Search notes & folders..." else "Search in $selectedFolder...",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        },
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
                        IconButton(
                            onClick = { viewModel.setSearchQuery("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // On Home View (when not actively searching): display dedicated Folders section and Home Notes header
            if (isHome && searchQuery.isEmpty()) {
                // Folders Section Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "FOLDERS",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = SurfaceCard,
                            shape = RoundedCornerShape(8.dp),
                            border = NormalBorder
                        ) {
                            Text(
                                text = "${displayFolders.size}",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Surface(
                        color = Color(0x1810B981),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.clickable { showNewFolderDialog = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "New Folder",
                                color = EmeraldLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Folders Row
                if (displayFolders.isEmpty()) {
                    Surface(
                        color = SurfaceCard,
                        shape = FolderCardShape,
                        border = NormalBorder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { showNewFolderDialog = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Text(text = "📁", fontSize = 22.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Create your first folder",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Organize subjects like Math, Physics, Ideas",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayFolders) { folderName ->
                            val count = notes.count { it.folder.equals(folderName, ignoreCase = true) }
                            FolderCard(
                                name = folderName,
                                count = count,
                                onClick = { viewModel.selectFolder(folderName) }
                            )
                        }
                    }
                }

                // Notes Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NOTES",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = SurfaceCard,
                            shape = RoundedCornerShape(8.dp),
                            border = NormalBorder
                        ) {
                            Text(
                                text = "${filteredNotes.size}",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Pinned filter toggle if pinned notes exist
                    val pinnedCount = notes.count { (it.folder.isBlank() || it.folder.equals("home", ignoreCase = true)) && it.isPinned }
                    if (pinnedCount > 0) {
                        Surface(
                            color = if (filter == NoteFilter.PINNED) Color(0x22F59E0B) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            border = if (filter == NoteFilter.PINNED) BorderStroke(1.dp, AmberPin) else NormalBorder,
                            modifier = Modifier.clickable {
                                viewModel.setFilter(if (filter == NoteFilter.PINNED) NoteFilter.ALL else NoteFilter.PINNED)
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = if (filter == NoteFilter.PINNED) AmberPin else TextMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Pinned ($pinnedCount)",
                                    color = if (filter == NoteFilter.PINNED) AmberPin else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            } else if (!isHome) {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Notes List or Clean Empty state
            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = if (isHome) "📝" else "📁", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                "No matching notes found"
                            } else if (isHome) {
                                "No notes in Home yet"
                            } else {
                                "$selectedFolder is empty"
                            },
                            color = TextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                "Try searching for another keyword"
                            } else if (isHome) {
                                "Tap the + button to create a new note in Home"
                            } else {
                                "Tap + to add your first note to this folder"
                            },
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = filteredNotes,
                        key = { it.id },
                        contentType = { "note_card" }
                    ) { note ->
                        NoteCardItem(
                            note = note,
                            showFolderBadge = searchQuery.isNotEmpty() && note.folder.isNotBlank(),
                            onClick = { viewModel.openNote(note.id) },
                            onTogglePin = { viewModel.togglePin(note.id) }
                        )
                    }
                }
            }
        }
    }

    // Modal: First Launch Onboarding / Cloud Vault Authentication
    // As instructed: "كلمة سر تظهر مرة واحد فقط في بداية التطبيق ولا تظهر مجددا أبدا"
    if (!isSetupDone) {
        AlertDialog(
            onDismissRequest = { /* Persistent until user unlocks or configures */ },
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x2210B981)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🔐", fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Cloud Vault Setup",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connect your encrypted Cloudflare R2 or S3 notes vault.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (setupMode == null) {
                        Text(
                            text = "Are you the Owner of this vault?",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                setupMode = "owner"
                                ownerAuthError = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = BgDark
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("👑 Yes, I am the Owner", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                setupMode = "custom"
                                customAuthError = null
                            },
                            border = NormalBorder,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("☁️ No (Connect custom R2 / S3)", color = TextSecondary, fontSize = 13.sp)
                        }
                    } else if (setupMode == "owner") {
                        Text(
                            text = "Enter Owner Master Password to unlock your encrypted Cloudflare R2 credentials:",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ownerPassword,
                            onValueChange = {
                                ownerPassword = it
                                ownerAuthError = null
                            },
                            label = { Text("Master Password") },
                            singleLine = true,
                            visualTransformation = if (showOwnerPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showOwnerPassword = !showOwnerPassword }) {
                                    Icon(
                                        if (showOwnerPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Visibility",
                                        tint = TextMuted
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = SurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (ownerAuthError != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = ownerAuthError!!, color = RedDelete, fontSize = 12.sp)
                        }
                    } else if (setupMode == "custom") {
                        Text(
                            text = "Enter your custom Cloudflare R2 or S3 credentials:",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = customEndpoint,
                            onValueChange = { customEndpoint = it },
                            label = { Text("Endpoint Host") },
                            placeholder = { Text("e.g. xxx.r2.cloudflarestorage.com") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = SurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customBucket,
                            onValueChange = { customBucket = it },
                            label = { Text("Bucket Name") },
                            placeholder = { Text("e.g. my-vault") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = SurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customAccessKey,
                            onValueChange = { customAccessKey = it },
                            label = { Text("Access Key ID") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = SurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customSecretKey,
                            onValueChange = { customSecretKey = it },
                            label = { Text("Secret Access Key") },
                            singleLine = true,
                            visualTransformation = if (showCustomSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showCustomSecret = !showCustomSecret }) {
                                    Icon(
                                        if (showCustomSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Visibility",
                                        tint = TextMuted
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = SurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (customAuthError != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = customAuthError!!, color = RedDelete, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                if (setupMode == "owner") {
                    Button(
                        onClick = {
                            val success = viewModel.unlockAsOwner(ownerPassword)
                            if (!success) {
                                ownerAuthError = "Incorrect password. Access denied."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                    ) {
                        Text("Unlock Vault", fontWeight = FontWeight.Bold)
                    }
                } else if (setupMode == "custom") {
                    Button(
                        onClick = {
                            val ok = viewModel.setupCustomR2(customEndpoint, customBucket, customAccessKey, customSecretKey)
                            if (!ok) {
                                customAuthError = "Please fill in all 4 credential fields."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                    ) {
                        Text("Connect", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (setupMode != null) {
                    TextButton(onClick = { setupMode = null }) {
                        Text("← Back", color = TextSecondary)
                    }
                }
            },
            containerColor = SurfaceDark
        )
    }

    // Modal: Settings & In-App Updates
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = {
                showSettingsDialog = false
                viewModel.dismissUpdateDialog()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x2210B981)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "⚙️", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Settings & Updates", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // App Info
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        border = NormalBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("AcNotes App", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Surface(
                                    color = Color(0x2210B981),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "v3.0.0",
                                        color = EmeraldLight,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (R2Config.isOwner) "Vault: 👑 Owner (AES-256-GCM Protected)" else "Vault: ☁️ ${R2Config.bucket}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // In-App Update Engine Section
                    Text("SOFTWARE UPDATES", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        border = NormalBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            when (val state = updateState) {
                                is UpdateUiState.Idle -> {
                                    Text(
                                        text = "Check for official updates released on GitHub.",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = { viewModel.checkForUpdates("3.0.0") },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = EmeraldPrimary,
                                            contentColor = BgDark
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("🔄 Check for Updates", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                                is UpdateUiState.Checking -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = EmeraldPrimary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("Connecting to GitHub...", color = TextSecondary, fontSize = 13.sp)
                                    }
                                }
                                is UpdateUiState.UpToDate -> {
                                    Column {
                                        Text(
                                            text = "🎉 You're on the latest version!",
                                            color = EmeraldLight,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("No newer releases found on GitHub.", color = TextMuted, fontSize = 12.sp)
                                    }
                                }
                                is UpdateUiState.Available -> {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "🚀 New Version: v${state.update.version}",
                                                color = EmeraldLight,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                        if (state.update.releaseNotes.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = state.update.releaseNotes,
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 4,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                viewModel.startDownloadUpdate(context, state.update.downloadUrl)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = EmeraldPrimary,
                                                contentColor = BgDark
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("📥 Download & Install", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                }
                                is UpdateUiState.Downloading -> {
                                    Column {
                                        Text("Downloading update in-app...", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = state.progress,
                                            color = EmeraldPrimary,
                                            trackColor = SurfaceBorder,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val percent = (state.progress * 100).toInt()
                                        val dlMb = String.format(Locale.US, "%.1f", state.downloadedBytes / (1024f * 1024f))
                                        val totMb = String.format(Locale.US, "%.1f", state.totalBytes / (1024f * 1024f))
                                        Text(
                                            text = "$percent% ($dlMb MB / $totMb MB)",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                is UpdateUiState.Error -> {
                                    Column {
                                        Text(text = "Update error: ${state.message}", color = RedDelete, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        TextButton(onClick = { viewModel.checkForUpdates("3.0.0") }) {
                                            Text("Retry", color = EmeraldPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Reconfigure cloud option
                    OutlinedButton(
                        onClick = {
                            viewModel.resetVaultConfig()
                            showSettingsDialog = false
                        },
                        border = NormalBorder,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset / Change Vault Credentials", color = TextMuted, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSettingsDialog = false
                    viewModel.dismissUpdateDialog()
                }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    // Dialog: Create New Folder
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Create New Folder", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("Folder name (e.g. Physics, Ideas)") },
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
                            val cleanName = newFolderName.trim()
                            viewModel.addFolder(cleanName)
                            viewModel.selectFolder(cleanName)
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

    // Dialog: Delete Folder Confirmation
    if (showDeleteFolderDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteFolderDialog = false },
            title = { Text("Delete Folder?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"$selectedFolder\"? All notes inside will be preserved and moved to Home.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(selectedFolder)
                        showDeleteFolderDialog = false
                    }
                ) {
                    Text("Delete", color = RedDelete, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun FolderCard(
    name: String,
    count: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "folderCardScale"
    )

    Surface(
        color = SurfaceCard,
        shape = FolderCardShape,
        border = NormalBorder,
        modifier = Modifier
            .width(135.dp)
            .height(82.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1810B981)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "📁", fontSize = 14.sp)
                }
                Text(
                    text = if (count == 1) "1 note" else "$count notes",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = name,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun NoteCardItem(
    note: Note,
    showFolderBadge: Boolean = false,
    onClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardPressScale"
    )

    Surface(
        color = SurfaceCard,
        shape = CardShape,
        border = if (note.isPinned) PinnedBorder else NormalBorder,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
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

                if (showFolderBadge && note.folder.isNotBlank()) {
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

@Composable
fun SyncStatusBadge(
    syncState: SyncState,
    onSyncClick: () -> Unit
) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = NormalBorder,
        modifier = Modifier.clickable { onSyncClick() }
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
