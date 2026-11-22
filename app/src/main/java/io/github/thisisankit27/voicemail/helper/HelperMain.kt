package io.github.thisisankit27.voicemail.helper

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
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The shell-identity audio helper (Option 4, personal build only).
 *
 * This is NOT an app component. It is a command-line program that the app starts through ADB, so it
 * runs as the shell user (uid 2000). On the target phones that user holds CAPTURE_AUDIO_OUTPUT and
 * MODIFY_PHONE_STATE, which lets it play the greeting *into* the call (Telephony TX, the HAL's
 * in-call-music path) and record the caller *from* it (VOICE_DOWNLINK). No root is involved.
 *
 *   VM_HELPER_TOKEN=<secret> VM_APP_UID=<uid> CLASSPATH=<apk> app_process /system/bin \
 *       --nice-name=vm-audio-helper io.github.thisisankit27.voicemail.helper.HelperMain
 *
 * It listens on 127.0.0.1 only, and every connection must present the secret first. It exits by
 * itself when the APK it was started from disappears (app updated or uninstalled).
 *
 * Uses one hidden API (AudioManager.getDevicesStatic) via reflection, because there is no Context.
 */
object HelperMain {
    private const val TAG = "VmHelper"
    private lateinit var tokenHash: ByteArray
    private lateinit var apkPath: String

    @JvmStatic
    fun main(args: Array<String>) {
        val token = System.getenv(HelperProtocol.TOKEN_ENV)
        if (token.isNullOrEmpty()) {
            println("refusing to start: ${HelperProtocol.TOKEN_ENV} not set")
            System.exit(2)
        }
        tokenHash = sha256(token!!.toByteArray())
        apkPath = System.getProperty("java.class.path") ?: ""
        val server = try {
            ServerSocket(HelperProtocol.PORT, 8, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        } catch (e: IOException) {
            println("port ${HelperProtocol.PORT} busy (helper already running?): ${e.message}")
            System.exit(3)
            return
        }
        log("started uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} v=${HelperProtocol.VERSION}")
        println("OK helper listening on 127.0.0.1:${HelperProtocol.PORT} pid=${Process.myPid()}")
        System.out.flush()
        startApkWatchdog()
        while (true) {
            val socket = try { server.accept() } catch (e: IOException) { log("accept failed: ${e.message}"); continue }
            thread(name = "conn") { serve(socket) }
        }
    }

    private fun startApkWatchdog() = thread(name = "apk-watchdog", isDaemon = true) {
        while (true) {
            SystemClock.sleep(30_000)
            if (apkPath.isNotEmpty() && !File(apkPath).exists()) {
                log("APK $apkPath is gone (app updated or removed); exiting")
                System.exit(0)
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            try {
                s.soTimeout = 5_000
                val hello = HelperProtocol.read(input)
                if (hello.type != HelperProtocol.HELLO || !MessageDigest.isEqual(sha256(hello.payload), tokenHash)) {
                    log("rejected a connection without the right secret")
                    return
                }
                HelperProtocol.write(
                    output, HelperProtocol.WELCOME,
                    ByteBuffer.allocate(12).putInt(HelperProtocol.VERSION).putInt(Process.myUid()).putInt(Build.VERSION.SDK_INT).array() +
                        apkPath.toByteArray(),
                )
                s.soTimeout = 0
                val request = HelperProtocol.read(input)
                when (request.type) {
                    HelperProtocol.PING -> HelperProtocol.writeText(output, HelperProtocol.PONG, "uid=${Process.myUid()} pid=${Process.myPid()} uptimeMs=${SystemClock.uptimeMillis()}")
                    HelperProtocol.PLAY -> {
                        val bb = ByteBuffer.wrap(request.payload)
                        val rate = bb.int
                        val channels = bb.int
                        val gain = bb.float
                        val pcm = ShortArray(bb.remaining() / 2)
                        bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                        playToCall(rate, channels, pcm, gain, input, output)
                    }
                    HelperProtocol.TONE -> {
                        val bb = ByteBuffer.wrap(request.payload)
                        val hz = bb.int
                        val ms = bb.int
                        val rate = 16_000
                        val pcm = ShortArray(rate * ms / 1000) { i -> (sin(2 * PI * hz * i / rate) * 0.5 * Short.MAX_VALUE).toInt().toShort() }
                        playToCall(rate, 1, pcm, 1f, input, output)
                    }
                    HelperProtocol.CAPTURE -> {
                        val bb = ByteBuffer.wrap(request.payload)
                        capture(bb.int, bb.int, input, output)
                    }
                    HelperProtocol.GRANT_SELF -> HelperProtocol.writeText(output, HelperProtocol.RESULT, grantSecureSettings(request.text()))
                    HelperProtocol.DEVICES -> HelperProtocol.writeText(
                        output, HelperProtocol.RESULT,
                        allDevices().joinToString("\n") { "id=${it.id} type=${it.type} ${if (it.isSink) "OUT" else "IN "} '${it.productName}'" },
                    )
                    HelperProtocol.SHUTDOWN -> {
                        HelperProtocol.writeText(output, HelperProtocol.RESULT, "bye")
                        log("shutdown requested by the app")
                        System.exit(0)
                    }
                    else -> HelperProtocol.writeText(output, HelperProtocol.ERROR, "unknown request ${request.type}")
                }
            } catch (e: IOException) {
                // The app went away mid-operation; nothing to clean up beyond the socket.
            } catch (t: Throwable) {
                log("request failed: ${t::class.java.simpleName}: ${t.message}")
                try { HelperProtocol.writeText(output, HelperProtocol.ERROR, "${t::class.java.simpleName}: ${t.message}") } catch (_: IOException) { }
            }
        }
    }

    /** Watches the connection for STOP or a close, flipping [stopped] so long operations end promptly. */
    private fun watchForStop(input: DataInputStream, stopped: java.util.concurrent.atomic.AtomicBoolean) =
        thread(name = "stop-watch", isDaemon = true) {
            try {
                while (!stopped.get()) {
                    if (HelperProtocol.read(input).type == HelperProtocol.STOP) break
                }
            } catch (_: IOException) { }
            stopped.set(true)
        }

    @Suppress("UNCHECKED_CAST")
    private fun allDevices(): Array<AudioDeviceInfo> {
        val method = AudioManager::class.java.getMethod("getDevicesStatic", Int::class.javaPrimitiveType)
        return method.invoke(null, AudioManager.GET_DEVICES_ALL) as Array<AudioDeviceInfo>
    }

    private fun playToCall(rate: Int, channels: Int, pcm: ShortArray, gain: Float, input: DataInputStream, output: DataOutputStream) {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        watchForStop(input, stopped)
        val tx = allDevices().firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            if (tx != null) track.setPreferredDevice(tx)
            if (gain != 1f) track.setVolume(gain.coerceIn(0f, 1f))
            track.play()
            SystemClock.sleep(150)
            val routed = track.routedDevice
            HelperProtocol.writeText(output, HelperProtocol.PLAY_STARTED, "tx=${tx?.id} routed=${routed?.type} '${routed?.productName}'")
            val started = SystemClock.elapsedRealtime()
            var offset = 0
            while (offset < pcm.size && !stopped.get()) {
                val written = track.write(pcm, offset, minOf(2048, pcm.size - offset))
                if (written < 0) break
                offset += written
            }
            if (!stopped.get()) SystemClock.sleep(250) // let the tail drain
            val summary = "played=${offset * 1000L / channels / rate}ms of ${pcm.size * 1000L / channels / rate}ms " +
                "in ${SystemClock.elapsedRealtime() - started}ms stopped=${stopped.get()} routed=${track.routedDevice?.type}"
            log(summary)
            HelperProtocol.writeText(output, HelperProtocol.PLAY_DONE, summary)
        } finally {
            stopped.set(true)
            track.stop()
            track.release()
        }
    }

    private fun capture(source: Int, rate: Int, input: DataInputStream, output: DataOutputStream) {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        watchForStop(input, stopped)
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setContext(ShellContext())
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
            )
            .setBufferSizeInBytes(max(minBuffer, rate))
            .build()
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                HelperProtocol.writeText(output, HelperProtocol.ERROR, "AudioRecord source=$source did not initialise")
                return
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                HelperProtocol.writeText(output, HelperProtocol.ERROR, "startRecording refused for source=$source")
                return
            }
            log("capture started source=$source rate=$rate")
            val window = ShortArray(rate / 10)
            val bytes = ByteBuffer.allocate(window.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var total = 0L
            val started = SystemClock.elapsedRealtime()
            while (!stopped.get()) {
                val n = record.read(window, 0, window.size)
                if (n < 0) break
                if (n == 0) continue
                bytes.clear()
                for (i in 0 until n) bytes.putShort(window[i])
                HelperProtocol.write(output, HelperProtocol.CAPTURE_DATA, bytes.array().copyOf(n * 2))
                total += n
            }
            val summary = "captured ${total * 1000 / rate}ms in ${SystemClock.elapsedRealtime() - started}ms " +
                "silenced=${record.activeRecordingConfiguration?.isClientSilenced}"
            log(summary)
            try { HelperProtocol.writeText(output, HelperProtocol.CAPTURE_ENDED, summary) } catch (_: IOException) { }
        } finally {
            stopped.set(true)
            try { record.stop() } catch (_: IllegalStateException) { }
            record.release()
        }
    }

    /** Lets the app hold WRITE_SECURE_SETTINGS, which it needs to switch Wireless debugging on after a reboot. */
    private fun grantSecureSettings(pkg: String): String {
        if (!pkg.matches(Regex("[a-zA-Z0-9_.]+"))) return "bad package"
        val p = ProcessBuilder("pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return "exit=${p.waitFor()} $out".trim()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}

/** Minimal Context presenting the shell's identity (uid 2000, com.android.shell) for AppOps attribution. */
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
