package hr.smocnica.core.data.messaging

import com.google.firebase.messaging.FirebaseMessaging
import hr.smocnica.core.data.DeviceIdentity
import hr.smocnica.core.data.remote.FirebaseCallableClient
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRegistration @Inject constructor(
    private val client: FirebaseCallableClient,
    private val identity: DeviceIdentity,
    private val notificationPreferences: NotificationPrivacyPreferences,
    private val tokenProvider: FcmTokenProvider,
) {
    val notificationPrivacyMode: NotificationPrivacyMode
        get() = notificationPreferences.mode

    suspend fun registerCurrentToken() {
        val token = tokenProvider.currentToken()
        registerToken(token)
    }

    suspend fun registerToken(token: String?) {
        register(token, notificationPreferences.mode)
    }

    suspend fun updateNotificationPrivacy(mode: NotificationPrivacyMode) {
        val token = tokenProvider.currentToken()
        register(token, mode)
        notificationPreferences.save(mode)
    }

    private suspend fun register(token: String?, mode: NotificationPrivacyMode) {
        val payload = mutableMapOf<String, Any>(
            "deviceId" to identity.deviceId,
            "deviceDisplayName" to identity.displayName,
            "platform" to "ANDROID",
            "detailedNotifications" to (mode == NotificationPrivacyMode.DETAILED),
        )
        token?.let { payload["fcmToken"] = it }
        client.call("registerDevice", payload)
    }

    suspend fun unregisterCurrentDevice() {
        client.call("unregisterDevice", mapOf("deviceId" to identity.deviceId))
    }
}

@Singleton
class FcmTokenProvider @Inject constructor() {
    suspend fun currentToken(): String? = runCatching {
        FirebaseMessaging.getInstance().token.await()
    }.getOrNull()
}
