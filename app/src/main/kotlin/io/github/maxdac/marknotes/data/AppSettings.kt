package io.github.maxdac.marknotes.data

import android.content.Context

/** Small non-secret preferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var remoteFolder: String
        get() = prefs.getString(KEY_REMOTE_FOLDER, DEFAULT_REMOTE_FOLDER) ?: DEFAULT_REMOTE_FOLDER
        set(value) = prefs.edit().putString(KEY_REMOTE_FOLDER, value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastSyncSummary: String
        get() = prefs.getString(KEY_LAST_SYNC_SUMMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_SUMMARY, value).apply()

    companion object {
        const val DEFAULT_REMOTE_FOLDER = "MarkNotes"
        private const val KEY_REMOTE_FOLDER = "remote_folder"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
    }
}
