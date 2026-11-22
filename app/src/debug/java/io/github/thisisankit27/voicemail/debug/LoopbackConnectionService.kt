package io.github.thisisankit27.voicemail.debug

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.core.redactNumber
import java.io.File

/**
 * A network-free "calling account". [TelecomManager.addNewIncomingCall] on this account makes
 * Telecom create a managed incoming call. It rings, the default dialer's InCallService (this
 * app) is bound, and the audio mode goes to MODE_IN_CALL, all without the modem.
 *
 * What it is NOT: there is no far end. Nobody hears the uplink, so E3 cannot be tested this way.
 * The "caller's voice" is a proxy: [LoopbackConnection] plays a clip through the call audio
 * route, standing in for the downlink.
 */
class LoopbackConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras
        val fromExtras = extras?.getString(EXTRA_SCENARIO)
            ?: extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString(EXTRA_SCENARIO)
        val scenario = fromExtras ?: pendingScenario ?: SCENARIO_MESSAGE
        AppLog.i("LOOPBACK scenario source=${if (fromExtras != null) "extras" else "in-process handoff"} extrasKeys=${extras?.keySet()}")
        pendingScenario = null
        val address = request.address ?: Uri.fromParts(PhoneAccount.SCHEME_TEL, FAKE_NUMBER, null)
        AppLog.i("LOOPBACK onCreateIncomingConnection scenario=$scenario number=${redactNumber(address.schemeSpecificPart)}")
        return LoopbackConnection(applicationContext, scenario).apply {
            setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName("Loopback caller", TelecomManager.PRESENTATION_ALLOWED)
            connectionCapabilities = Connection.CAPABILITY_MUTE
            audioModeIsVoip = false // behave like a carrier call → Telecom uses MODE_IN_CALL
            setRinging()
            onRinging()
        }
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        AppLog.i("LOOPBACK onCreateIncomingConnectionFailed")
    }

    companion object {
        const val ACCOUNT_ID = "loopback"
        const val EXTRA_SCENARIO = "voicemail.scenario"
        const val FAKE_NUMBER = "+910000000042"

        @Volatile private var pendingScenario: String? = null

        /** Caller waits for the greeting + beep, speaks the caller clip, then hangs up. */
        const val SCENARIO_MESSAGE = "message"
        /** Caller hangs up after 8 s of ringing (Test 4). */
        const val SCENARIO_HANGUP_RINGING = "hangup_ringing"
        /** Caller stays silent after the beep and hangs up (Test 10). */
        const val SCENARIO_SILENT = "silent"

        fun handle(context: Context) =
            PhoneAccountHandle(ComponentName(context, LoopbackConnectionService::class.java), ACCOUNT_ID)

        fun register(context: Context) {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val account = PhoneAccount.builder(handle(context), "Voicemail loopback (no network)")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .setShortDescription("Test calls; never touches the network")
                .build()
            try {
                telecom.registerPhoneAccount(account)
                val enabled = runCatching { telecom.getPhoneAccount(handle(context))?.isEnabled }.getOrNull()
                AppLog.i("LOOPBACK account registered enabled=$enabled (enable via Settings → Calling accounts, or adb telecom set-phone-account-enabled)")
            } catch (e: Exception) {
                AppLog.w("LOOPBACK registerPhoneAccount failed", e)
            }
        }

        fun placeIncoming(context: Context, scenario: String) {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_TEL, FAKE_NUMBER, null))
                putString(EXTRA_SCENARIO, scenario)
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_SCENARIO, scenario) })
            }
            // Telecom may drop or repackage extras; the service runs in this process, so hand off directly too.
            pendingScenario = scenario
            try {
                telecom.addNewIncomingCall(handle(context), extras)
                AppLog.i("LOOPBACK addNewIncomingCall scenario=$scenario")
            } catch (e: Exception) {
                AppLog.w("LOOPBACK addNewIncomingCall refused", e)
            }
        }
    }
}

/** The fake far end. Its script stands in for a person on another phone. */
class LoopbackConnection(private val context: Context, private val scenario: String) : Connection() {
    private val main = Handler(Looper.getMainLooper())
    private var voice: MediaPlayer? = null

    fun onRinging() {
        if (scenario == LoopbackConnectionService.SCENARIO_HANGUP_RINGING) {
            main.postDelayed({
                if (state == STATE_RINGING) {
                    AppLog.i("LOOPBACK caller hangs up while ringing (8 s)")
                    remoteHangup()
                }
            }, 8_000)
        }
    }

    override fun onAnswer(videoState: Int) = answered()
    override fun onAnswer() = answered()

    private fun answered() {
        AppLog.i("LOOPBACK onAnswer -> setActive (scenario=$scenario)")
        setActive()
        when (scenario) {
            // Greeting (~7 s) + beep start ~0.8 s after ACTIVE; the caller starts speaking at 10 s.
            LoopbackConnectionService.SCENARIO_MESSAGE -> main.postDelayed({ speak() }, 10_000)
            LoopbackConnectionService.SCENARIO_SILENT -> main.postDelayed({ remoteHangup() }, 14_000)
            else -> Unit
        }
    }

    /** Proxy for the downlink: play the caller clip on the call's audio route. */
    private fun speak() {
        if (state != STATE_ACTIVE) return
        val clip = callerFile(context)
        if (!clip.exists()) {
            AppLog.i("LOOPBACK no caller clip (run render_caller); caller stays silent")
            main.postDelayed({ remoteHangup() }, 4_000)
            return
        }
        try {
            voice = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(clip.path)
                setOnCompletionListener {
                    AppLog.i("LOOPBACK caller finished speaking; hangs up in 2 s")
                    main.postDelayed({ remoteHangup() }, 2_000)
                }
                prepare()
                start()
            }
            AppLog.i("LOOPBACK caller speaking (proxy downlink) durationMs=${voice?.duration}")
        } catch (e: Exception) {
            AppLog.w("LOOPBACK caller clip failed", e)
            remoteHangup()
        }
    }

    private fun remoteHangup() {
        if (state == STATE_DISCONNECTED) return
        release()
        setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
        destroy()
        AppLog.i("LOOPBACK remote hangup")
    }

    override fun onReject() {
        AppLog.i("LOOPBACK onReject")
        release()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        AppLog.i("LOOPBACK onDisconnect (local hangup)")
        release()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() = onDisconnect()

    @Deprecated("Deprecated in API 34")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        AppLog.i("LOOPBACK audio route=${CallAudioState.audioRouteToString(state.route)}")
    }

    private fun release() {
        main.removeCallbacksAndMessages(null)
        voice?.release()
        voice = null
    }

    companion object {
        fun callerFile(context: Context) = File(context.filesDir, "caller.wav")
    }
}
