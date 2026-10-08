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
                                Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = currentScreen,
                        label = "ScreenTransition",
                        transitionSpec = {
                            val targetDepth = targetState.depth()
                            val initialDepth = initialState.depth()
                            if (targetDepth > initialDepth) {
                                // Forward: slide in from right with subtle parallax push
                                (slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(240))).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> -fullWidth / 4 },
                                        animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(180))
                                )
                            } else if (targetDepth < initialDepth) {
                                // Backward: slide out to right, reveal previous from left
                                (slideInHorizontally(
                                    initialOffsetX = { fullWidth -> -fullWidth / 4 },
                                    animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(240))).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> fullWidth },
                                        animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(180))
                                )
                            } else {
                                (fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) + scaleIn(initialScale = 0.98f)).togetherWith(
                                    fadeOut(animationSpec = androidx.compose.animation.core.tween(180)) + scaleOut(targetScale = 0.98f)
                                )
                            }
                        }
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

private fun Screen.depth(): Int = when (this) {
    is Screen.List -> 0
    is Screen.View -> 1
    is Screen.Edit -> 2
}
