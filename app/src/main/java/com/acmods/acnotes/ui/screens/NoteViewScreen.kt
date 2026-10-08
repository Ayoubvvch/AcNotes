package com.acmods.acnotes.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acmods.acnotes.ui.MarkdownNativeView
import com.acmods.acnotes.ui.NotesViewModel
import com.acmods.acnotes.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteViewScreen(
    noteId: Long,
    viewModel: NotesViewModel,
    modifier: Modifier = Modifier
) {
    val notes by viewModel.notes.collectAsState()
    val note = notes.find { it.id == noteId }
    val context = LocalContext.current

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (note == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("الملاحظة غير موجودة", color = TextSecondary)
        }
        return
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = TextPrimary)
                    }
                },
                actions = {
                    // Share Note button
                    IconButton(onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TITLE, note.title)
                            putExtra(Intent.EXTRA_TEXT, "${note.title}\n\n${note.text}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "مشاركة الملاحظة"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "مشاركة", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }

                    // Delete button
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = RedDelete.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                    }

                    // Edit button
                    Button(
                        onClick = { viewModel.editCurrentNote() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = BgDark
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تعديل", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Title
            Text(
                text = if (note.title.isNotBlank()) note.title else "بدون عنوان",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Meta Info: Date + Folder
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = note.date, color = TextMuted, fontSize = 12.sp)

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
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = SurfaceBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // Native Markdown Rendered Body
            if (note.text.isNotBlank()) {
                MarkdownNativeView(
                    content = note.text,
                    onToggleTask = { lineIdx, isChecked ->
                        viewModel.toggleTaskInNote(note.id, lineIdx, isChecked)
                    }
                )
            } else {
                Text(
                    text = "ملاحظة فارغة.",
                    color = TextMuted,
                    fontSize = 14.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("حذف الملاحظة", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("هل أنت متأكد من حذف هذه الملاحظة نهائياً؟", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteNote(note.id)
                    }
                ) {
                    Text("حذف", color = RedDelete, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("إلغاء", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
