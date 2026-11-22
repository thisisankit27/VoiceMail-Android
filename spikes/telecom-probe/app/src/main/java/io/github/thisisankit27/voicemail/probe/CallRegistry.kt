package io.github.thisisankit27.voicemail.probe

import android.os.Build
import android.telecom.Call
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The calls Telecom has given the InCallService, observable by the in-call UI. */
object CallRegistry {
    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    val calls: StateFlow<List<Call>> = _calls.asStateFlow()

    /** Bumped whenever any call changes state, so the UI re-reads [Call.Details]. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun add(call: Call) = _calls.update { it + call }
    fun remove(call: Call) = _calls.update { it - call }
    fun changed() = _revision.update { it + 1 }
}

@Suppress("DEPRECATION")
fun Call.stateCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) details.state else state

fun stateName(state: Int): String = when (state) {
    Call.STATE_NEW -> "NEW"
    Call.STATE_DIALING -> "DIALING"
    Call.STATE_RINGING -> "RINGING"
    Call.STATE_HOLDING -> "HOLDING"
    Call.STATE_ACTIVE -> "ACTIVE"
    Call.STATE_DISCONNECTED -> "DISCONNECTED"
    Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
    Call.STATE_CONNECTING -> "CONNECTING"
    Call.STATE_DISCONNECTING -> "DISCONNECTING"
    Call.STATE_PULLING_CALL -> "PULLING_CALL"
    Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"
    Call.STATE_SIMULATED_RINGING -> "SIMULATED_RINGING"
    else -> "STATE#$state"
}

/** Keeps only the last two digits: logs from this probe may be pasted into a public repo. */
fun redactNumber(raw: String?): String {
    if (raw.isNullOrBlank()) return "<none>"
    val digits = raw.filter { it.isDigit() }
    return if (digits.length <= 2) "••" else "••${digits.takeLast(2)}"
}

fun Call.describe(): String {
    val d = details
    val direction = when (d.callDirection) {
        Call.Details.DIRECTION_INCOMING -> "IN"
        Call.Details.DIRECTION_OUTGOING -> "OUT"
        else -> "?"
    }
    val presentation = when (d.handlePresentation) {
        android.telecom.TelecomManager.PRESENTATION_ALLOWED -> "ALLOWED"
        android.telecom.TelecomManager.PRESENTATION_RESTRICTED -> "RESTRICTED"
        android.telecom.TelecomManager.PRESENTATION_UNKNOWN -> "UNKNOWN"
        android.telecom.TelecomManager.PRESENTATION_PAYPHONE -> "PAYPHONE"
        else -> "P#${d.handlePresentation}"
    }
    // The PhoneAccount id can be an ICCID on some builds: log only a hash of it.
    val account = d.accountHandle?.id?.hashCode()?.toUInt()?.toString(16) ?: "none"
    return "state=${stateName(stateCompat())} dir=$direction number=${redactNumber(d.handle?.schemeSpecificPart)} " +
        "presentation=$presentation accountHash=$account"
}
