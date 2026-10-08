package com.acmods.acnotes.data

import androidx.compose.runtime.Immutable

fun extractPreview(rawText: String): String {
    if (rawText.isBlank()) return ""
    val head = if (rawText.length > 350) rawText.substring(0, 350) else rawText
    val sb = StringBuilder()
    for (line in head.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isNotBlank() &&
            !trimmed.startsWith("#") &&
            !trimmed.startsWith("---") &&
            !trimmed.startsWith(">") &&
            !trimmed.startsWith("```") &&
            !trimmed.startsWith("$$")
        ) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(trimmed)
            if (sb.length >= 100) break
        }
    }
    return if (sb.length > 110) sb.substring(0, 110) + "…" else sb.toString()
}

@Immutable
data class Note(
    val id: Long,
    val title: String,
    val text: String,
    val preview: String = "",
    val folder: String = "",
    val date: String,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val syncKey: String? = null
)
