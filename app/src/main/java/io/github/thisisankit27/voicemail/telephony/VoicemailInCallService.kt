package io.github.thisisankit27.voicemail.telephony

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telephony.SubscriptionManager
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.core.redactNumber
import io.github.thisisankit27.voicemail.ui.call.InCallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bound by Telecom while the app is the default phone app and any call exists. It feeds
 * [CallManager] (which the call screens observe), runs the voicemail engine, and keeps the call
 * notification / foreground service up for as long as a call exists.
 */
class VoicemailInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            AppLog.i("call ${CallManager.idOf(call)} -> ${call.phase()}")
            app.engine.onStateChanged(call, state)
            CallManager.refresh()
            CallService.update(this@VoicemailInCallService)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) = CallManager.refresh()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = CallManager.refresh()
        override fun onParentChanged(call: Call, parent: Call?) = CallManager.refresh()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = CallManager.refresh()
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
        CallManager.formatter = app.contacts::format
        CallManager.simLabeler = ::simLabel
    }

    override fun onDestroy() {
        if (CallManager.service === this) CallManager.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        AppLog.i("call added ${call.phase()} dir=${call.details.callDirection} ${redactNumber(call.details.handle?.schemeSpecificPart)}")
        call.registerCallback(callback)
        CallManager.add(call)
        app.appScope.launch(Dispatchers.IO) {
            CallManager.setContact(call, app.contacts.lookup(call.details.handle?.schemeSpecificPart))
        }
        app.engine.onCallAdded(call)
        CallService.update(this)
        // Incoming calls are surfaced by the full-screen notification (it opens the screen itself
        // when the phone is locked or idle). Outgoing calls open the screen straight away.
        if (call.phase() != CallPhase.RINGING) showCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        app.engine.onCallRemoved(call)
        CallManager.remove(call)
        CallService.update(this)
        if (CallManager.calls.value.isEmpty()) setMuted(false)
    }

    @Deprecated("Deprecated in API 34, still delivered; the G60 runs API 31")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallManager.onAudioState(audioState)
    }

    override fun onBringToForeground(showDialpad: Boolean) = showCallScreen()

    private fun showCallScreen() {
        try {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w("could not open the call screen", e)
        }
    }

    /** "SIM 1" / carrier name on dual-SIM phones, null on single-SIM. */
    private fun simLabel(call: Call): String? = try {
        val sm = getSystemService(SubscriptionManager::class.java)
        val subs = sm.activeSubscriptionInfoList.orEmpty()
        if (subs.size < 2) null else {
            val handleId = call.details.accountHandle?.id
            val sub = subs.firstOrNull { it.iccId == handleId || it.subscriptionId.toString() == handleId }
            sub?.let { "SIM ${it.simSlotIndex + 1}" }
        }
    } catch (_: SecurityException) {
        null
    }
}
