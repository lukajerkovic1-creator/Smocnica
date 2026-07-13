package hr.smocnica.core.data

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceIdentity @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    val deviceId: String = preferences.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(KEY_ID, it).apply()
    }

    var displayName: String
        get() = preferences.getString(KEY_NAME, null) ?: Build.MODEL.takeIf(String::isNotBlank) ?: "Android uređaj"
        set(value) {
            require(value.trim().length in 2..40) { "Naziv uređaja mora imati između 2 i 40 znakova." }
            preferences.edit().putString(KEY_NAME, value.trim()).apply()
        }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_NAME = "display_name"
    }
}

fun interface Clock { fun now(): Long }

fun interface IdGenerator { fun next(): String }
