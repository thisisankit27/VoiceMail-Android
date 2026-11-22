package io.github.thisisankit27.voicemail.probe

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service held while any call exists. It carries the full-screen incoming-call
 * notification and gives the process foreground status for microphone access. Whether Android
 * lets it start from the InCallService context (locked, app in background) is experiment E5,
 * so every failure is logged rather than hidden.
 */
class ProbeCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
            ProbeLog.i("E5 foreground service started (phoneCall|microphone)")
        } catch (e: Exception) {
            ProbeLog.e("E5 startForeground refused", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProbeLog.i("E5 foreground service stopped")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, ProbeApp.CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Telecom probe: call in progress")
            .setContentText("Tap to open the probe's in-call screen")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ProbeCallService::class.java))
            } catch (e: Exception) {
                ProbeLog.e("E5 startForegroundService refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProbeCallService::class.java))
        }
    }
}
