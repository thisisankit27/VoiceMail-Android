package io.github.thisisankit27.voicemail.helper

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the helper reported about itself when a connection was accepted. */
data class HelperInfo(val version: Int, val uid: Int, val sdk: Int, val apkPath: String)

/**
 * App-side client for [HelperMain]. All calls block, so callers run them on an IO dispatcher.
 * Every operation opens its own short connection.
 */
class HelperClient(private val token: () -> String) {

    /** Returns the helper's info, or null when no helper is answering. */
    fun ping(): HelperInfo? = try {
        open().use { c ->
            HelperProtocol.write(c.out, HelperProtocol.PING)
            val pong = HelperProtocol.read(c.input)
            if (pong.type == HelperProtocol.PONG) c.info else null
        }
    } catch (e: IOException) {
        null
    }

    fun shutdown() = try {
        open().use { c ->
            HelperProtocol.write(c.out, HelperProtocol.SHUTDOWN)
            HelperProtocol.read(c.input)
        }
        Unit
    } catch (_: IOException) { }

    fun grantSecureSettings(): String = open().use { c ->
        HelperProtocol.write(c.out, HelperProtocol.GRANT_SELF)
        HelperProtocol.read(c.input).text()
    }

    fun devices(): String = open().use { c ->
        HelperProtocol.write(c.out, HelperProtocol.DEVICES)
        HelperProtocol.read(c.input).text()
    }

    /** Starts playing PCM into the call. The returned handle blocks in [Playback.awaitDone]. */
    fun play(pcm: ShortArray, rate: Int, channels: Int = 1, gain: Float = 1f): Playback {
        val c = open()
        val body = ByteBuffer.allocate(12 + pcm.size * 2)
        body.putInt(rate).putInt(channels).putFloat(gain)
        body.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        HelperProtocol.write(c.out, HelperProtocol.PLAY, body.array())
        return Playback(c)
    }

    fun tone(hz: Int, ms: Int): Playback {
        val c = open()
        HelperProtocol.write(c.out, HelperProtocol.TONE, ByteBuffer.allocate(8).putInt(hz).putInt(ms).array())
        return Playback(c)
    }

    /** Starts recording [source] (3 = VOICE_DOWNLINK). Read chunks with [Capture.read]. */
    fun capture(source: Int, rate: Int): Capture {
        val c = open()
        HelperProtocol.write(c.out, HelperProtocol.CAPTURE, ByteBuffer.allocate(8).putInt(source).putInt(rate).array())
        return Capture(c)
    }

    class Playback internal constructor(private val c: Conn) : Closeable {
        /** Blocks until playback finished or was stopped; returns the helper's summary line. */
        fun awaitDone(): String {
            while (true) {
                val f = HelperProtocol.read(c.input)
                when (f.type) {
                    HelperProtocol.PLAY_STARTED -> Unit
                    HelperProtocol.PLAY_DONE -> return f.text()
                    HelperProtocol.ERROR -> throw IOException("helper: ${f.text()}")
                }
            }
        }

        fun stop() = try { HelperProtocol.write(c.out, HelperProtocol.STOP) } catch (_: IOException) { }
        override fun close() = c.close()
    }

    class Capture internal constructor(private val c: Conn) : Closeable {
        /** Returns the next PCM chunk (~100 ms), or null when the capture ended. */
        fun read(): ShortArray? {
            val f = HelperProtocol.read(c.input)
            return when (f.type) {
                HelperProtocol.CAPTURE_DATA -> {
                    val out = ShortArray(f.payload.size / 2)
                    ByteBuffer.wrap(f.payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
                    out
                }
                HelperProtocol.ERROR -> throw IOException("helper: ${f.text()}")
                else -> null
            }
        }

        fun stop() = try { HelperProtocol.write(c.out, HelperProtocol.STOP) } catch (_: IOException) { }
        override fun close() = c.close()
    }

    internal class Conn(val socket: Socket, val input: DataInputStream, val out: DataOutputStream, val info: HelperInfo) : Closeable {
        override fun close() = try { socket.close() } catch (_: IOException) { }
    }

    private fun open(): Conn {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", HelperProtocol.PORT), 1_500)
            socket.soTimeout = 5_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            HelperProtocol.writeText(out, HelperProtocol.HELLO, token())
            val welcome = HelperProtocol.read(input)
            if (welcome.type != HelperProtocol.WELCOME) throw IOException("unexpected frame ${welcome.type}")
            val bb = ByteBuffer.wrap(welcome.payload)
            val info = HelperInfo(bb.int, bb.int, bb.int, String(welcome.payload, 12, welcome.payload.size - 12))
            socket.soTimeout = 0
            return Conn(socket, input, out, info)
        } catch (e: IOException) {
            try { socket.close() } catch (_: IOException) { }
            throw e
        }
    }
}
