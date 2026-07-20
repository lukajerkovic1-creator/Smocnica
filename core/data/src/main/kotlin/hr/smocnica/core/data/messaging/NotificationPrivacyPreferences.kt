package hr.smocnica.core.data.messaging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class NotificationPrivacyMode {
    PRIVATE,
    DETAILED,
}

@Singleton
class NotificationPrivacyPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val mode: NotificationPrivacyMode
        get() = runCatching {
            NotificationPrivacyMode.valueOf(
                preferences.getString(KEY_MODE, NotificationPrivacyMode.PRIVATE.name).orEmpty(),
            )
        }.getOrDefault(NotificationPrivacyMode.PRIVATE)

    fun save(mode: NotificationPrivacyMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFERENCES = "notification_privacy"
        const val KEY_MODE = "notification_privacy_mode"
    }
}
