package io.github.thisisankit27.voicemail.probe

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.io.File

/**
 * Bound by Telecom only while the probe holds ROLE_DIALER and a call exists.
 * Logs every callback (E1/E2) and, when auto-answer is configured, runs an [AutoAnswerSession].
 */
class ProbeInCallService : InCallService() {
    private val sessions = mutableMapOf<Call, AutoAnswerSession>()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            ProbeLog.i("onStateChanged -> ${stateName(state)} | ${call.describe()}")
            sessions[call]?.onStateChanged(state)
            CallRegistry.changed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProbeLog.i("InCallService created (bound by Telecom)")
    }

    override fun onDestroy() {
        instance = null
        ProbeLog.i("InCallService destroyed (unbound)")
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        val config = ProbeConfig(this)
        ProbeLog.i("onCallAdded ${call.describe()} | ${config.summary()}")
        CallRegistry.add(call)
        call.registerCallback(callback)
        ProbeCallService.start(this)
        showUi()
        if (call.stateCompat() == Call.STATE_RINGING && config.autoAnswerSeconds > 0) {
            sessions[call] = AutoAnswerSession(call, this, config).also { it.arm() }
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        sessions.remove(call)?.stop("call removed")
        CallRegistry.remove(call)
        ProbeLog.i("onCallRemoved; remaining=${CallRegistry.calls.value.size}")
        if (CallRegistry.calls.value.isEmpty()) ProbeCallService.stop(this)
    }

    @Deprecated("Deprecated in API 34; the probe targets the Android 12 G60")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        ProbeLog.i("audio route=${CallAudioState.audioRouteToString(audioState.route)} muted=${audioState.isMuted}")
    }

    fun showUi() {
        try {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            ProbeLog.e("could not start InCallActivity", e)
        }
    }

    @Suppress("DEPRECATION")
    fun routeToSpeaker(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        ProbeLog.i("requested audio route ${if (on) "SPEAKER" else "EARPIECE"}")
    }

    /** E4 variant: capture during a call the user answered by hand. */
    fun manualCapture(call: Call) {
        val config = ProbeConfig(this)
        val file = File(CaptureProbe.captureDir(this), "manual-${ProbeConfig.sourceName(config.captureSource)}-${System.currentTimeMillis()}.wav")
        CaptureProbe(this, config.captureSource, file).start(config.captureSeconds, "E4(manual)") { }
        ProbeLog.i("E4(manual) started on ${call.describe()}")
    }

    companion object {
        @Volatile var instance: ProbeInCallService? = null
            private set
    }
}

/**
 * One experiment run: ring timer → answer() → speaker → greeting → beep → capture → disconnect.
 * Every step logs a timestamp relative to when the call started ringing.
 */
class AutoAnswerSession(
    private val call: Call,
    private val service: ProbeInCallService,
    private val config: ProbeConfig,
) {
    private val main = Handler(Looper.getMainLooper())
    private val ringStartedAt = SystemClock.elapsedRealtime()
    private var answeredByProbe = false
    private var greetingStarted = false
    private var player: GreetingPlayer? = null
    private var capture: CaptureProbe? = null

    private val answer = Runnable {
        if (call.stateCompat() == Call.STATE_RINGING) {
            ProbeLog.i("E2 timer fired at ${elapsed()} ms -> answer(STATE_AUDIO_ONLY)")
            answeredByProbe = true
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } else {
            ProbeLog.i("E2 timer fired but call is ${stateName(call.stateCompat())}; not answering")
        }
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - ringStartedAt

    fun arm() {
        main.postDelayed(answer, config.autoAnswerSeconds * 1000L)
        ProbeLog.i("E2 armed: auto-answer in ${config.autoAnswerSeconds} s")
    }

    fun onStateChanged(state: Int) {
        when (state) {
            Call.STATE_RINGING -> Unit
            Call.STATE_ACTIVE -> if (answeredByProbe && !greetingStarted) {
                greetingStarted = true
                ProbeLog.i("E2 call ACTIVE at ${elapsed()} ms")
                if (config.speakerOnAnswer) service.routeToSpeaker(true)
                // Give the route change a moment to settle before playing.
                main.postDelayed({ playGreeting() }, 800)
            } else if (!answeredByProbe) {
                main.removeCallbacks(answer)
                ProbeLog.i("E2 answered by user at ${elapsed()} ms; session cancelled")
            }
            Call.STATE_DISCONNECTED -> stop("disconnected at ${elapsed()} ms (answeredByProbe=$answeredByProbe)")
            else -> main.removeCallbacks(answer)
        }
    }

    private fun playGreeting() {
        if (call.stateCompat() != Call.STATE_ACTIVE) return
        player = GreetingPlayer(service, config.greetingUsage).also { p ->
            p.play {
                if (call.stateCompat() != Call.STATE_ACTIVE) return@play
                p.beep { startCapture() }
            }
        }
    }

    private fun startCapture() {
        if (call.stateCompat() != Call.STATE_ACTIVE) return
        val source = ProbeConfig.sourceName(config.captureSource)
        val file = File(CaptureProbe.captureDir(service), "call-$source-${System.currentTimeMillis()}.wav")
        capture = CaptureProbe(service, config.captureSource, file).also {
            it.start(config.captureSeconds, "E4") {
                main.post {
                    if (call.stateCompat() == Call.STATE_ACTIVE) {
                        ProbeLog.i("E6 capture finished; disconnect() at ${elapsed()} ms")
                        call.disconnect()
                    }
                }
            }
        }
    }

    fun stop(reason: String) {
        main.removeCallbacks(answer)
        player?.release()
        capture?.stop()
        ProbeLog.i("session stopped: $reason")
    }
}
