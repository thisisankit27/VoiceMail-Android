package io.github.thisisankit27.voicemail.probe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Calls (probe)", NotificationManager.IMPORTANCE_HIGH),
        )
        ProbeLog.i("process started")
    }

    companion object {
        const val CHANNEL_CALL = "probe_call"
    }
}
