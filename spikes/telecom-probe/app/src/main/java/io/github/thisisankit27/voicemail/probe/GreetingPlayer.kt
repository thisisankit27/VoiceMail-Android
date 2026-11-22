package io.github.thisisankit27.voicemail.probe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * E3 instrument. Plays the rendered greeting (or a tone pattern if none was rendered) with a
 * chosen [usage], then a beep. During a call on speakerphone, the only way the caller can hear
 * this is acoustically: loudspeaker → phone microphone → uplink. That is exactly what E3 tests.
 */
class GreetingPlayer(private val context: Context, private val usage: Int) {
    private val main = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var track: AudioTrack? = null

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun play(onDone: () -> Unit) {
        logVolumes()
        val file = greetingFile(context)
        if (!file.exists()) {
            ProbeLog.i("E3 no rendered greeting (run 'Render greeting'); playing a 3 s tone pattern instead")
            playTone(frequency = 660, millis = 3000, onDone = onDone)
            return
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.path)
                setVolume(1f, 1f)
                setOnCompletionListener {
                    ProbeLog.i("E3 greeting playback completed")
                    onDone()
                }
                setOnErrorListener { _, what, extra ->
                    ProbeLog.i("E3 MediaPlayer error what=$what extra=$extra")
                    onDone()
                    true
                }
                prepare()
                start()
            }
            ProbeLog.i("E3 greeting playing usage=${ProbeConfig.usageName(usage)} durationMs=${mediaPlayer?.duration}")
        } catch (e: Exception) {
            ProbeLog.e("E3 greeting playback failed", e)
            onDone()
        }
    }

    fun beep(onDone: () -> Unit) = playTone(frequency = 1000, millis = 600, onDone = onDone)

    fun release() {
        main.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        track?.release()
        track = null
    }

    private fun playTone(frequency: Int, millis: Int, onDone: () -> Unit) {
        val rate = 16_000
        val count = rate * millis / 1000
        // On/off pattern (300 ms on, 100 ms off) for the long tone so it is easy to recognise.
        val pulsed = millis > 1000
        val pcm = ShortArray(count) { i ->
            val inPulse = !pulsed || (i % (rate * 4 / 10)) < rate * 3 / 10
            if (inPulse) (sin(2 * PI * frequency * i / rate) * Short.MAX_VALUE * 0.8).toInt().toShort() else 0
        }
        try {
            track?.release()
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(count * 2)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    play()
                }
        } catch (e: Exception) {
            ProbeLog.e("tone playback failed", e)
        }
        main.postDelayed(onDone, millis + 150L)
    }

    private fun logVolumes() {
        val am = context.getSystemService(AudioManager::class.java)
        fun v(stream: Int) = "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}"
        ProbeLog.i(
            "volumes voiceCall=${v(AudioManager.STREAM_VOICE_CALL)} music=${v(AudioManager.STREAM_MUSIC)} " +
                "ring=${v(AudioManager.STREAM_RING)} mode=${am.mode} speakerphone=${am.isSpeakerphoneOn}",
        )
    }

    companion object {
        fun greetingFile(context: Context): File = File(context.filesDir, "greeting.wav")
    }
}
