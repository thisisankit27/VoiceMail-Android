package io.github.thisisankit27.voicemail.probe

import android.content.AttributionSource
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Phase 0 "shell identity" experiment (E11/E12). NOT an app component: this is a command-line
 * program run as the ADB shell user (uid 2000), which on this device holds CAPTURE_AUDIO_OUTPUT and
 * MODIFY_PHONE_STATE. No root, no system partition changes. Started with:
 *
 *   adb shell CLASSPATH=$(adb shell pm path io.github.thisisankit27.voicemail.probe | cut -d: -f2) \
 *     app_process /system/bin io.github.thisisankit27.voicemail.probe.ShellAudioMain <command> ...
 *
 * Commands:
 *   devices                                  list audio devices (incl. Telephony Rx/Tx)
 *   capture <source> <seconds> <out.wav>     record an AudioSource as uid 2000 (3=VOICE_DOWNLINK, 4=VOICE_CALL, 1=MIC)
 *   play <in.wav> <usage>                    play a WAV routed to the Telephony Tx device (into the call uplink)
 *   tone [ms]                                play a 1 kHz beep into the call uplink
 *
 * Uses one hidden API (AudioManager.getDevicesStatic) via reflection, because there is no Context here.
 * [DOC-ASSUMPTION/hidden-API] — acceptable for a personal build, not for a Play build.
 */
object ShellAudioMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("ShellAudio uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT}")
        try {
            when (args.getOrNull(0)) {
                "devices" -> devices()
                "capture" -> capture(args[1].toInt(), args[2].toInt(), File(args[3]))
                "play" -> {
                    val (rate, channels, pcm) = readWav(File(args[1]))
                    playToCall(rate, channels, pcm, args.getOrNull(2)?.toInt() ?: AudioAttributes.USAGE_VOICE_COMMUNICATION)
                }
                "tone" -> {
                    val rate = 16_000
                    val ms = args.getOrNull(1)?.toInt() ?: 600
                    val pcm = ShortArray(rate * ms / 1000) { i -> (kotlin.math.sin(2 * Math.PI * 1000 * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort() }
                    playToCall(rate, 1, pcm, AudioAttributes.USAGE_VOICE_COMMUNICATION)
                }
                else -> println("usage: devices | capture <source> <seconds> <out.wav> | play <in.wav> [usage]")
            }
        } catch (t: Throwable) {
            println("FAILED ${t::class.java.name}: ${t.message}")
            t.printStackTrace(System.out)
        }
        // Binder threads of the audio service keep app_process alive otherwise.
        System.out.flush()
        System.exit(0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun allDevices(): Array<AudioDeviceInfo> {
        val method = AudioManager::class.java.getMethod("getDevicesStatic", Int::class.javaPrimitiveType)
        return method.invoke(null, AudioManager.GET_DEVICES_ALL) as Array<AudioDeviceInfo>
    }

    private fun devices() {
        for (d in allDevices()) {
            println("device id=${d.id} type=${d.type} ${if (d.isSink) "OUT" else "IN "} name='${d.productName}' address='${d.address}'")
        }
    }

    private fun capture(source: Int, seconds: Int, out: File) {
        val rate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Attribute the capture to the real shell package, otherwise AppOps rejects "uid:2000".
        val record = AudioRecord.Builder()
            .setContext(ShellContext())
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
            )
            .setBufferSizeInBytes(max(minBuffer, rate))
            .build()
        println("capture source=$source state=${record.state} (1=INITIALIZED)")
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        val wav = WavWriter(out, rate)
        record.startRecording()
        println("capture recordingState=${record.recordingState} (3=RECORDING)")
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            println("RESULT source=$source FAILED: startRecording refused (see logcat AudioPolicyIntefaceImpl/AppOps)")
            record.release(); wav.close(); return
        }
        val window = ShortArray(rate / 10)
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        var n10 = 0
        var peak = CaptureProbe.SILENT_DB
        var overall = CaptureProbe.SILENT_DB
        var zero = 0
        var total = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = record.read(window, 0, window.size)
            if (n < 0) { println("read error $n"); break }
            if (n == 0) continue
            wav.write(window, n)
            val db = CaptureProbe.rmsDbfs(window, n)
            total++; if (db <= CaptureProbe.SILENT_DB) zero++
            peak = max(peak, db); overall = max(overall, db)
            if (++n10 == 10) {
                println("t=${total / 10}s peak=%.1f dBFS silenced=%s".format(peak, record.activeRecordingConfiguration?.isClientSilenced))
                n10 = 0; peak = CaptureProbe.SILENT_DB
            }
        }
        record.stop(); record.release(); wav.close()
        println("RESULT source=$source loudest=%.1f dBFS digitalSilence=%d%% bytes=%d file=%s"
            .format(overall, if (total == 0) 0 else zero * 100 / total, wav.bytesWritten, out.path))
    }

    private fun playToCall(rate: Int, channels: Int, pcm: ShortArray, usage: Int) {
        val tx = allDevices().firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        println("telephony TX device: ${tx?.let { "id=${it.id} '${it.productName}'" } ?: "NOT LISTED"}")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (tx != null) println("setPreferredDevice(TX)=${track.setPreferredDevice(tx)}")
        track.play()
        SystemClock.sleep(200)
        println("routedDevice after start: ${track.routedDevice?.let { "type=${it.type} '${it.productName}'" }}")
        val started = SystemClock.elapsedRealtime()
        var offset = 0
        while (offset < pcm.size) {
            val written = track.write(pcm, offset, minOf(4096, pcm.size - offset))
            if (written < 0) { println("write error $written"); break }
            offset += written
        }
        SystemClock.sleep(300)
        println("played ${pcm.size / channels / rate.toDouble()} s in ${SystemClock.elapsedRealtime() - started} ms; routedDevice=${track.routedDevice?.type}")
        track.stop(); track.release()
    }

    private fun readWav(file: File): Triple<Int, Int, ShortArray> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44).also { raf.readFully(it) }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val channels = bb.getShort(22).toInt()
            val rate = bb.getInt(24)
            val body = ByteArray((raf.length() - 44).toInt()).also { raf.readFully(it) }
            val samples = ShortArray(body.size / 2)
            ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            println("wav rate=$rate channels=$channels samples=${samples.size}")
            return Triple(rate, channels, samples)
        }
    }
}

/** Minimal Context presenting the shell's identity (uid 2000, com.android.shell) for attribution. */
private class ShellContext : ContextWrapper(null) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE
    override fun getAttributionTag(): String? = null
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
