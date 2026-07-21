package hr.smocnica.ui

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hr.smocnica.SmocnicaApplication
import hr.smocnica.core.data.messaging.SmocnicaNotificationChannels
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationChannelStartupTest {
    @Test
    fun lowStockChannelExistsAfterApplicationStartup() {
        val application = ApplicationProvider.getApplicationContext<SmocnicaApplication>()
        val manager = application.getSystemService(NotificationManager::class.java)

        assertNotNull(manager.getNotificationChannel(SmocnicaNotificationChannels.LOW_STOCK_CHANNEL_ID))
    }
}
