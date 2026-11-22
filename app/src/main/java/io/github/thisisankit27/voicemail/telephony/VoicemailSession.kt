package io.github.thisisankit27.voicemail.telephony

import android.media.MediaRecorder
import android.os.SystemClock
import android.telecom.Call
import android.telecom.VideoProfile
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.core.Wav
import io.github.thisisankit27.voicemail.core.WavWriter
import io.github.thisisankit27.voicemail.core.redactNumber
import io.github.thisisankit27.voicemail.data.AppSettings
import io.github.thisisankit27.voicemail.data.RecordingStatus
import io.github.thisisankit27.voicemail.data.Voicemail
import io.github.thisisankit27.voicemail.greeting.GreetingKind
import io.github.thisisankit27.voicemail.helper.HelperClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * One unanswered call handled by the app (docs/ARCHITECTURE §4, Option 4):
 *
 *   ring timer → answer() → mute the phone's mic → greeting into the call (helper, Telephony TX)
 *   → beep → record the caller (helper, VOICE_DOWNLINK) → stop on hang-up / max length / silence
 *   → save → disconnect.
 *
 * The call state from Telecom is the source of truth: any state other than RINGING before the timer
 * fires (the user answered or declined, the caller hung up, the network forwarded) cancels the session.
 * Runs as a single coroutine, so there are no races between the timer and the user.
 */
class VoicemailSession(
    private val call: Call,
    private val deps: VoicemailEngine,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val id = UUID.randomUUID().toString()
    private val ringStartedAt = SystemClock.elapsedRealtime()
    val state = MutableStateFlow(call.stateCompat())
    private var job: Job? = null
    private var answeredByApp = false
    private var takenOver = false
    private var playback: HelperClient.Playback? = null
    private var capture: HelperClient.Capture? = null

    fun start() {
        job = scope.launch { run() }
    }

    fun onStateChanged(newState: Int) {
        state.value = newState
    }

    /** "Answer now" from the incoming-call screen: skip the rest of the countdown. */
    fun sendToVoicemailNow() {
        skipCountdown.value = true
    }

    private val skipCountdown = MutableStateFlow(false)

    /** The owner picks up a call the app is handling: stop greeting/recording, unmute, keep the call. */
    fun takeOver() {
        if (!answeredByApp) return
        takenOver = true
        AppLog.i("session: taken over by the user")
        abortAudio()
        job?.cancel()
    }

    fun cancel(reason: String) {
        if (job?.isActive == true) AppLog.i("session cancelled: $reason")
        abortAudio()
        job?.cancel()
    }

    /** Socket reads don't react to coroutine cancellation; telling the helper to stop unblocks them. */
    private fun abortAudio() {
        val p = playback
        val c = capture
        scope.launch(Dispatchers.IO) { p?.stop(); c?.stop() }
    }

    private suspend fun run() {
        val number = call.details.handle?.schemeSpecificPart
        try {
            // 1. Countdown. Any state change (answer, decline, caller hung up, forwarded) ends it.
            val ringMs = settings.ringSeconds * 1000L
            CallManager.setVoicemailPhase(call, VoicemailPhase.Countdown(ringStartedAt + ringMs))
            AppLog.i("session armed for ${redactNumber(number)}: answering in ${settings.ringSeconds}s")
            val interrupted = withTimeoutOrNull(ringMs) {
                combine(state, skipCountdown) { st, skip ->
                    when {
                        st != Call.STATE_RINGING && st != Call.STATE_SIMULATED_RINGING -> "state"
                        skip -> "skip"
                        else -> null
                    }
                }.filterNotNull().first()
            }
            if (interrupted == "state") {
                AppLog.i("session: call left RINGING before the timer (${state.value}); not answering")
                return
            }
            if (CallManager.calls.value.size > 1) {
                AppLog.i("session: another call exists; not answering (would put it on hold)")
                return
            }
            // 2. The helper must be up, or the caller would hear silence. Leave the call ringing.
            if (withContext(Dispatchers.IO) { deps.helperClient.ping() } == null) {
                AppLog.w("session: audio helper not running; letting the call ring out")
                deps.onHelperMissing()
                return
            }
            val greeting = deps.greetings.pcmFor(settings, if (settings.allowMessages) GreetingKind.MESSAGE else GreetingKind.CLOSING)
            if (greeting == null) {
                AppLog.w("session: no greeting audio; not answering")
                return
            }

            // 3. Answer, and mute the phone's microphone so the caller hears only the greeting.
            CallManager.setVoicemailPhase(call, VoicemailPhase.Answering)
            answeredByApp = true
            AppLog.i("session: answering after ${SystemClock.elapsedRealtime() - ringStartedAt} ms")
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            val active = withTimeoutOrNull(10_000) { state.first { it == Call.STATE_ACTIVE || it == Call.STATE_DISCONNECTED } }
            if (active != Call.STATE_ACTIVE) {
                AppLog.i("session: call did not become active ($active)")
                return
            }
            CallManager.setMuted(true)
            delay(700) // let the in-call audio path settle before injecting audio

            // 4. Greeting into the call.
            CallManager.setVoicemailPhase(call, VoicemailPhase.Greeting)
            val summary = playThroughHelper { deps.helperClient.play(greeting.samples, greeting.sampleRate, greeting.channels) }
            AppLog.i("session: greeting $summary")
            if (!settings.allowMessages) {
                CallManager.setVoicemailPhase(call, VoicemailPhase.Closing)
                return
            }
            if (state.value != Call.STATE_ACTIVE) return
            playThroughHelper { deps.helperClient.tone(1000, 450) }

            // 5. Record the caller.
            record(number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("session failed", e)
        } finally {
            withContext(NonCancellable) {
                playback?.stop(); playback?.close()
                capture?.stop(); capture?.close()
                CallManager.setVoicemailPhase(call, null)
                if (answeredByApp) {
                    if (takenOver) {
                        CallManager.setMuted(false)
                        AppLog.i("session: microphone unmuted; the call is yours")
                    } else if (call.stateCompat() == Call.STATE_ACTIVE) {
                        AppLog.i("session: disconnecting")
                        call.disconnect()
                    }
                }
            }
        }
    }

    private suspend fun playThroughHelper(start: () -> HelperClient.Playback): String = withContext(Dispatchers.IO) {
        val p = start()
        playback = p
        try {
            // Stops promptly if the caller hangs up mid-greeting.
            val watcher = launch { state.first { it != Call.STATE_ACTIVE }; p.stop() }
            val result = runInterruptible { p.awaitDone() }
            watcher.cancel()
            result
        } finally {
            p.close()
            playback = null
        }
    }

    private suspend fun record(number: String?) = withContext(Dispatchers.IO) {
        val dir = File(deps.context.filesDir, "voicemails").apply { mkdirs() }
        val fileName = "vm_$id.wav"
        val file = File(dir, fileName)
        val startedAt = System.currentTimeMillis()
        val contact = deps.contacts.lookup(number)
        val row = Voicemail(
            id = id,
            callerNumber = number,
            presentation = call.details.handlePresentation,
            callerName = contact?.name,
            audioFile = fileName,
            createdAt = startedAt,
            ringMs = SystemClock.elapsedRealtime() - ringStartedAt,
            status = RecordingStatus.RECORDING,
            simLabel = CallManager.simLabeler(call),
        )
        deps.voicemails.insert(row)
        val recordStart = SystemClock.elapsedRealtime()
        CallManager.setVoicemailPhase(call, VoicemailPhase.Recording(recordStart))
        AppLog.i("session: recording")
        var peak = Wav.SILENT_DB
        var lastVoiceAt = 0L
        var spoke = false
        var stopReason = "?"
        val writer = WavWriter(file, CAPTURE_RATE)
        try {
            val c = deps.helperClient.capture(MediaRecorder.AudioSource.VOICE_DOWNLINK, CAPTURE_RATE)
            capture = c
            val stopper = launch { state.first { it != Call.STATE_ACTIVE }; c.stop() }
            var lastUiUpdate = 0L
            while (isActive) {
                val chunk = runInterruptible { c.read() }
                if (chunk == null) { stopReason = "capture ended"; break }
                writer.write(chunk)
                val db = Wav.rmsDbfs(chunk)
                val now = SystemClock.elapsedRealtime()
                if (db > peak) peak = db
                if (db > VOICE_DB) { spoke = true; lastVoiceAt = now }
                if (now - lastUiUpdate > 150) {
                    CallManager.setVoicemailPhase(call, VoicemailPhase.Recording(recordStart, db))
                    lastUiUpdate = now
                }
                if (state.value != Call.STATE_ACTIVE) { stopReason = "caller hung up"; break }
                if (writer.durationMs >= settings.maxMessageSeconds * 1000L) { stopReason = "max length"; break }
                if (settings.silenceStopSeconds > 0 && spoke && now - lastVoiceAt > settings.silenceStopSeconds * 1000L) {
                    stopReason = "silence"; break
                }
            }
            stopper.cancel()
        } catch (e: CancellationException) {
            stopReason = if (takenOver) "taken over" else "cancelled"
            throw e
        } finally {
            withContext(NonCancellable) {
                writer.close()
                val duration = writer.durationMs
                val empty = !spoke || duration < 1_000
                if (empty) file.delete()
                deps.voicemails.update(
                    row.copy(
                        audioFile = if (empty) null else fileName,
                        durationMs = duration,
                        status = if (empty) RecordingStatus.EMPTY else RecordingStatus.RECORDED,
                        peakDb = peak,
                    ),
                )
                AppLog.i("session: recording stopped ($stopReason) duration=${duration}ms peak=%.1f dBFS empty=$empty".format(peak))
                if (!empty) deps.onNewVoicemail(id)
            }
        }
    }

    companion object {
        const val CAPTURE_RATE = 16_000
        /** A 100 ms window louder than this counts as the caller speaking. */
        const val VOICE_DB = -45.0

        fun isIncomingRinging(call: Call) =
            call.stateCompat() == Call.STATE_RINGING && call.details.callDirection == Call.Details.DIRECTION_INCOMING

        fun isEmergency(call: Call): Boolean =
            call.details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
                call.details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
    }
}
