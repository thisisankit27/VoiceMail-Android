package io.github.thisisankit27.voicemail.telephony

import android.bluetooth.BluetoothDevice
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import io.github.thisisankit27.voicemail.contacts.ContactMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

enum class CallPhase { NEW, DIALING, RINGING, CONNECTING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED, SELECT_ACCOUNT, OTHER }

/** What the app's voicemail is doing with a call, shown on the call screen. */
sealed interface VoicemailPhase {
    /** Will answer at [answerAtElapsed] (SystemClock.elapsedRealtime) unless the user acts first. */
    data class Countdown(val answerAtElapsed: Long) : VoicemailPhase
    data object Answering : VoicemailPhase
    data object Greeting : VoicemailPhase
    data class Recording(val startedAtElapsed: Long, val levelDb: Double = -96.0) : VoicemailPhase
    data object Closing : VoicemailPhase
}

/** Immutable snapshot of one call for the UI. */
data class CallUi(
    val id: String,
    val number: String?,
    val contact: ContactMatch?,
    val displayName: String,
    val formattedNumber: String,
    val phase: CallPhase,
    val incoming: Boolean,
    val connectTimeMillis: Long,
    val canHold: Boolean,
    val canMerge: Boolean,
    val canSwap: Boolean,
    val canMute: Boolean,
    val isConference: Boolean,
    val childCount: Int,
    val childIds: List<String>,
    val parentId: String?,
    val simLabel: String?,
    val disconnectLabel: String?,
    val voicemail: VoicemailPhase?,
    val presentationRestricted: Boolean,
)

enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET, STREAMING }

data class AudioUi(
    val route: AudioRoute = AudioRoute.EARPIECE,
    val supported: Set<AudioRoute> = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    val muted: Boolean = false,
    val bluetoothDevices: List<BluetoothDevice> = emptyList(),
    val activeBluetooth: BluetoothDevice? = null,
)

/**
 * Process-wide registry of Telecom calls, fed by [VoicemailInCallService]. The UI observes [calls]
 * and [audio]; commands go through here so the screens never touch android.telecom directly.
 */
object CallManager {
    private val calls_ = ConcurrentHashMap<String, Call>()
    private val ids = ConcurrentHashMap<Call, String>()
    private val contacts = ConcurrentHashMap<String, ContactMatch>()
    private val voicemailPhases = ConcurrentHashMap<String, VoicemailPhase>()
    private var nextId = 1

    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val calls: StateFlow<List<CallUi>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow(AudioUi())
    val audio: StateFlow<AudioUi> = _audio.asStateFlow()

    /** The most recent disconnected call, kept briefly so the screen can show "Call ended". */
    private val _ended = MutableStateFlow<CallUi?>(null)
    val ended: StateFlow<CallUi?> = _ended.asStateFlow()

    @Volatile internal var service: VoicemailInCallService? = null
    internal var formatter: (String?) -> String = { it ?: "Private number" }
    internal var simLabeler: (Call) -> String? = { null }

    @Synchronized
    fun idOf(call: Call): String = ids.getOrPut(call) { "c${nextId++}" }.also { calls_[it] = call }

    fun call(id: String): Call? = calls_[id]

    internal fun add(call: Call) {
        idOf(call)
        _ended.value = null
        refresh()
    }

    internal fun remove(call: Call) {
        val id = ids.remove(call) ?: return
        snapshot(call, id).let { _ended.value = it.copy(phase = CallPhase.DISCONNECTED) }
        calls_.remove(id)
        voicemailPhases.remove(id)
        contacts.remove(id)
        refresh()
    }

    internal fun setContact(call: Call, match: ContactMatch?) {
        if (match == null) return
        contacts[idOf(call)] = match
        refresh()
    }

    fun setVoicemailPhase(call: Call, phase: VoicemailPhase?) {
        val id = ids[call] ?: return
        if (phase == null) voicemailPhases.remove(id) else voicemailPhases[id] = phase
        refresh()
    }

    @Suppress("DEPRECATION")
    internal fun onAudioState(state: CallAudioState) {
        val routes = buildSet {
            if (state.supportedRouteMask and CallAudioState.ROUTE_EARPIECE != 0) add(AudioRoute.EARPIECE)
            if (state.supportedRouteMask and CallAudioState.ROUTE_SPEAKER != 0) add(AudioRoute.SPEAKER)
            if (state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0) add(AudioRoute.BLUETOOTH)
            if (state.supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET != 0) add(AudioRoute.WIRED_HEADSET)
        }
        _audio.value = AudioUi(
            route = when (state.route) {
                CallAudioState.ROUTE_SPEAKER -> AudioRoute.SPEAKER
                CallAudioState.ROUTE_BLUETOOTH -> AudioRoute.BLUETOOTH
                CallAudioState.ROUTE_WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
                CallAudioState.ROUTE_STREAMING -> AudioRoute.STREAMING
                else -> AudioRoute.EARPIECE
            },
            supported = routes,
            muted = state.isMuted,
            bluetoothDevices = state.supportedBluetoothDevices.toList(),
            activeBluetooth = state.activeBluetoothDevice,
        )
    }

    fun refresh() {
        _calls.value = calls_.entries.sortedBy { it.key.drop(1).toIntOrNull() ?: 0 }.map { (id, call) -> snapshot(call, id) }
    }

    private fun snapshot(call: Call, id: String): CallUi {
        val d = call.details
        val number = d.handle?.schemeSpecificPart
        val restricted = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val contact = contacts[id]
        val conference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE)
        val formatted = if (restricted && number.isNullOrBlank()) "Private number" else formatter(number)
        val name = when {
            conference -> "Conference call"
            contact != null -> contact.name
            !d.callerDisplayName.isNullOrBlank() -> d.callerDisplayName
            else -> formatted
        }
        return CallUi(
            id = id,
            number = number,
            contact = contact,
            displayName = name,
            formattedNumber = formatted,
            phase = call.phase(),
            incoming = d.callDirection == Call.Details.DIRECTION_INCOMING,
            connectTimeMillis = d.connectTimeMillis,
            canHold = d.can(Call.Details.CAPABILITY_HOLD) || d.can(Call.Details.CAPABILITY_SUPPORT_HOLD),
            canMerge = d.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) || call.conferenceableCalls.isNotEmpty(),
            canSwap = d.can(Call.Details.CAPABILITY_SWAP_CONFERENCE),
            canMute = d.can(Call.Details.CAPABILITY_MUTE),
            isConference = conference,
            childCount = call.children.size,
            childIds = call.children.mapNotNull { ids[it] },
            parentId = call.parent?.let { ids[it] },
            simLabel = simLabeler(call),
            disconnectLabel = d.disconnectCause?.let { describe(it) },
            voicemail = voicemailPhases[id],
            presentationRestricted = restricted,
        )
    }

    private fun describe(cause: DisconnectCause): String? = when (cause.code) {
        DisconnectCause.BUSY -> "Busy"
        DisconnectCause.REJECTED -> "Declined"
        DisconnectCause.MISSED -> "Missed"
        DisconnectCause.ERROR -> cause.label?.toString()?.ifBlank { null } ?: "Call failed"
        DisconnectCause.UNKNOWN -> null
        else -> cause.label?.toString()?.ifBlank { null }
    }

    // ---- Commands -------------------------------------------------------------------------

    fun answer(id: String) = calls_[id]?.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun reject(id: String) = calls_[id]?.reject(false, null)
    fun rejectWithMessage(id: String, text: String) = calls_[id]?.reject(true, text)
    fun hangup(id: String) = calls_[id]?.disconnect()
    fun hold(id: String) = calls_[id]?.hold()
    fun unhold(id: String) = calls_[id]?.unhold()
    fun playDtmf(id: String, digit: Char) = calls_[id]?.playDtmfTone(digit)
    fun stopDtmf(id: String) = calls_[id]?.stopDtmfTone()
    fun swap(id: String) = calls_[id]?.swapConference()

    /** Merges [id] with a call it can be conferenced with (or merges an existing conference). */
    fun merge(id: String) {
        val call = calls_[id] ?: return
        val other = call.conferenceableCalls.firstOrNull()
        if (other != null) call.conference(other) else call.mergeConference()
    }

    fun splitFromConference(id: String) = calls_[id]?.splitFromConference()

    fun setMuted(muted: Boolean) = service?.setMuted(muted)

    @Suppress("DEPRECATION")
    fun setRoute(route: AudioRoute) {
        io.github.thisisankit27.voicemail.core.AppLog.i("audio route -> $route (requested by the user)")
        service?.setAudioRoute(
            when (route) {
                AudioRoute.SPEAKER -> CallAudioState.ROUTE_SPEAKER
                AudioRoute.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
                AudioRoute.WIRED_HEADSET, AudioRoute.EARPIECE -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
                AudioRoute.STREAMING -> CallAudioState.ROUTE_STREAMING
            },
        )
    }

    fun useBluetooth(device: BluetoothDevice) = service?.requestBluetoothAudio(device)

    /** Hang up everything (used by the notification "Hang up" when several calls exist). */
    fun hangupAll() = calls_.values.forEach { it.disconnect() }

    fun hasActiveOrHeld(): Boolean = calls_.values.any { it.phase() in setOf(CallPhase.ACTIVE, CallPhase.HOLDING, CallPhase.DIALING, CallPhase.CONNECTING) }
}

@Suppress("DEPRECATION")
fun Call.stateCompat(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) details.state else state

fun Call.phase(): CallPhase = when (stateCompat()) {
    Call.STATE_NEW -> CallPhase.NEW
    Call.STATE_DIALING -> CallPhase.DIALING
    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallPhase.RINGING
    Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> CallPhase.CONNECTING
    Call.STATE_ACTIVE, Call.STATE_AUDIO_PROCESSING -> CallPhase.ACTIVE
    Call.STATE_HOLDING -> CallPhase.HOLDING
    Call.STATE_DISCONNECTING -> CallPhase.DISCONNECTING
    Call.STATE_DISCONNECTED -> CallPhase.DISCONNECTED
    Call.STATE_SELECT_PHONE_ACCOUNT -> CallPhase.SELECT_ACCOUNT
    else -> CallPhase.OTHER
}
