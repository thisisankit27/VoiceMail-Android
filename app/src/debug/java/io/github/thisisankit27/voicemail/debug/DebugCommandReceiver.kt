package io.github.thisisankit27.voicemail.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.core.AppLog
import kotlinx.coroutines.launch

/**
 * ADB command channel for device testing. Protected by android.permission.DUMP, which the ADB shell
 * holds and third-party apps cannot get.
 *   adb shell am broadcast -n io.github.thisisankit27.voicemail/.debug.DebugCommandReceiver --es cmd <cmd>
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        val app = context.app
        app.appScope.launch {
            try {
                AppLog.i("debug cmd=$cmd")
                when (cmd) {
                    "helper_start" -> AppLog.i("result: ${app.helper.ensureRunning("debug", userInitiated = true) { AppLog.i("KEY PROMPT shown: accept it on the phone") }}")
                    "helper_check" -> AppLog.i("result: ${app.helper.check()}")
                    "helper_stop" -> app.helper.stop()
                    "helper_devices" -> AppLog.i(app.helper.client.devices())
                    "loopback_register" -> LoopbackConnectionService.register(context)
                    "fake_incoming" -> LoopbackConnectionService.placeIncoming(context, intent.getStringExtra("scenario") ?: LoopbackConnectionService.SCENARIO_MESSAGE)
                    "enable" -> app.settings.update { it.copy(enabled = intent.getBooleanExtra("value", true)) }
                    "ring" -> app.settings.update { it.copy(ringSeconds = intent.getIntExtra("value", 20)) }
                    "owner" -> app.settings.update { it.copy(ownerName = intent.getStringExtra("value").orEmpty()) }
                    "render" -> AppLog.i("render ok=${app.greetings.render(app.settings.current(), io.github.thisisankit27.voicemail.greeting.GreetingKind.MESSAGE)}")
                    "allow_messages" -> app.settings.update { it.copy(allowMessages = intent.getBooleanExtra("value", true)) }
                    "answer" -> io.github.thisisankit27.voicemail.telephony.CallManager.calls.value.firstOrNull()?.let { io.github.thisisankit27.voicemail.telephony.CallManager.answer(it.id) }
                    "reject" -> io.github.thisisankit27.voicemail.telephony.CallManager.calls.value.firstOrNull()?.let { io.github.thisisankit27.voicemail.telephony.CallManager.reject(it.id) }
                    "take_over" -> io.github.thisisankit27.voicemail.telephony.CallManager.calls.value.firstOrNull()?.let { app.engine.session(it.id)?.takeOver() }
                    "to_voicemail" -> io.github.thisisankit27.voicemail.telephony.CallManager.calls.value.firstOrNull()?.let { app.engine.session(it.id)?.sendToVoicemailNow() }
                    "hangup" -> io.github.thisisankit27.voicemail.telephony.CallManager.hangupAll()
                    "calls" -> AppLog.i("calls: ${io.github.thisisankit27.voicemail.telephony.CallManager.calls.value}")
                    "seed" -> seed(context)
                    "caller_side" -> app.settings.update { it.copy(saveCallerSide = intent.getBooleanExtra("value", true)) }
                    "max_message" -> app.settings.update { it.copy(maxMessageSeconds = intent.getIntExtra("value", 30)) }
                    "settings" -> AppLog.i("settings: ${app.settings.current().let { "max=${it.maxMessageSeconds}s silence=${it.silenceStopSeconds}s callerSide=${it.saveCallerSide} ring=${it.ringSeconds}s" }}")
                    "init" -> AppLog.i("token ready (${app.helper.token.length} chars)")
                    "key" -> AppLog.i("adb key fingerprint ${app.helper.key.fingerprint()} authorized=${app.helper.keyAuthorized} secure=${app.helper.hasSecureSettings()}")
                    else -> AppLog.w("unknown debug cmd $cmd")
                }
            } catch (t: Throwable) {
                AppLog.w("debug cmd $cmd failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** Sample voicemails with fake numbers and a generated tone, for UI work. */
    private suspend fun seed(context: Context) {
        val dao = context.app.database.voicemails()
        val dir = java.io.File(context.filesDir, "voicemails").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val samples = listOf(
            Triple("+15550100", "Sample Caller", 18_000L) to 25L * 60_000,
            Triple("+15550101", null, 42_000L) to 3L * 3_600_000,
            Triple("+15550102", "Delivery", 7_000L) to 27L * 3_600_000,
            Triple(null, null, 0L) to 50L * 3_600_000,
            Triple("+15550103", "Sample Friend", 64_000L) to 6L * 86_400_000,
        )
        for ((info, ago) in samples) {
            val (number, name, ms) = info
            val id = java.util.UUID.randomUUID().toString()
            var file: String? = null
            if (ms > 0) {
                file = "vm_$id.wav"
                val rate = 16_000
                val pcm = ShortArray((rate * ms / 1000).toInt()) { i -> (kotlin.math.sin(2 * Math.PI * (300 + (i / 4000) % 5 * 60) * i / rate) * 6000).toInt().toShort() }
                io.github.thisisankit27.voicemail.core.Wav.write(java.io.File(dir, file), io.github.thisisankit27.voicemail.core.Pcm(rate, 1, pcm))
            }
            dao.insert(
                io.github.thisisankit27.voicemail.data.Voicemail(
                    id = id, callerNumber = number, presentation = if (number == null) 2 else 1, callerName = name, audioFile = file,
                    durationMs = ms, createdAt = now - ago, isRead = ago > 26L * 3_600_000,
                    status = if (ms > 0) io.github.thisisankit27.voicemail.data.RecordingStatus.RECORDED else io.github.thisisankit27.voicemail.data.RecordingStatus.EMPTY,
                ),
            )
        }
        AppLog.i("seeded ${samples.size} sample voicemails")
    }
}
