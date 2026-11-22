package io.github.thisisankit27.voicemail.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * App event log: logcat (tag [TAG]) plus the most recent lines in memory for the diagnostics screen.
 * Phone numbers must go through [redactNumber] before they reach here.
 */
object AppLog {
    const val TAG = "VoiceMail"
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(message: String) {
        Log.i(TAG, message)
        remember(message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        remember("WARN $message${error?.let { ": ${it::class.java.simpleName}: ${it.message}" } ?: ""}")
    }

    private fun remember(message: String) {
        val line = "${LocalDateTime.now().format(time)}  $message"
        _lines.update { (listOf(line) + it).take(300) }
    }
}

/** Keeps only the last two digits, for logs. */
fun redactNumber(raw: String?): String {
    if (raw.isNullOrBlank()) return "<none>"
    val digits = raw.filter { it.isDigit() }
    return if (digits.length <= 2) "••" else "••${digits.takeLast(2)}"
}
