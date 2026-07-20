package hr.smocnica.core.data.messaging

import hr.smocnica.core.data.DeviceIdentity
import hr.smocnica.core.data.remote.FirebaseCallableClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DeviceRegistrationTest {
    private val client = mockk<FirebaseCallableClient>()
    private val identity = mockk<DeviceIdentity> {
        every { deviceId } returns "device-1"
        every { displayName } returns "Moj telefon"
    }
    private val preferences = mockk<NotificationPrivacyPreferences>(relaxed = true)
    private val tokenProvider = mockk<FcmTokenProvider>()

    @Test
    fun `private mode is sent with every token registration`() = runTest {
        every { preferences.mode } returns NotificationPrivacyMode.PRIVATE
        coEvery { client.call("registerDevice", any()) } returns emptyMap()
        val registration = DeviceRegistration(client, identity, preferences, tokenProvider)

        registration.registerToken("token-1")

        coVerify(exactly = 1) {
            client.call("registerDevice", match {
                it["deviceId"] == "device-1" &&
                    it["fcmToken"] == "token-1" &&
                    it["detailedNotifications"] == false
            })
        }
    }

    @Test
    fun `preference is persisted only after server confirms the change`() = runTest {
        every { preferences.mode } returns NotificationPrivacyMode.PRIVATE
        coEvery { tokenProvider.currentToken() } returns "token-2"
        coEvery { client.call("registerDevice", any()) } returns emptyMap()
        val registration = DeviceRegistration(client, identity, preferences, tokenProvider)

        registration.updateNotificationPrivacy(NotificationPrivacyMode.DETAILED)

        coVerify { client.call("registerDevice", match { it["detailedNotifications"] == true }) }
        verify { preferences.save(NotificationPrivacyMode.DETAILED) }
    }

    @Test(expected = IllegalStateException::class)
    fun `failed server update does not persist a less private setting`() = runTest {
        every { preferences.mode } returns NotificationPrivacyMode.PRIVATE
        coEvery { tokenProvider.currentToken() } returns "token-3"
        coEvery { client.call("registerDevice", any()) } throws IllegalStateException("offline")
        val registration = DeviceRegistration(client, identity, preferences, tokenProvider)

        try {
            registration.updateNotificationPrivacy(NotificationPrivacyMode.DETAILED)
        } finally {
            verify(exactly = 0) { preferences.save(any()) }
        }
    }
}
