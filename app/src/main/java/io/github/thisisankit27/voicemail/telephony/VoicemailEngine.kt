package io.github.thisisankit27.voicemail.telephony

import android.content.Context
import android.telecom.Call
import io.github.thisisankit27.voicemail.contacts.ContactLookup
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.data.RoutingMode
import io.github.thisisankit27.voicemail.data.SettingsStore
import io.github.thisisankit27.voicemail.data.VoicemailDao
import io.github.thisisankit27.voicemail.greeting.GreetingRepository
import io.github.thisisankit27.voicemail.helper.HelperClient
import io.github.thisisankit27.voicemail.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Decides, per incoming call, whether the app should take a message, and owns the sessions. */
class VoicemailEngine(
    val context: Context,
    val settingsStore: SettingsStore,
    val greetings: GreetingRepository,
    val voicemails: VoicemailDao,
    val contacts: ContactLookup,
    val helperClient: HelperClient,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    private val onHelperDown: () -> Unit,
) {
    private val sessions = ConcurrentHashMap<Call, VoicemailSession>()

    fun session(callId: String): VoicemailSession? =
        sessions.entries.firstOrNull { CallManager.idOf(it.key) == callId }?.value

    fun onCallAdded(call: Call) {
        if (!VoicemailSession.isIncomingRinging(call) || VoicemailSession.isEmergency(call)) return
        scope.launch {
            val settings = settingsStore.current()
            if (!settings.enabled) return@launch
            val number = call.details.handle?.schemeSpecificPart
            if (settings.routing == RoutingMode.UNKNOWN_ONLY) {
                val known = withContext(Dispatchers.IO) { contacts.lookup(number) } != null
                if (known) {
                    AppLog.i("caller is a contact and routing is UNKNOWN_ONLY; not arming")
                    return@launch
                }
            }
            if (call.stateCompat() != android.telecom.Call.STATE_RINGING) return@launch
            val session = VoicemailSession(call, this@VoicemailEngine, settings, scope)
            sessions[call] = session
            session.start()
        }
    }

    fun onStateChanged(call: Call, state: Int) {
        sessions[call]?.onStateChanged(state)
    }

    fun onCallRemoved(call: Call) {
        sessions.remove(call)?.let { session ->
            // The session sees DISCONNECTED and finishes by itself (saving any message). The cancel
            // is only a safety net in case it is stuck waiting on the helper.
            session.onStateChanged(Call.STATE_DISCONNECTED)
            scope.launch {
                kotlinx.coroutines.delay(5_000)
                session.cancel("call removed")
            }
        }
    }

    internal fun onNewVoicemail(id: String) {
        scope.launch {
            if (settingsStore.current().notifyNewVoicemail) voicemails.get(id)?.let { notifier.newVoicemail(it) }
        }
    }

    internal fun onHelperMissing() = onHelperDown()
}
