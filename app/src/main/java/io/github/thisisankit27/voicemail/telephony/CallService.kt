package io.github.thisisankit27.voicemail.telephony

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.thisisankit27.voicemail.R
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.notifications.Notifier
import io.github.thisisankit27.voicemail.ui.call.InCallActivity

/**
 * Foreground service (type phoneCall) held while any call exists. It carries the call notification:
 * the full-screen incoming-call notification (which opens [InCallActivity] over the lock screen) and
 * the ongoing-call notification with its timer and Hang up action.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = CallManager.calls.value
        if (calls.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, Notifier.ID_CALL, build(calls), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (e: Exception) {
            AppLog.w("call foreground service refused", e)
        }
        return START_NOT_STICKY
    }

    private fun build(calls: List<CallUi>): Notification {
        val ringing = calls.firstOrNull { it.phase == CallPhase.RINGING && it.incoming }
        val primary = ringing ?: calls.firstOrNull { it.phase == CallPhase.ACTIVE } ?: calls.first()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val person = Person.Builder()
            .setName(primary.displayName)
            .setImportant(true)
            .apply {
                primary.contact?.photoUri?.let { setIcon(Icon.createWithContentUri(Uri.parse(it))) }
                primary.number?.let { setUri("tel:$it") }
            }
            .build()
        val builder = Notification.Builder(this, if (ringing != null) Notifier.CHANNEL_INCOMING else Notifier.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (ringing != null) {
            val subtitle = when (ringing.voicemail) {
                is VoicemailPhase.Countdown -> "Voicemail will answer if you don't"
                else -> ringing.simLabel?.let { "Incoming call · $it" } ?: "Incoming call"
            }
            builder.setStyle(Notification.CallStyle.forIncomingCall(person, action(ACTION_DECLINE, ringing.id), action(ACTION_ANSWER, ringing.id)))
                .setContentText(subtitle)
                .setFullScreenIntent(open, true)
        } else {
            builder.setStyle(Notification.CallStyle.forOngoingCall(person, action(ACTION_HANGUP, primary.id)))
            val vm = primary.voicemail
            builder.setContentText(
                when {
                    vm is VoicemailPhase.Greeting -> "Voicemail is playing your greeting"
                    vm is VoicemailPhase.Recording -> "Voicemail is recording a message"
                    primary.phase == CallPhase.DIALING || primary.phase == CallPhase.CONNECTING -> "Calling…"
                    primary.phase == CallPhase.HOLDING -> "On hold"
                    calls.size > 1 -> "${calls.size} calls"
                    else -> "Ongoing call"
                },
            )
            if (primary.connectTimeMillis > 0 && primary.phase == CallPhase.ACTIVE) {
                builder.setUsesChronometer(true).setWhen(primary.connectTimeMillis).setShowWhen(true)
            }
        }
        return builder.build()
    }

    private fun action(action: String, callId: String): PendingIntent = PendingIntent.getBroadcast(
        this, action.hashCode() + callId.hashCode(),
        Intent(this, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_CALL_ID, callId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_ANSWER = "io.github.thisisankit27.voicemail.ANSWER"
        const val ACTION_DECLINE = "io.github.thisisankit27.voicemail.DECLINE"
        const val ACTION_HANGUP = "io.github.thisisankit27.voicemail.HANGUP"
        const val EXTRA_CALL_ID = "call_id"

        fun update(context: Context) {
            val intent = Intent(context, CallService::class.java)
            try {
                if (CallManager.calls.value.isEmpty()) context.stopService(intent)
                else ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AppLog.w("could not update the call service", e)
            }
        }
    }
}

/** Handles Answer / Decline / Hang up from the call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(CallService.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            CallService.ACTION_ANSWER -> {
                CallManager.answer(id)
                context.startActivity(Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            CallService.ACTION_DECLINE -> CallManager.reject(id)
            CallService.ACTION_HANGUP -> {
                // If the app's voicemail is handling this call, hanging up just ends it early.
                context.app.engine.session(id)?.cancel("hung up from notification")
                CallManager.hangup(id)
            }
        }
    }
}
