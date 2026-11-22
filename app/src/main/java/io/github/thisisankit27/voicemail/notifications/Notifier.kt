package io.github.thisisankit27.voicemail.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.thisisankit27.voicemail.R
import io.github.thisisankit27.voicemail.contacts.ContactLookup
import io.github.thisisankit27.voicemail.data.Voicemail
import io.github.thisisankit27.voicemail.ui.MainActivity

/** Notification channels and the non-call notifications (new voicemail, voicemail paused). */
class Notifier(private val context: Context, private val contacts: ContactLookup) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    // Telecom plays the ringtone; the channel itself stays silent to avoid double ringing.
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(CHANNEL_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_VOICEMAIL, "New voicemail", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A caller left you a message"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_SERVICE, "Voicemail status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when voicemail can't answer calls, and why"
                    setSound(null, null)
                },
            ),
        )
    }

    /** POST_NOTIFICATIONS is only a runtime permission on Android 13+; this covers both cases. */
    private fun canPost() = nm.areNotificationsEnabled()

    fun newVoicemail(v: Voicemail) {
        if (!canPost()) return
        val who = v.callerName ?: contacts.lookup(v.callerNumber)?.name ?: contacts.format(v.callerNumber)
        val seconds = v.durationMs / 1000
        val open = PendingIntent.getActivity(
            context, v.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_VOICEMAIL_ID, v.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = Notification.Builder(context, CHANNEL_VOICEMAIL)
            .setSmallIcon(R.drawable.ic_stat_voicemail)
            .setContentTitle("New voicemail")
            .build()
        val n = Notification.Builder(context, CHANNEL_VOICEMAIL)
            .setSmallIcon(R.drawable.ic_stat_voicemail)
            .setContentTitle("Voicemail from $who")
            .setContentText("${seconds / 60}:%02d · Tap to listen".format(seconds % 60))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(v.id.hashCode(), n)
    }

    fun cancelVoicemail(id: String) = nm.cancel(id.hashCode())

    /** Voicemail can't answer: the audio helper isn't running. Tapping opens the status screen. */
    fun helperDown(reason: String) {
        if (!canPost()) return
        val open = PendingIntent.getActivity(
            context, ID_HELPER,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID_HELPER,
            Notification.Builder(context, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_voicemail)
                .setContentTitle("Voicemail is paused")
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(reason))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun helperUp() = nm.cancel(ID_HELPER)

    companion object {
        const val CHANNEL_INCOMING = "incoming_calls"
        const val CHANNEL_ONGOING = "ongoing_calls"
        const val CHANNEL_VOICEMAIL = "voicemail"
        const val CHANNEL_SERVICE = "voicemail_status"
        const val ID_CALL = 1
        const val ID_HELPER = 2
    }
}
