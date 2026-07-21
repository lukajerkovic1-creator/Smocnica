package hr.smocnica.core.data.messaging

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmocnicaMessagingService : FirebaseMessagingService() {
    @Inject lateinit var deviceRegistration: DeviceRegistration
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        serviceScope.launch {
            runCatching { deviceRegistration.registerToken(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra("pantryId", message.data["pantryId"])
            putExtra("productId", message.data["productId"])
            putExtra("destination", message.data["destination"])
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, SmocnicaNotificationChannels.LOW_STOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(message.notification?.title ?: "Smočnica")
            .setContentText(message.notification?.body ?: "Artikl je pao ispod minimalne zalihe.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }
}
