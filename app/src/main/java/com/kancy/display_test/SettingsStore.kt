package com.kancy.display_test

import android.content.Context

/**
 * Persists app-side UI preferences across launches.
 *
 * Backed by [android.content.SharedPreferences] — chosen over DataStore because it needs no extra
 * dependency and the preference set is tiny. Writes are committed asynchronously via apply().
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    fun setBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "display_app_settings"
    }
}
