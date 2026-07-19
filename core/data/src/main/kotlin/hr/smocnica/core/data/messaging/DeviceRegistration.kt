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
) {
    suspend fun registerCurrentToken() {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
        val payload = mutableMapOf<String, Any>(
            "deviceId" to identity.deviceId,
            "deviceDisplayName" to identity.displayName,
            "platform" to "ANDROID",
        )
        token?.let { payload["fcmToken"] = it }
        client.call(
            "registerDevice",
            payload,
        )
    }

    suspend fun unregisterCurrentDevice() {
        client.call("unregisterDevice", mapOf("deviceId" to identity.deviceId))
    }
}
