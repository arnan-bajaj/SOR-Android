package org.systemoverload.team

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.systemoverload.team.data.savePushToken

/**
 * Push from the Slack bot (slack-bot/lib/push.mjs), through Firebase Cloud Messaging. In the background Android shows
 * the notification itself and tapping it opens the app with the `path` extra; while the app is open, we show it here.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Handler(Looper.getMainLooper()).post { SorApp.model.savePushToken(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val n = msg.notification ?: return
        val path = msg.data["path"] ?: "/"
        val open = Intent(this, MainActivity::class.java).putExtra("path", path).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, path.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(this, SorApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(path.hashCode(), note) }
    }
}
