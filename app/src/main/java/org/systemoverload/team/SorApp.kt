package org.systemoverload.team

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.systemoverload.team.data.AppModel

class SorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firestore keeps its offline cache on disk by default on Android: the shop Wi-Fi is never great.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notify_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Tasks, comments, action items, reminders and meetings"
        })
        model = AppModel(this)
    }

    companion object {
        const val CHANNEL = "team"
        lateinit var model: AppModel
            private set
    }
}
