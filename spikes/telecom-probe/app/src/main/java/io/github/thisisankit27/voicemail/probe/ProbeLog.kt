package io.github.thisisankit27.voicemail.probe

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Experiment timeline. Every line goes to logcat under [TAG], so `adb logcat -s TelecomProbe`
 * captures a run. The most recent lines are also kept in memory for the on-screen timeline.
 * Phone numbers must be passed through [redactNumber] before they reach here.
 */
object ProbeLog {
    const val TAG = "TelecomProbe"
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(message: String) {
        Log.i(TAG, message)
        remember(message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        remember("ERROR $message${error?.let { ": ${it::class.java.simpleName}: ${it.message}" } ?: ""}")
    }

    private fun remember(message: String) {
        val line = "${LocalTime.now().format(time)}  $message"
        _lines.update { (listOf(line) + it).take(400) }
    }
}
