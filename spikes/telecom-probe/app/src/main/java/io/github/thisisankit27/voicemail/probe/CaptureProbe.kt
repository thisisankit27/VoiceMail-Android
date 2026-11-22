package io.github.thisisankit27.voicemail.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * E4 instrument. Records one [source] to a WAV and reports, every second, the loudest 100 ms
 * window in dBFS together with the framework's own verdict: [android.media.AudioRecordingConfiguration.isClientSilenced].
 * A silenced client receives zeros, which shows up here as -120 dBFS and `silenced=true`.
 */
class CaptureProbe(
    private val context: Context,
    private val source: Int,
    private val output: File,
) {
    @Volatile private var running = false

    fun start(maxSeconds: Int, label: String, onDone: (String) -> Unit) {
        running = true
        thread(name = "capture-probe") {
            val result = runCatching { record(maxSeconds, label) }
                .getOrElse { "$label exception ${it::class.java.simpleName}: ${it.message}" }
            ProbeLog.i(result)
            onDone(result)
        }
    }

    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission") // checked below
    private fun record(maxSeconds: Int, label: String): String {
        val name = ProbeConfig.sourceName(source)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "$label source=$name FAILED: RECORD_AUDIO not granted"
        }
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, RATE))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return "$label source=$name FAILED: AudioRecord did not initialize"
        }
        output.parentFile?.mkdirs()
        val wav = WavWriter(output, RATE)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return "$label source=$name FAILED: startRecording state=${record.recordingState}"
            }
            ProbeLog.i("$label capture started source=$name file=${output.name}")
            val window = ShortArray(RATE / 10)
            val deadline = SystemClock.elapsedRealtime() + maxSeconds * 1000L
            var windows = 0
            var zeroWindows = 0
            var loudest = SILENT_DB
            var loudestThisSecond = SILENT_DB
            var silencedEver = false
            while (running && SystemClock.elapsedRealtime() < deadline) {
                val n = record.read(window, 0, window.size)
                if (n < 0) {
                    ProbeLog.i("$label read error $n")
                    break
                }
                if (n == 0) continue
                wav.write(window, n)
                val db = rmsDbfs(window, n)
                windows++
                if (db <= SILENT_DB) zeroWindows++
                loudest = max(loudest, db)
                loudestThisSecond = max(loudestThisSecond, db)
                if (windows % 10 == 0) {
                    val silenced = record.activeRecordingConfiguration?.isClientSilenced
                    if (silenced == true) silencedEver = true
                    ProbeLog.i("$label t=${windows / 10}s peak=%.1f dBFS silenced=%s".format(loudestThisSecond, silenced))
                    loudestThisSecond = SILENT_DB
                }
            }
            val zeroPct = if (windows == 0) 0 else zeroWindows * 100 / windows
            return "$label RESULT source=$name seconds=${windows / 10.0} loudest=%.1f dBFS digitalSilence=%d%% silencedEver=%s file=%s bytes=%d"
                .format(loudest, zeroPct, silencedEver, output.name, wav.bytesWritten)
        } finally {
            runCatching { record.stop() }
            record.release()
            wav.close()
        }
    }

    companion object {
        const val RATE = 16_000
        const val SILENT_DB = -120.0

        fun rmsDbfs(samples: ShortArray, count: Int): Double {
            var sum = 0.0
            for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
            val rms = sqrt(sum / count)
            return if (rms < 1e-9) SILENT_DB else 20 * log10(rms / Short.MAX_VALUE)
        }

        fun captureDir(context: Context): File = File(context.getExternalFilesDir(null), "captures")
    }
}
