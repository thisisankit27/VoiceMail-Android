package io.github.thisisankit27.voicemail.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * ADB-drivable actions, shared by [MainActivity] (foreground) and [ProbeCommandReceiver]
 * (background / locked, for E5). The receiver is guarded by android.permission.DUMP, so only the
 * shell or the system can send to it: no other app on the device can trigger fake calls.
 */
object ProbeActions {
    fun run(context: Context, intent: Intent): Boolean {
        val action = intent.getStringExtra("probe_action") ?: return false
        val config = ProbeConfig(context)
        ProbeLog.i("action: $action")
        when (action) {
            "status" -> ProbeLog.i("config: ${config.summary()} inCallServiceBound=${ProbeInCallService.instance != null}")
            "tts_voices" -> TtsProbe.run(context, render = null)
            "render_greeting" -> TtsProbe.run(context, render = TtsProbe.Render.GREETING)
            "render_caller" -> TtsProbe.run(context, render = TtsProbe.Render.CALLER)
            "play_greeting" -> GreetingPlayer(context, config.greetingUsage).let { p -> p.play { p.beep { p.release() } } }
            "mic_baseline_all" -> micBaselineAll(context)
            // Loophole check: a capture started BEFORE a call. Is it silenced once the call starts?
            "capture_long" -> {
                val name = ProbeConfig.sourceName(config.captureSource)
                val file = File(CaptureProbe.captureDir(context), "long-$name-${System.currentTimeMillis()}.wav")
                CaptureProbe(context, config.captureSource, file).start(intent.getIntExtra("value", 40), "E4(pre-call)") { }
            }
            "register_loopback" -> LoopbackConnectionService.register(context)
            "fake_incoming" -> LoopbackConnectionService.placeIncoming(
                context,
                intent.getStringExtra("scenario") ?: LoopbackConnectionService.SCENARIO_MESSAGE,
            )
            "set_auto_answer" -> config.autoAnswerSeconds = intent.getIntExtra("value", 0)
            "set_capture_seconds" -> config.captureSeconds = intent.getIntExtra("value", config.captureSeconds)
            "set_source" -> config.captureSource = intent.getIntExtra("value", config.captureSource)
            "set_usage" -> config.greetingUsage = intent.getIntExtra("value", config.greetingUsage)
            "set_speaker" -> config.speakerOnAnswer = intent.getBooleanExtra("value", true)
            else -> ProbeLog.i("unknown action")
        }
        if (action.startsWith("set_")) ProbeLog.i("config: ${config.summary()}")
        return true
    }

    /** E4 baseline: noise floor and silencing state of every source, with no call active. */
    fun micBaselineAll(context: Context) {
        thread(name = "baseline") {
            for (source in ProbeConfig.SOURCES) {
                val file = File(CaptureProbe.captureDir(context), "baseline-${ProbeConfig.sourceName(source)}.wav")
                val done = CountDownLatch(1)
                CaptureProbe(context, source, file).start(3, "E4(baseline)") { done.countDown() }
                done.await()
            }
            ProbeLog.i("E4(baseline) all sources done")
        }
    }
}

/** `adb shell am broadcast -n io.github.thisisankit27.voicemail.probe/.ProbeCommandReceiver --es probe_action <action>` */
class ProbeCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProbeActions.run(context.applicationContext, intent)
    }
}
