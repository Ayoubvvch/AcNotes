package com.acmods.acnotes.data

import android.content.Context

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val avatarEmoji: String = "👤"
)

object UserProfileManager {
    private const val PREFS = "acnotes_user_profile"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_EMOJI = "emoji"

    fun load(context: Context): UserProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return UserProfile(
            name = prefs.getString(KEY_NAME, "") ?: "",
            email = prefs.getString(KEY_EMAIL, "") ?: "",
            avatarEmoji = prefs.getString(KEY_EMOJI, "👤") ?: "👤"
        )
    }

    fun save(context: Context, profile: UserProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, profile.name.trim())
            .putString(KEY_EMAIL, profile.email.trim())
            .putString(KEY_EMOJI, profile.avatarEmoji)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
