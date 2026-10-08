package com.acmods.acnotes.data

data class Note(
    val id: Long,
    val title: String,
    val text: String,
    val folder: String = "",
    val date: String,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val syncKey: String? = null
)
