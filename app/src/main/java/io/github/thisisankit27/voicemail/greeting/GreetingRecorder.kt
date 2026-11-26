package io.github.thisisankit27.voicemail.greeting

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.thisisankit27.voicemail.core.Pcm
import io.github.thisisankit27.voicemail.core.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class RecorderState(val recording: Boolean = false, val elapsedMs: Long = 0, val levelDb: Double = Wav.SILENT_DB, val hasTake: Boolean = false)

/** Records the owner's own greeting with the microphone (outside calls), capped at [MAX_MS]. */
class GreetingRecorder(private val target: File, private val takeFile: File) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(RecorderState(hasTake = takeFile.exists()))
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    @SuppressLint("MissingPermission") // the screen requests RECORD_AUDIO before calling start()
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
            val samples = ArrayList<Short>(RATE * 10)
            val window = ShortArray(RATE / 20)
            try {
                record.startRecording()
                _state.value = RecorderState(recording = true)
                while (isActive && samples.size < RATE * MAX_MS / 1000) {
                    val n = record.read(window, 0, window.size)
                    if (n <= 0) continue
                    for (i in 0 until n) samples.add(window[i])
                    _state.value = RecorderState(true, samples.size * 1000L / RATE, Wav.rmsDbfs(window, n))
                }
            } finally {
                record.stop()
                record.release()
                val pcm = Wav.normalize(trimSilence(Pcm(RATE, 1, samples.toShortArray())))
                if (pcm.durationMs > 500) Wav.write(takeFile, pcm)
                _state.value = RecorderState(recording = false, elapsedMs = pcm.durationMs, hasTake = takeFile.exists())
            }
        }
    }

    fun stop() { job?.cancel() }

    /** Makes the last take the active recorded greeting. */
    fun useTake(): Boolean {
        if (!takeFile.exists()) return false
        takeFile.copyTo(target, overwrite = true)
        return true
    }

    /** Cuts leading/trailing silence (the tap on the button, the breath before speaking). */
    private fun trimSilence(pcm: Pcm): Pcm {
        val win = RATE / 50
        val s = pcm.samples
        fun loud(at: Int) = Wav.rmsDbfs(s.copyOfRange(at, minOf(at + win, s.size))) > -45
        var start = 0
        while (start + win < s.size && !loud(start)) start += win
        var end = s.size
        while (end - win > start && !loud(end - win)) end -= win
        val from = (start - RATE / 10).coerceAtLeast(0)
        val to = (end + RATE / 5).coerceAtMost(s.size)
        return if (to - from < RATE / 2) pcm else Pcm(RATE, 1, s.copyOfRange(from, to))
    }

    companion object {
        const val RATE = 24_000
        const val MAX_MS = 45_000L
    }
}
