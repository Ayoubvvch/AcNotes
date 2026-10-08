package com.acmods.acnotes.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.acmods.acnotes.ui.NotesViewModel
import com.acmods.acnotes.ui.theme.*
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
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
            Text("Note not found", color = TextSecondary)
        }
        return
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    if (note.folder.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x1810B981))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "📁 ${note.folder}",
                                color = EmeraldLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    // Share
                    IconButton(onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TITLE, note.title)
                            putExtra(Intent.EXTRA_TEXT, "${note.title}\n\n${note.text}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Note"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }

                    // Delete
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RedDelete.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                    }

                    // Edit
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
                        Text("Edit", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
        ) {
            // Note Title & Date Header in Native Compose
            var swipeOffset by remember { mutableFloatStateOf(0f) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipeOffset > 80f) {
                                    viewModel.navigateBack()
                                }
                                swipeOffset = 0f
                            },
                            onDragCancel = { swipeOffset = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount > 0 || swipeOffset > 0) {
                                    swipeOffset += dragAmount
                                }
                            }
                        )
                    }
            ) {
                Text(
                    text = if (note.title.isNotBlank()) note.title else "Untitled Note",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 32.sp,
                    style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = note.date,
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            Divider(color = SurfaceBorder, thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

            var lastRenderedText by remember(note.id) { mutableStateOf<String?>(null) }

            // High-Fidelity KaTeX & Markdown Reader Surface
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF09090B.toInt())
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                            offscreenPreRaster = true
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun toggleTask(lineIndex: Int, isChecked: Boolean) {
                                viewModel.toggleTaskInNote(note.id, lineIndex, isChecked)
                            }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                lastRenderedText = note.text
                                val jsonStr = JSONObject.quote(note.text)
                                view?.evaluateJavascript("renderMarkdownWithMath($jsonStr)", null)
                            }
                        }

                        loadUrl("file:///android_asset/reader/template.html")
                    }
                },
                update = { webView ->
                    if (lastRenderedText != note.text) {
                        lastRenderedText = note.text
                        val jsonStr = JSONObject.quote(note.text)
                        webView.evaluateJavascript("renderMarkdownWithMath($jsonStr)", null)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Note", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete this note?", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteNote(note.id)
                    }
                ) {
                    Text("Delete", color = RedDelete, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
