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
                    "helper_start" -> AppLog.i("result: ${app.helper.ensureRunning("debug") { AppLog.i("KEY PROMPT shown: accept it on the phone") }}")
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
}
