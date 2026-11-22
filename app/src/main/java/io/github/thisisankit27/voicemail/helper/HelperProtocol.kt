package io.github.thisisankit27.voicemail.helper

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Wire protocol between the app and the shell-identity audio helper, over a loopback TCP socket.
 *
 * Every frame is `[int32 type][int32 length][payload]` (big-endian). Each connection starts with
 * HELLO carrying the shared secret; the helper closes any connection that doesn't. One connection
 * carries one operation (ping, play, capture, ...), which keeps both ends simple.
 *
 * Shared by both processes: this file is compiled into the APK, and the helper runs from that APK.
 */
object HelperProtocol {
    /** Bump when frames change: the app restarts a helper that reports another version. */
    const val VERSION = 4
    const val PORT = 47231
    const val PROCESS_NAME = "vm-audio-helper"
    const val TOKEN_ENV = "VM_HELPER_TOKEN"
    const val APP_UID_ENV = "VM_APP_UID"
    const val MAX_FRAME = 16 * 1024 * 1024

    // app -> helper
    const val HELLO = 1          // utf8 token
    const val PING = 2           // -> PONG
    const val PLAY = 3           // int rate, int channels, float gain, pcm16le... -> PLAY_STARTED, PLAY_DONE
    const val TONE = 4           // int hz, int ms -> PLAY_DONE
    const val CAPTURE = 5        // int source, int rate -> CAPTURE_DATA* until the app sends STOP or closes
    const val STOP = 6           // stop the current play/capture on this connection
    const val GRANT_SELF = 7     // utf8 package -> RESULT (pm grant WRITE_SECURE_SETTINGS)
    const val SHUTDOWN = 8       // helper exits
    const val DEVICES = 9        // -> RESULT (audio device listing, for diagnostics)

    // helper -> app
    const val WELCOME = 101      // int version, int uid, int sdk, utf8 apkPath
    const val PONG = 102         // utf8 status line
    const val PLAY_STARTED = 103 // utf8 routed device
    const val PLAY_DONE = 104    // utf8 summary
    const val CAPTURE_DATA = 105 // pcm16le
    const val CAPTURE_ENDED = 106 // utf8 summary
    const val RESULT = 107       // utf8 text
    const val ERROR = 199        // utf8 message

    class Frame(val type: Int, val payload: ByteArray) {
        fun text(): String = String(payload, Charsets.UTF_8)
    }

    fun write(out: DataOutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
        synchronized(out) {
            out.writeInt(type)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    fun writeText(out: DataOutputStream, type: Int, text: String) = write(out, type, text.toByteArray(Charsets.UTF_8))

    fun read(input: DataInputStream): Frame {
        val type = input.readInt()
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME) throw IOException("bad frame length $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, payload)
    }
}
