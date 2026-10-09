package com.acmods.acnotes.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextDirection
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

    // Auto-save on back or navigation
    val onBackAction: () -> Unit = {
        if (title.isNotBlank() || bodyValue.text.isNotBlank()) {
            viewModel.saveNote(
                id = noteId,
                title = title,
                text = bodyValue.text,
                folder = selectedFolder
            )
        } else {
            viewModel.navigateBack()
        }
    }
    BackHandler { onBackAction() }

    fun handleSmartEnter(oldVal: TextFieldValue, newVal: TextFieldValue): TextFieldValue {
        val oldText = oldVal.text
        val newText = newVal.text
        val cursor = newVal.selection.start
        if (newText.length == oldText.length + 1 && cursor > 0 && newText[cursor - 1] == '\n') {
            val lineStart = newText.lastIndexOf('\n', cursor - 2) + 1
            val prevLine = newText.substring(lineStart, cursor - 1)

            val taskRegex = Regex("^(\\s*[-*+]\\s*\\[[ xX]\\]\\s*)")
            val bulletRegex = Regex("^(\\s*[-*+]\\s*)")

            val taskMatch = taskRegex.find(prevLine)
            if (taskMatch != null) {
                val prefix = taskMatch.value
                return if (prevLine.trim() == prefix.trim()) {
                    // Empty task item: cancel out marker on enter
                    val cleaned = newText.substring(0, lineStart) + newText.substring(cursor)
                    TextFieldValue(cleaned, TextRange(lineStart))
                } else {
                    val indent = prefix.takeWhile { it.isWhitespace() }
                    val auto = "$indent- [ ] "
                    val result = newText.substring(0, cursor) + auto + newText.substring(cursor)
                    TextFieldValue(result, TextRange(cursor + auto.length))
                }
            }

            val bulletMatch = bulletRegex.find(prevLine)
            if (bulletMatch != null) {
                val prefix = bulletMatch.value
                return if (prevLine.trim() == prefix.trim()) {
                    // Empty bullet: cancel out marker on enter
                    val cleaned = newText.substring(0, lineStart) + newText.substring(cursor)
                    TextFieldValue(cleaned, TextRange(lineStart))
                } else {
                    val indent = prefix.takeWhile { it.isWhitespace() }
                    val auto = "$indent- "
                    val result = newText.substring(0, cursor) + auto + newText.substring(cursor)
                    TextFieldValue(result, TextRange(cursor + auto.length))
                }
            }
        }
        return newVal
    }

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

    fun insertHeading(level: String) {
        val text = bodyValue.text
        val selection = bodyValue.selection
        val lineStart = (text.lastIndexOf('\n', (selection.start - 1).coerceAtLeast(0)) + 1).coerceAtLeast(0)
        val newText = text.substring(0, lineStart) + level + text.substring(lineStart)
        val newCursor = selection.start + level.length
        bodyValue = TextFieldValue(newText, TextRange(newCursor))
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
                                text = if (selectedFolder.isNotBlank()) "📁 $selectedFolder ▾" else "🏠 Root ▾",
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
                                text = { Text("🏠 Root (No folder)", color = TextPrimary, fontSize = 13.sp) },
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
                    IconButton(onClick = onBackAction) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
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
                        Text("Save", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                    FormatChip("B") { insertMarkdown("**", "**", "bold") }
                    FormatChip("I") { insertMarkdown("*", "*", "italic") }
                    FormatChip("H1") { insertHeading("# ") }
                    FormatChip("H2") { insertHeading("## ") }
                    FormatChip("• List") { insertMarkdown("- ", "", "item") }
                    FormatChip("☑ Task") { insertMarkdown("- [ ] ", "", "New task") }
                    FormatChip("❝ Quote") { insertMarkdown("> ", "", "quote") }
                    FormatChip("</> Code") { insertMarkdown("```\n", "\n```", "code") }
                    FormatChip("∑ Math") { insertMarkdown("$$\n", "\n$$", "E = mc^2") }
                    FormatChip("x² Math") { insertMarkdown("$", "$", "x^2") }
                    FormatChip("🔗 Link") { insertMarkdown("[title]", "(url)") }
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
                placeholder = { Text("Note title...", color = TextMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold) },
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
                    color = TextPrimary,
                    textDirection = TextDirection.Content
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Divider(color = SurfaceBorder, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

            // Body Input with Smart List/Task auto-continuation
            TextField(
                value = bodyValue,
                onValueChange = { newBody ->
                    bodyValue = handleSmartEnter(bodyValue, newBody)
                },
                placeholder = { Text("Start typing your thoughts and notes here...", color = TextMuted, fontSize = 15.sp) },
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
                    color = TextPrimary,
                    textDirection = TextDirection.Content
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
