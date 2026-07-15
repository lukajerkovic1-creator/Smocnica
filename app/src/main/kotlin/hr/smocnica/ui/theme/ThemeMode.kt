package hr.smocnica.ui.theme

import android.content.Context

enum class ThemeMode(val label: String) {
    SYSTEM("Sustav"),
    LIGHT("Svijetli"),
    DARK("Tamni");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

object ThemePreferences {
    private const val PREFERENCES = "appearance"
    private const val KEY_THEME = "theme_mode"

    fun load(context: Context): ThemeMode = runCatching {
        ThemeMode.valueOf(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_THEME, ThemeMode.SYSTEM.name)
                .orEmpty(),
        )
    }.getOrDefault(ThemeMode.SYSTEM)

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }
}
