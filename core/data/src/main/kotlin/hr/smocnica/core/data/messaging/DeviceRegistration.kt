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
        val token = FirebaseMessaging.getInstance().token.await()
        client.call(
            "registerDevice",
            mapOf(
                "deviceId" to identity.deviceId,
                "deviceDisplayName" to identity.displayName,
                "fcmToken" to token,
                "platform" to "ANDROID",
            ),
        )
    }

    suspend fun unregisterCurrentDevice() {
        client.call("unregisterDevice", mapOf("deviceId" to identity.deviceId))
    }
}
