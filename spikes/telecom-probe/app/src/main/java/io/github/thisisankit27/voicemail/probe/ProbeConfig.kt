package io.github.thisisankit27.voicemail.probe

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder

/** Experiment knobs, persisted so they survive the process being restarted by Telecom. */
class ProbeConfig(context: Context) {
    private val prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE)

    /** E2: seconds of ringing before the probe answers. 0 = never auto-answer. */
    var autoAnswerSeconds: Int
        get() = prefs.getInt("auto_answer_s", 0)
        set(value) = prefs.edit().putInt("auto_answer_s", value.coerceIn(0, 60)).apply()

    /** E3: switch the call to the loudspeaker after auto-answer. */
    var speakerOnAnswer: Boolean
        get() = prefs.getBoolean("speaker", true)
        set(value) = prefs.edit().putBoolean("speaker", value).apply()

    /** E3: AudioAttributes usage the greeting is played with. */
    var greetingUsage: Int
        get() = prefs.getInt("usage", AudioAttributes.USAGE_VOICE_COMMUNICATION)
        set(value) = prefs.edit().putInt("usage", value).apply()

    /** E4: AudioRecord source used to try to capture the caller. */
    var captureSource: Int
        get() = prefs.getInt("source", MediaRecorder.AudioSource.MIC)
        set(value) = prefs.edit().putInt("source", value).apply()

    /** E4: how long to record after the beep before disconnecting. */
    var captureSeconds: Int
        get() = prefs.getInt("capture_s", 30)
        set(value) = prefs.edit().putInt("capture_s", value.coerceIn(5, 120)).apply()

    /** E4/E11: when false, the app does not record after the beep (leaves the mic to a shell-identity capture). */
    var probeCaptureEnabled: Boolean
        get() = prefs.getBoolean("probe_capture", true)
        set(value) = prefs.edit().putBoolean("probe_capture", value).apply()

    /** E11: when false, the app plays no speaker greeting after answering (the shell helper drives call audio). */
    var appGreetingEnabled: Boolean
        get() = prefs.getBoolean("app_greeting", true)
        set(value) = prefs.edit().putBoolean("app_greeting", value).apply()

    /** E10: when holding ROLE_CALL_SCREENING, reject every incoming call. */
    var screeningReject: Boolean
        get() = prefs.getBoolean("screen_reject", false)
        set(value) = prefs.edit().putBoolean("screen_reject", value).apply()

    fun summary(): String =
        "autoAnswer=${autoAnswerSeconds}s speaker=$speakerOnAnswer usage=${usageName(greetingUsage)} " +
            "source=${sourceName(captureSource)} capture=${captureSeconds}s probeCapture=$probeCaptureEnabled appGreeting=$appGreetingEnabled screeningReject=$screeningReject"

    companion object {
        val USAGES = listOf(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_ASSISTANT,
        )

        val SOURCES = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED,
        )

        fun usageName(usage: Int): String = when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
            else -> "usage#$usage"
        }

        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            else -> "source#$source"
        }

        fun <T> next(list: List<T>, current: T): T = list[(list.indexOf(current) + 1).mod(list.size)]
    }
}
