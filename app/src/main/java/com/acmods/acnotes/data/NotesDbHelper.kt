package com.acmods.acnotes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NotesDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "acnotes.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_NOTES = "notes"
        private const val TABLE_FOLDERS = "folders"
        private const val TABLE_DELETED = "deleted_notes"

        private const val COL_ID = "id"
        private const val COL_TITLE = "title"
        private const val COL_TEXT = "text"
        private const val COL_FOLDER = "folder"
        private const val COL_DATE = "date"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_IS_PINNED = "is_pinned"
        private const val COL_SYNC_KEY = "sync_key"
        private const val COL_FOLDER_NAME = "name"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NOTES (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_FOLDER TEXT DEFAULT '',
                $COL_DATE TEXT NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_IS_PINNED INTEGER DEFAULT 0,
                $COL_SYNC_KEY TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_FOLDERS (
                $COL_FOLDER_NAME TEXT PRIMARY KEY
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_DELETED (
                $COL_ID INTEGER PRIMARY KEY
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FOLDERS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DELETED")
        onCreate(db)
    }

    @Synchronized
    fun getAllNotes(): List<Note> {
        val list = mutableListOf<Note>()
        val db = readableDatabase
        val cursor = db.query(TABLE_NOTES, null, null, null, null, null, "$COL_IS_PINNED DESC, $COL_UPDATED_AT DESC")
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_TITLE)
            val textIdx = it.getColumnIndexOrThrow(COL_TEXT)
            val folderIdx = it.getColumnIndexOrThrow(COL_FOLDER)
            val dateIdx = it.getColumnIndexOrThrow(COL_DATE)
            val updatedIdx = it.getColumnIndexOrThrow(COL_UPDATED_AT)
            val pinIdx = it.getColumnIndexOrThrow(COL_IS_PINNED)
            val syncIdx = it.getColumnIndexOrThrow(COL_SYNC_KEY)

            while (it.moveToNext()) {
                list.add(
                    Note(
                        id = it.getLong(idIdx),
                        title = it.getString(titleIdx) ?: "",
                        text = it.getString(textIdx) ?: "",
                        folder = it.getString(folderIdx) ?: "",
                        date = it.getString(dateIdx) ?: "",
                        updatedAt = it.getLong(updatedIdx),
                        isPinned = it.getInt(pinIdx) == 1,
                        syncKey = it.getString(syncIdx)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun getNote(id: Long): Note? {
        val db = readableDatabase
        val cursor = db.query(TABLE_NOTES, null, "$COL_ID = ?", arrayOf(id.toString()), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return Note(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    title = it.getString(it.getColumnIndexOrThrow(COL_TITLE)) ?: "",
                    text = it.getString(it.getColumnIndexOrThrow(COL_TEXT)) ?: "",
                    folder = it.getString(it.getColumnIndexOrThrow(COL_FOLDER)) ?: "",
                    date = it.getString(it.getColumnIndexOrThrow(COL_DATE)) ?: "",
                    updatedAt = it.getLong(it.getColumnIndexOrThrow(COL_UPDATED_AT)),
                    isPinned = it.getInt(it.getColumnIndexOrThrow(COL_IS_PINNED)) == 1,
                    syncKey = it.getString(it.getColumnIndexOrThrow(COL_SYNC_KEY))
                )
            }
        }
        return null
    }

    @Synchronized
    fun saveNote(note: Note) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_ID, note.id)
            put(COL_TITLE, note.title)
            put(COL_TEXT, note.text)
            put(COL_FOLDER, note.folder)
            put(COL_DATE, note.date)
            put(COL_UPDATED_AT, note.updatedAt)
            put(COL_IS_PINNED, if (note.isPinned) 1 else 0)
            put(COL_SYNC_KEY, note.syncKey)
        }
        db.insertWithOnConflict(TABLE_NOTES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun saveAll(notes: List<Note>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (note in notes) {
                val cv = ContentValues().apply {
                    put(COL_ID, note.id)
                    put(COL_TITLE, note.title)
                    put(COL_TEXT, note.text)
                    put(COL_FOLDER, note.folder)
                    put(COL_DATE, note.date)
                    put(COL_UPDATED_AT, note.updatedAt)
                    put(COL_IS_PINNED, if (note.isPinned) 1 else 0)
                    put(COL_SYNC_KEY, note.syncKey)
                }
                db.insertWithOnConflict(TABLE_NOTES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteNote(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_NOTES, "$COL_ID = ?", arrayOf(id.toString()))
            val cv = ContentValues().apply { put(COL_ID, id) }
            db.insertWithOnConflict(TABLE_DELETED, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun togglePin(id: Long, isPinned: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply { put(COL_IS_PINNED, if (isPinned) 1 else 0) }
        db.update(TABLE_NOTES, cv, "$COL_ID = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun getFolders(): List<String> {
        val list = mutableListOf<String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_FOLDERS, arrayOf(COL_FOLDER_NAME), null, null, null, null, "$COL_FOLDER_NAME ASC")
        cursor.use {
            val nameIdx = it.getColumnIndexOrThrow(COL_FOLDER_NAME)
            while (it.moveToNext()) {
                list.add(it.getString(nameIdx))
            }
        }
        return list
    }

    @Synchronized
    fun addFolder(name: String) {
        if (name.isBlank()) return
        val db = writableDatabase
        val cv = ContentValues().apply { put(COL_FOLDER_NAME, name.trim()) }
        db.insertWithOnConflict(TABLE_FOLDERS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun deleteFolder(name: String) {
        val db = writableDatabase
        db.delete(TABLE_FOLDERS, "$COL_FOLDER_NAME = ?", arrayOf(name))
        // move notes in this folder to Home
        val cv = ContentValues().apply { put(COL_FOLDER, "") }
        db.update(TABLE_NOTES, cv, "$COL_FOLDER = ?", arrayOf(name))
    }

    @Synchronized
    fun getDeletedIds(): Set<Long> {
        val set = mutableSetOf<Long>()
        val db = readableDatabase
        val cursor = db.query(TABLE_DELETED, arrayOf(COL_ID), null, null, null, null, null)
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            while (it.moveToNext()) {
                set.add(it.getLong(idIdx))
            }
        }
        return set
    }
}
