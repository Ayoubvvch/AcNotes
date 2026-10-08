package com.acmods.acnotes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acmods.acnotes.ui.NotesViewModel
import com.acmods.acnotes.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    initialFolder: String = "",
    viewModel: NotesViewModel,
    modifier: Modifier = Modifier
) {
    val notes by viewModel.notes.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val note = remember(noteId, notes) { if (noteId != null) notes.find { it.id == noteId } else null }

    var title by remember { mutableStateOf(note?.title ?: "") }
    var bodyValue by remember {
        mutableStateOf(TextFieldValue(note?.text ?: ""))
    }
    var selectedFolder by remember {
        mutableStateOf(note?.folder ?: initialFolder)
    }
    var showFolderMenu by remember { mutableStateOf(false) }

    fun insertMarkdown(prefix: String, suffix: String = "", placeholder: String = "") {
        val text = bodyValue.text
        val selection = bodyValue.selection
        val selectedText = if (selection.start != selection.end) {
            text.substring(selection.start, selection.end)
        } else {
            placeholder
        }

        val newText = text.replaceRange(selection.start, selection.end, "$prefix$selectedText$suffix")
        val newCursor = selection.start + prefix.length + selectedText.length + suffix.length
        bodyValue = TextFieldValue(
            text = newText,
            selection = TextRange(newCursor)
        )
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Surface(
                            color = SurfaceCard,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier.clickable { showFolderMenu = true }
                        ) {
                            Text(
                                text = if (selectedFolder.isNotBlank()) "📁 $selectedFolder ▾" else "🏠 الرئيسي ▾",
                                color = EmeraldLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showFolderMenu,
                            onDismissRequest = { showFolderMenu = false },
                            modifier = Modifier.background(SurfaceDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text("🏠 الرئيسي (بدون مجلد)", color = TextPrimary, fontSize = 13.sp) },
                                onClick = {
                                    selectedFolder = ""
                                    showFolderMenu = false
                                }
                            )
                            folders.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("📁 $f", color = TextPrimary, fontSize = 13.sp) },
                                    onClick = {
                                        selectedFolder = f
                                        showFolderMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = TextPrimary)
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            viewModel.saveNote(
                                id = noteId,
                                title = title,
                                text = bodyValue.text,
                                folder = selectedFolder
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = BgDark
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("حفظ", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        bottomBar = {
            // Sticky Formatting Toolbar
            Surface(
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatChip("B") { insertMarkdown("**", "**", "عريض") }
                    FormatChip("I") { insertMarkdown("*", "*", "مائل") }
                    FormatChip("H1") { insertMarkdown("# ", "", "عنوان رئيسي") }
                    FormatChip("H2") { insertMarkdown("## ", "", "عنوان فرعي") }
                    FormatChip("• قائمة") { insertMarkdown("- ", "", "عنصر") }
                    FormatChip("☑ مهمة") { insertMarkdown("- [ ] ", "", "مهمة جديدة") }
                    FormatChip("❝ اقتباس") { insertMarkdown("> ", "", "اقتباس") }
                    FormatChip("</> كود") { insertMarkdown("```\n", "\n```", "code") }
                    FormatChip("🔗 رابط") { insertMarkdown("[عنوان]", "(رابط)") }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Title Input
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("عنوان الملاحظة...", color = TextMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold) },
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
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Divider(color = SurfaceBorder, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

            // Body Input
            TextField(
                value = bodyValue,
                onValueChange = { bodyValue = it },
                placeholder = { Text("ابدأ بكتابة أفكارك وملاحظاتك هنا...", color = TextMuted, fontSize = 15.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = EmeraldPrimary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
fun FormatChip(text: String, onClick: () -> Unit) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
