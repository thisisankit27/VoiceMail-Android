package io.github.thisisankit27.voicemail.telephony

import android.media.MediaRecorder
import android.os.SystemClock
import android.telecom.Call
import android.telecom.VideoProfile
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.core.Tones
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
        val u = uplink
        scope.launch(Dispatchers.IO) { p?.stop(); c?.stop(); u?.stop() }
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
            startCallerSideCopy()
            CallManager.setVoicemailPhase(call, VoicemailPhase.Greeting)
            // Greeting and beep go out as ONE clip: a separate short beep clip was inaudible to callers.
            val clip = if (settings.allowMessages) Tones.greetingWithBeep(greeting) else greeting
            val summary = playThroughHelper { deps.helperClient.play(clip.samples, clip.sampleRate, clip.channels) }
            AppLog.i("session: greeting${if (settings.allowMessages) "+beep" else ""} $summary")
            if (!settings.allowMessages) {
                CallManager.setVoicemailPhase(call, VoicemailPhase.Closing)
                delay(HANGUP_GRACE_MS) // let the end of the closing greeting reach the caller
                return
            }
            if (state.value != Call.STATE_ACTIVE) return

            // 5. Record the caller, up to the limit. Only one in-call capture at a time (see below).
            pauseCallerSideCopy()
            val stopReason = record(number)

            // 6. Time's up: tell the caller with two beeps, then the app hangs up (in finally).
            if (stopReason == "max length" || stopReason == "silence") {
                CallManager.setVoicemailPhase(call, VoicemailPhase.Closing)
                startCallerSideCopy()
                val end = playThroughHelper { deps.helperClient.play(Tones.endBeeps(), 16_000) }
                AppLog.i("session: end beeps $end")
                // The uplink lags ~0.5–0.9 s behind what the helper writes. Keep the line open a moment
                // so the beeps fully reach the caller and the caller-side copy contains them.
                delay(HANGUP_GRACE_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("session failed", e)
        } finally {
            withContext(NonCancellable) {
                playback?.stop(); playback?.close()
                capture?.stop(); capture?.close()
                // Stop the caller-side copy BEFORE unmuting, so it never contains the owner's voice.
                stopCallerSideCopy()
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

    /** Records the caller; returns why it stopped. */
    private suspend fun record(number: String?): String = withContext(Dispatchers.IO) {
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
        stopReason
    }

    // ---- Diagnostics: what the caller heard ------------------------------------------------

    private var uplink: HelperClient.Capture? = null
    private var uplinkJob: Job? = null
    private var uplinkWriter: WavWriter? = null

    /**
     * When enabled, records the call UPLINK (what is sent to the caller) into vm_<id>_caller.wav:
     * the greeting and beep, and later the end beeps. It is paused while the caller's message is
     * recorded: on the G60 two simultaneous in-call captures share one HAL stream, and a running
     * uplink capture made the message (downlink) capture come back silent (real call, 2026-11-23).
     * One second of silence marks where the message recording was skipped.
     */
    private fun startCallerSideCopy() {
        if (!settings.saveCallerSide) return
        val writer = uplinkWriter ?: run {
            val file = callerSideFile(deps.context, id)
            file.parentFile?.mkdirs()
            WavWriter(file, CAPTURE_RATE).also { uplinkWriter = it }
        }
        if (writer.bytesWritten > 0) writer.write(Tones.silence(CAPTURE_RATE, 1_000))
        uplinkJob = scope.launch(Dispatchers.IO) {
            try {
                val c = deps.helperClient.capture(MediaRecorder.AudioSource.VOICE_UPLINK, CAPTURE_RATE)
                uplink = c
                while (isActive) {
                    val chunk = runInterruptible { c.read() } ?: break
                    synchronized(writer) { writer.write(chunk) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) AppLog.w("caller-side copy failed", e)
            } finally {
                uplink?.close()
                uplink = null
            }
        }
    }

    /** Pauses the copy and waits until the uplink capture is really closed. */
    private suspend fun pauseCallerSideCopy() {
        val job = uplinkJob ?: return
        withContext(Dispatchers.IO) { uplink?.stop() }
        withTimeoutOrNull(1_500) { job.join() } ?: job.cancel()
        uplinkJob = null
    }

    private suspend fun stopCallerSideCopy() {
        pauseCallerSideCopy()
        uplinkWriter?.let { w ->
            synchronized(w) { w.close() }
            AppLog.i("session: caller-side copy saved (${w.durationMs} ms)")
        }
        uplinkWriter = null
    }

    companion object {
        const val CAPTURE_RATE = 16_000
        /** Time the line stays open after the last sound, so it reaches the caller before hang-up. */
        const val HANGUP_GRACE_MS = 1_500L

        fun callerSideFile(context: android.content.Context, id: String) = File(File(context.filesDir, "voicemails"), "vm_${id}_caller.wav")
        /** A 100 ms window louder than this counts as the caller speaking. */
        const val VOICE_DB = -45.0

        fun isIncomingRinging(call: Call) =
            call.stateCompat() == Call.STATE_RINGING && call.details.callDirection == Call.Details.DIRECTION_INCOMING

        fun isEmergency(call: Call): Boolean =
            call.details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
                call.details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
    }
}
