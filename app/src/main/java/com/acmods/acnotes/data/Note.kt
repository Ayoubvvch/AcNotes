package com.acmods.acnotes.data

import androidx.compose.runtime.Immutable

fun extractPreview(rawText: String): String {
    if (rawText.isBlank()) return ""
    if (rawText.contains("\"type\":\"excalidraw\"") || rawText.contains("\"type\": \"excalidraw\"")) {
        return "🎨 Interactive Excalidraw Whiteboard (View-only)"
    }
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

fun getUniqueTitle(desiredTitle: String, folder: String, existingNotes: List<Note>, excludeId: Long? = null, isWhiteboard: Boolean = false): String {
    val normFolder = if (folder.isNotBlank() && !folder.equals("home", ignoreCase = true) && !folder.equals("uncategorized", ignoreCase = true)) folder.trim() else ""
    var baseTitle = desiredTitle.trim()
    if (baseTitle.isBlank()) {
        baseTitle = if (isWhiteboard) "Untitled Whiteboard" else "Untitled Note"
    }

    val match = Regex("^(.*?)\\s*\\((\\d+)\\)$").find(baseTitle)
    val rootTitle = match?.groupValues?.get(1)?.trim() ?: baseTitle

    val sameFolderNotes = existingNotes.filter { n ->
        if (excludeId != null && n.id == excludeId) return@filter false
        val nFolder = if (n.folder.isNotBlank() && !n.folder.equals("home", ignoreCase = true) && !n.folder.equals("uncategorized", ignoreCase = true)) n.folder.trim() else ""
        nFolder.equals(normFolder, ignoreCase = true)
    }

    val existingTitles = sameFolderNotes.map { it.title.trim().lowercase() }.toSet()

    if (!existingTitles.contains(baseTitle.lowercase())) {
        return baseTitle
    }

    var counter = 1
    while (existingTitles.contains("${rootTitle} ($counter)".lowercase())) {
        counter++
    }
    return "$rootTitle ($counter)"
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
) {
    val isWhiteboard: Boolean
        get() = text.contains("\"type\":\"excalidraw\"") || text.contains("\"type\": \"excalidraw\"")
}
