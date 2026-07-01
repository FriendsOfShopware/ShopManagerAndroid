package de.shyim.shopware.push

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.shyim.shopware.MainActivity
import de.shyim.shopware.R
import de.shyim.shopware.ShopwareApp
import de.shyim.shopware.data.sync.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Receives order pushes fanned out by the external Shopware app server and posts a tappable
// notification that deep-links into the order. Also re-registers a rotated token to all shops.
class FcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val repo = (application as ShopwareApp).repository
        scope.launch {
            runCatching { repo.registerPushToken(token, android.os.Build.MODEL ?: "Android") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val n = message.notification
        // Prefer the notification block; fall back to data fields for data-only messages.
        val title = n?.title ?: getString(R.string.push_new_order_title)
        val body = n?.body ?: data["orderNumber"]?.let { "#$it" } ?: ""

        Notifications.ensureChannels(applicationContext)

        // The push carries the shop's APP_URL and the order id; MainActivity matches the
        // connected shop by URL and deep-links to the order detail.
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_PUSH_SHOP_URL, data["shopUrl"])
            putExtra(MainActivity.EXTRA_PUSH_ORDER_ID, data["orderId"])
        }
        val requestCode = (data["orderId"] ?: title).hashCode()
        val pending = PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ORDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(requestCode, notification)
        }
    }

    companion object {
        // Matches SyncWorker's orders channel so the user has one toggle for order alerts.
        private const val CHANNEL_ORDERS = "orders"
    }
}
