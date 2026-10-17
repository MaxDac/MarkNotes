package io.github.maxdac.marknotes.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

/** App accent colour. [DYNAMIC] follows the wallpaper (Material You) and needs Android 12+. */
enum class ThemeColor(val label: String) {
    DYNAMIC("Dynamic"),
    BLUE("Blue"),
    TEAL("Teal"),
    GREEN("Green"),
    LIME("Lime"),
    AMBER("Amber"),
    ORANGE("Orange"),
    RED("Red"),
    PURPLE("Purple")
}

/** Small non-secret preferences. */
class AppSettings(context: Context, private val dynamicColorSupported: Boolean) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Folder of the legacy single Proton Drive setup; migrated into the Proton drive account. */
    val legacyRemoteFolder: String
        get() = prefs.getString(KEY_REMOTE_FOLDER, DEFAULT_REMOTE_FOLDER) ?: DEFAULT_REMOTE_FOLDER

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastSyncSummary: String
        get() = prefs.getString(KEY_LAST_SYNC_SUMMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_SUMMARY, value).apply()

    /** Stable random id of this installation, written to the drive manifests. */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private val _themeMode = MutableStateFlow(
        enumValueOrNull<ThemeMode>(prefs.getString(KEY_THEME_MODE, null)) ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _themeColor = MutableStateFlow(
        enumValueOrNull<ThemeColor>(prefs.getString(KEY_THEME_COLOR, null))
            ?.takeIf { it != ThemeColor.DYNAMIC || dynamicColorSupported }
            ?: if (dynamicColorSupported) ThemeColor.DYNAMIC else ThemeColor.BLUE
    )
    val themeColor: StateFlow<ThemeColor> = _themeColor

    /** Colours offered in the settings; Dynamic needs Android 12+. */
    val availableColors: List<ThemeColor>
        get() = ThemeColor.entries.filter { it != ThemeColor.DYNAMIC || dynamicColorSupported }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setThemeColor(color: ThemeColor) {
        prefs.edit().putString(KEY_THEME_COLOR, color.name).apply()
        _themeColor.value = color
    }

    companion object {
        const val DEFAULT_REMOTE_FOLDER = "MarkNotes"
        private const val KEY_REMOTE_FOLDER = "remote_folder"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR = "theme_color"

        private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}
