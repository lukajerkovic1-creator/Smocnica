package hr.smocnica.core.data.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object SmocnicaNotificationChannels {
    const val LOW_STOCK_CHANNEL_ID: String = "low_stock"

    fun create(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(LOW_STOCK_CHANNEL_ID, "Niska zaliha", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Obavijesti kada artikl prijeđe ispod minimalne zalihe"
            },
        )
    }
}
