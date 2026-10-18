package com.acmods.acnotes.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
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
import com.acmods.acnotes.data.UserProfile
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

    val userProfile by viewModel.userProfile.collectAsState()
    val isCloudConnected by viewModel.isCloudConnected.collectAsState()
    val isInitialSetupDone by viewModel.isInitialSetupDone.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showDeleteFolderDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Authentication & Onboarding Modal State
    // Modes: null (choose: Sign In / Sign Up / Offline), "signin", "signup", "ask_name"
    var authMode by remember { mutableStateOf<String?>(null) }
    var authHost by remember { mutableStateOf("") }
    var authBucket by remember { mutableStateOf("") }
    var authAccessKey by remember { mutableStateOf("") }
    var authSecretKey by remember { mutableStateOf("") }
    var showAuthSecret by remember { mutableStateOf(false) }
    var authName by remember { mutableStateOf("") }
    var authEmoji by remember { mutableStateOf("👤") }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifyingAuth by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setProfileAvatarUri(it) }
    }

    var discoveredBuckets by remember { mutableStateOf<List<String>>(emptyList()) }
    var isDiscoveringBuckets by remember { mutableStateOf(false) }
    var hasDiscoveredBuckets by remember { mutableStateOf(false) }
    var isCreatingNewBucket by remember { mutableStateOf(false) }
    var newBucketInput by remember { mutableStateOf("") }

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
                        val headerAvatarBitmap = remember(userProfile.avatarImagePath) {
                            if (userProfile.avatarImagePath.isNotBlank()) {
                                val file = File(userProfile.avatarImagePath)
                                if (file.exists() && file.length() > 0) {
                                    BitmapFactory.decodeFile(file.absolutePath)
                                } else null
                            } else null
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color(0x2210B981))
                                .clickable { showSettingsDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            if (headerAvatarBitmap != null) {
                                Image(
                                    bitmap = headerAvatarBitmap.asImageBitmap(),
                                    contentDescription = "Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = if (userProfile.avatarEmoji.isNotBlank()) userProfile.avatarEmoji else "Ac",
                                    fontSize = if (userProfile.avatarEmoji.isNotBlank()) 19.sp else 17.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (userProfile.name.isNotBlank()) userProfile.name else "AcNotes",
                                color = TextPrimary,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isCloudConnected) EmeraldPrimary else TextMuted)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isCloudConnected) "Cloud Synced" else "Offline Mode",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCloudConnected) {
                            SyncStatusBadge(syncState = syncState, onSyncClick = { viewModel.syncNow() })
                        } else {
                            Surface(
                                color = SurfaceCard,
                                shape = RoundedCornerShape(14.dp),
                                border = NormalBorder,
                                modifier = Modifier.clickable { showSettingsDialog = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Offline", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
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
                    if (isCloudConnected) {
                        SyncStatusBadge(syncState = syncState, onSyncClick = { viewModel.syncNow() })
                    }
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

    // Modal: Universal Onboarding / Sign In / Sign Up / Work Offline
    if (!isInitialSetupDone) {
        AlertDialog(
            onDismissRequest = { /* Must choose an option on first launch */ },
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0x2210B981)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🏛️", fontSize = 17.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when (authMode) {
                                "signin" -> "Sign In to Vault"
                                "signup" -> "Create Workspace"
                                "ask_name" -> "Profile Name"
                                else -> "Welcome to AcNotes"
                            },
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (authMode) {
                            "signin" -> "Connect an existing Cloudflare R2 / S3 vault to restore notes."
                            "signup" -> "Personalize your profile and set up cloud backup."
                            "ask_name" -> "Enter your name to personalize your workspace."
                            else -> "Choose how you want to set up your workspace."
                        },
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (authMode == null) {
                        // Mode Selection: Sign In / Sign Up / Offline
                        Button(
                            onClick = {
                                authMode = "signin"
                                authErrorMessage = null
                                hasDiscoveredBuckets = false
                                discoveredBuckets = emptyList()
                                isCreatingNewBucket = false
                                newBucketInput = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = BgDark
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sign In (Connect Existing Vault)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                authMode = "signup"
                                authErrorMessage = null
                                hasDiscoveredBuckets = false
                                discoveredBuckets = emptyList()
                                isCreatingNewBucket = false
                                newBucketInput = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceCard,
                                contentColor = TextPrimary
                            ),
                            border = NormalBorder,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sign Up (New Workspace)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.continueOffline()
                            },
                            border = NormalBorder,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Work Offline (No Cloud Sync)", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    } else if (authMode == "signin") {
                        // Sign In Form: Asks for R2 credentials only, discovers buckets automatically
                        OutlinedTextField(
                            value = authHost,
                            onValueChange = { authHost = it; authErrorMessage = null; hasDiscoveredBuckets = false },
                            label = { Text("Account ID or Endpoint") },
                            placeholder = { Text("e.g. 5d0d1d468b161e84470798a691e7b5b9") },
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
                            value = authAccessKey,
                            onValueChange = { authAccessKey = it; authErrorMessage = null; hasDiscoveredBuckets = false },
                            label = { Text("Access Key ID") },
                            placeholder = { Text("e.g. d1fc280a9699...") },
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
                            value = authSecretKey,
                            onValueChange = { authSecretKey = it; authErrorMessage = null; hasDiscoveredBuckets = false },
                            label = { Text("Secret Access Key") },
                            singleLine = true,
                            visualTransformation = if (showAuthSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showAuthSecret = !showAuthSecret }) {
                                    Icon(
                                        if (showAuthSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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

                        // Smart Bucket Discovery Section
                        if (hasDiscoveredBuckets) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Select Vault Bucket:", color = EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))

                            if (discoveredBuckets.isEmpty()) {
                                Text("No buckets found on this account. Create one below:", color = TextMuted, fontSize = 12.sp)
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    items(discoveredBuckets) { b ->
                                        val isSelected = authBucket == b
                                        Surface(
                                            color = if (isSelected) EmeraldPrimary.copy(alpha = 0.2f) else SurfaceCard,
                                            border = if (isSelected) BorderStroke(1.5.dp, EmeraldPrimary) else NormalBorder,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.clickable { authBucket = b }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = b,
                                                    color = if (isSelected) EmeraldLight else TextPrimary,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (isCreatingNewBucket) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = newBucketInput,
                                        onValueChange = { newBucketInput = it },
                                        placeholder = { Text("new-bucket-name") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = EmeraldPrimary,
                                            unfocusedBorderColor = SurfaceBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            val clean = newBucketInput.trim().lowercase()
                                            if (clean.isNotBlank()) {
                                                isVerifyingAuth = true
                                                viewModel.createAndSelectBucket(authHost, clean, authAccessKey, authSecretKey) { ok, msg ->
                                                    isVerifyingAuth = false
                                                    if (ok) {
                                                        discoveredBuckets = discoveredBuckets + clean
                                                        authBucket = clean
                                                        isCreatingNewBucket = false
                                                        newBucketInput = ""
                                                    } else {
                                                        authErrorMessage = msg
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                                    ) {
                                        Text("Create")
                                    }
                                }
                            } else {
                                TextButton(
                                    onClick = { isCreatingNewBucket = true },
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ Create New Bucket", color = EmeraldPrimary, fontSize = 12.sp)
                                }
                            }
                        }

                        if (authErrorMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = authErrorMessage!!, color = RedDelete, fontSize = 12.sp)
                        }
                    } else if (authMode == "signup") {
                        // Sign Up Form: Gallery Photo + Name + Auto-discovered Cloud info
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            val signupAvatarBitmap = remember(userProfile.avatarImagePath) {
                                if (userProfile.avatarImagePath.isNotBlank()) {
                                    val f = File(userProfile.avatarImagePath)
                                    if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
                                } else null
                            }

                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x2210B981))
                                    .clickable { galleryLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                if (signupAvatarBitmap != null) {
                                    Image(
                                        bitmap = signupAvatarBitmap.asImageBitmap(),
                                        contentDescription = "Avatar",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.PhotoCamera, contentDescription = "Pick Photo", tint = EmeraldLight, modifier = Modifier.size(22.dp))
                                        Text("Photo", color = TextMuted, fontSize = 9.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = authName,
                                    onValueChange = { authName = it; authErrorMessage = null },
                                    label = { Text("Your Name") },
                                    placeholder = { Text("e.g. Ayoub") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = EmeraldPrimary,
                                        unfocusedBorderColor = SurfaceBorder,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Optional Emoji Quick Selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("👤", "🚀", "🧠", "⚡", "📚", "🏛️", "🌿", "💎").forEach { em ->
                                val selected = authEmoji == em
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0x3310B981) else SurfaceCard)
                                        .clickable { authEmoji = em },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = em, fontSize = 16.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = authHost,
                            onValueChange = { authHost = it; authErrorMessage = null; hasDiscoveredBuckets = false },
                            label = { Text("Account ID or Endpoint") },
                            placeholder = { Text("e.g. 5d0d1d468b161e84470798a691e7b5b9") },
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
                            value = authAccessKey,
                            onValueChange = { authAccessKey = it; authErrorMessage = null; hasDiscoveredBuckets = false },
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
                            value = authSecretKey,
                            onValueChange = { authSecretKey = it; authErrorMessage = null; hasDiscoveredBuckets = false },
                            label = { Text("Secret Access Key") },
                            singleLine = true,
                            visualTransformation = if (showAuthSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showAuthSecret = !showAuthSecret }) {
                                    Icon(
                                        if (showAuthSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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

                        // Smart Bucket Discovery in Sign Up
                        if (hasDiscoveredBuckets) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Select Vault Bucket:", color = EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))

                            if (discoveredBuckets.isEmpty()) {
                                Text("No buckets found. Create a new bucket below:", color = TextMuted, fontSize = 12.sp)
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    items(discoveredBuckets) { b ->
                                        val isSelected = authBucket == b
                                        Surface(
                                            color = if (isSelected) EmeraldPrimary.copy(alpha = 0.2f) else SurfaceCard,
                                            border = if (isSelected) BorderStroke(1.5.dp, EmeraldPrimary) else NormalBorder,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.clickable { authBucket = b }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = b,
                                                    color = if (isSelected) EmeraldLight else TextPrimary,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (isCreatingNewBucket) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = newBucketInput,
                                        onValueChange = { newBucketInput = it },
                                        placeholder = { Text("new-bucket-name") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = EmeraldPrimary,
                                            unfocusedBorderColor = SurfaceBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            val clean = newBucketInput.trim().lowercase()
                                            if (clean.isNotBlank()) {
                                                isVerifyingAuth = true
                                                viewModel.createAndSelectBucket(authHost, clean, authAccessKey, authSecretKey) { ok, msg ->
                                                    isVerifyingAuth = false
                                                    if (ok) {
                                                        discoveredBuckets = discoveredBuckets + clean
                                                        authBucket = clean
                                                        isCreatingNewBucket = false
                                                        newBucketInput = ""
                                                    } else {
                                                        authErrorMessage = msg
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                                    ) {
                                        Text("Create")
                                    }
                                }
                            } else {
                                TextButton(
                                    onClick = { isCreatingNewBucket = true },
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ Create New Bucket", color = EmeraldPrimary, fontSize = 12.sp)
                                }
                            }
                        }

                        if (authErrorMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = authErrorMessage!!, color = RedDelete, fontSize = 12.sp)
                        }
                    } else if (authMode == "ask_name") {
                        // Follow-up if sign in had no profile name
                        Text("What should we call you?", color = TextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = authName,
                            onValueChange = { authName = it },
                            label = { Text("Your Name") },
                            placeholder = { Text("e.g. Ayoub") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = SurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                if (authMode == "signin") {
                    if (!hasDiscoveredBuckets) {
                        Button(
                            onClick = {
                                if (authHost.isBlank() || authAccessKey.isBlank() || authSecretKey.isBlank()) {
                                    authErrorMessage = "Account ID, Access Key ID, and Secret Key are required"
                                    return@Button
                                }
                                isDiscoveringBuckets = true
                                authErrorMessage = null
                                viewModel.fetchBucketsForAccount(authHost, authAccessKey, authSecretKey) { ok, buckets, err ->
                                    isDiscoveringBuckets = false
                                    if (ok) {
                                        discoveredBuckets = buckets
                                        hasDiscoveredBuckets = true
                                        if (buckets.isNotEmpty()) {
                                            authBucket = if (buckets.contains("my-vault")) "my-vault" else buckets.first()
                                        }
                                    } else {
                                        authErrorMessage = err
                                    }
                                }
                            },
                            enabled = !isDiscoveringBuckets,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                        ) {
                            if (isDiscoveringBuckets) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Find Buckets", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (authBucket.isBlank()) {
                                    authErrorMessage = "Please select or create a bucket first"
                                    return@Button
                                }
                                isVerifyingAuth = true
                                authErrorMessage = null
                                viewModel.connectCloudVault(
                                    endpoint = authHost,
                                    bucket = authBucket,
                                    accessKey = authAccessKey,
                                    secretKey = authSecretKey
                                ) { success, msg ->
                                    isVerifyingAuth = false
                                    if (success) {
                                        if (userProfile.name.isBlank()) {
                                            authMode = "ask_name"
                                        }
                                    } else {
                                        authErrorMessage = msg
                                    }
                                }
                            },
                            enabled = !isVerifyingAuth,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                        ) {
                            if (isVerifyingAuth) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Connect & Restore", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (authMode == "signup") {
                    if (!hasDiscoveredBuckets) {
                        Button(
                            onClick = {
                                if (authHost.isBlank() || authAccessKey.isBlank() || authSecretKey.isBlank()) {
                                    authErrorMessage = "Account ID, Access Key ID, and Secret Key are required"
                                    return@Button
                                }
                                isDiscoveringBuckets = true
                                authErrorMessage = null
                                viewModel.fetchBucketsForAccount(authHost, authAccessKey, authSecretKey) { ok, buckets, err ->
                                    isDiscoveringBuckets = false
                                    if (ok) {
                                        discoveredBuckets = buckets
                                        hasDiscoveredBuckets = true
                                        if (buckets.isNotEmpty()) {
                                            authBucket = if (buckets.contains("my-vault")) "my-vault" else buckets.first()
                                        }
                                    } else {
                                        authErrorMessage = err
                                    }
                                }
                            },
                            enabled = !isDiscoveringBuckets,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                        ) {
                            if (isDiscoveringBuckets) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Find Buckets", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (authBucket.isBlank()) {
                                    authErrorMessage = "Please select or create a bucket first"
                                    return@Button
                                }
                                isVerifyingAuth = true
                                authErrorMessage = null
                                viewModel.connectCloudVault(
                                    endpoint = authHost,
                                    bucket = authBucket,
                                    accessKey = authAccessKey,
                                    secretKey = authSecretKey,
                                    name = authName
                                ) { success, msg ->
                                    isVerifyingAuth = false
                                    if (success) {
                                        viewModel.updateProfile(authName, authEmoji)
                                    } else {
                                        authErrorMessage = msg
                                    }
                                }
                            },
                            enabled = !isVerifyingAuth,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                        ) {
                            if (isVerifyingAuth) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Create & Connect", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (authMode == "ask_name") {
                    Button(
                        onClick = {
                            if (authName.isNotBlank()) {
                                viewModel.updateProfile(authName, "👤")
                            }
                            authMode = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark)
                    ) {
                        Text("Save & Enter", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (authMode != null && authMode != "ask_name") {
                    TextButton(onClick = { authMode = null }) {
                        Text("← Back", color = TextSecondary)
                    }
                }
            },
            containerColor = SurfaceDark
        )
    }

    // Modal: Settings & In-App Updates & Account Management
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
                    Text("Settings & Account", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Profile Card
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        border = NormalBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val settingsAvatarBitmap = remember(userProfile.avatarImagePath) {
                                if (userProfile.avatarImagePath.isNotBlank()) {
                                    val f = File(userProfile.avatarImagePath)
                                    if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
                                } else null
                            }

                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x2210B981))
                                    .clickable { galleryLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                if (settingsAvatarBitmap != null) {
                                    Image(
                                        bitmap = settingsAvatarBitmap.asImageBitmap(),
                                        contentDescription = "Avatar",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(text = if (userProfile.avatarEmoji.isNotBlank()) userProfile.avatarEmoji else "👤", fontSize = 22.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (userProfile.name.isNotBlank()) userProfile.name else "AcNotes User",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = if (isCloudConnected) "Cloud: ${R2Config.bucket}" else "Mode: Offline (Local Storage)",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            IconButton(
                                onClick = { galleryLauncher.launch("image/*") },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = "Change Photo", tint = EmeraldLight, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Cloud Vault Section
                    Text("CLOUD VAULT", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        border = NormalBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (isCloudConnected) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(EmeraldPrimary)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Connected to R2", color = EmeraldLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    TextButton(onClick = { viewModel.syncNow() }) {
                                        Text("Sync Now", color = EmeraldPrimary, fontSize = 12.sp)
                                    }
                                }
                                Text(
                                    text = "Host: ${R2Config.endpointHost}",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        viewModel.disconnectCloud()
                                        showSettingsDialog = false
                                    },
                                    border = NormalBorder,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Disconnect Cloud / Switch Vault", color = RedDelete, fontSize = 12.sp)
                                }
                            } else {
                                Text("You are currently working in offline local mode.", color = TextMuted, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        showSettingsDialog = false
                                        authMode = "signin"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = BgDark),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Connect to Cloud Vault", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // In-App Update Engine Section
                    Text("SOFTWARE UPDATES", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        border = NormalBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            when (val state = updateState) {
                                is UpdateUiState.Idle -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("AcNotes App", color = TextPrimary, fontSize = 13.sp)
                                        Text("v3.2.1", color = TextMuted, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = { viewModel.checkForUpdates("3.2.1") },
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
                                        Text("Checking GitHub for updates...", color = TextSecondary, fontSize = 13.sp)
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
                                        TextButton(onClick = { viewModel.checkForUpdates("3.1.0") }) {
                                            Text("Retry", color = EmeraldPrimary)
                                        }
                                    }
                                }
                            }
                        }
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
