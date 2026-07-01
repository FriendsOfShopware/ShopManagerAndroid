package de.shyim.shopware.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import de.shyim.shopware.R

// Notification plumbing shared by the FCM order push (and any future local notifications).
// Order alerts are delivered by FCM (external Shopware app server), not by background polling.
object Notifications {
    const val EXTRA_SHOP_ID = "shopId"
    const val CHANNEL_ORDERS = "orders"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ORDERS,
                context.getString(R.string.notif_channel_orders),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }
}
