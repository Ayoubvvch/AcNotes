package com.acmods.acnotes

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.acmods.acnotes.ui.NotesViewModel
import com.acmods.acnotes.ui.Screen
import com.acmods.acnotes.ui.screens.NoteEditScreen
import com.acmods.acnotes.ui.screens.NoteViewScreen
import com.acmods.acnotes.ui.screens.NotesListScreen
import com.acmods.acnotes.ui.theme.AcNotesTheme
import com.acmods.acnotes.ui.theme.BgDark

class MainActivity : ComponentActivity() {

    private val viewModel: NotesViewModel by viewModels()
    private var lastBackPressTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AcNotesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgDark
                ) {
                    val currentScreen by viewModel.screen.collectAsState()

                    BackHandler {
                        val handled = viewModel.navigateBack()
                        if (!handled) {
                            val now = System.currentTimeMillis()
                            if (now - lastBackPressTime < 2000) {
                                finish()
                            } else {
                                lastBackPressTime = now
                                Toast.makeText(this@MainActivity, "اضغط مرة أخرى للخروج", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = currentScreen,
                        label = "ScreenTransition"
                    ) { screen ->
                        when (screen) {
                            is Screen.List -> {
                                NotesListScreen(viewModel = viewModel)
                            }
                            is Screen.View -> {
                                NoteViewScreen(
                                    noteId = screen.noteId,
                                    viewModel = viewModel
                                )
                            }
                            is Screen.Edit -> {
                                NoteEditScreen(
                                    noteId = screen.noteId,
                                    initialFolder = screen.initialFolder,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
