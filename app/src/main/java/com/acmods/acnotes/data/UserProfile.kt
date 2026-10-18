package com.acmods.acnotes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val avatarEmoji: String = "👤",
    val avatarImagePath: String = ""
)

object UserProfileManager {
    private const val PREFS = "acnotes_user_profile"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_EMOJI = "emoji"
    private const val KEY_IMAGE_PATH = "image_path"
    const val AVATAR_FILE = "profile_avatar.jpg"

    fun getLocalAvatarFile(context: Context): File {
        return File(context.filesDir, AVATAR_FILE)
    }

    fun load(context: Context): UserProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = getLocalAvatarFile(context)
        val imagePath = if (file.exists() && file.length() > 0) file.absolutePath else (prefs.getString(KEY_IMAGE_PATH, "") ?: "")
        return UserProfile(
            name = prefs.getString(KEY_NAME, "") ?: "",
            email = prefs.getString(KEY_EMAIL, "") ?: "",
            avatarEmoji = prefs.getString(KEY_EMOJI, "👤") ?: "👤",
            avatarImagePath = imagePath
        )
    }

    fun save(context: Context, profile: UserProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, profile.name.trim())
            .putString(KEY_EMAIL, profile.email.trim())
            .putString(KEY_EMOJI, profile.avatarEmoji)
            .putString(KEY_IMAGE_PATH, profile.avatarImagePath)
            .apply()
    }

    fun saveAvatarFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val original = BitmapFactory.decodeStream(stream) ?: return null
                val minEdge = minOf(original.width, original.height)
                val x = (original.width - minEdge) / 2
                val y = (original.height - minEdge) / 2
                val cropped = Bitmap.createBitmap(original, x, y, minEdge, minEdge)
                val scaled = Bitmap.createScaledBitmap(cropped, 384, 384, true)

                val dest = getLocalAvatarFile(context)
                FileOutputStream(dest).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.flush()
                }
                dest.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        val file = getLocalAvatarFile(context)
        if (file.exists()) file.delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
