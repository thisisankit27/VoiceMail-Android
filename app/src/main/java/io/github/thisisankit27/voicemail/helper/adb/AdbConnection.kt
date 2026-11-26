package io.github.thisisankit27.voicemail.helper.adb

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * A minimal ADB host-side client, enough to run one `shell:` command on this same phone.
 *
 * Supports both transports adbd offers:
 * - **Wireless debugging (Android 11+)**: adbd answers CNXN with STLS and the connection upgrades to
 *   TLS 1.3. adbd authorises the client by the public key in its TLS certificate.
 * - **Legacy TCP (`adb tcpip`)**: RSA challenge/response (AUTH). An unknown key makes the phone show
 *   the "Allow debugging?" prompt; "Always allow" stores it in adb_keys, which Wireless debugging
 *   then also accepts.
 *
 * Protocol reference: AOSP packages/modules/adb/protocol.txt.
 */
class AdbConnection private constructor(
    private val socket: Socket,
    private var input: DataInputStream,
    private var output: OutputStream,
) : Closeable {
    private var nextLocalId = 1
    var tls: Boolean = false
        private set
    var deviceBanner: String = ""
        private set

    /** Runs `shell:<command>` and returns everything it printed (stdout+stderr, raw shell). */
    fun shell(command: String, timeoutMs: Int = 30_000): String {
        socket.soTimeout = timeoutMs
        val localId = nextLocalId++
        write(Message(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray()))
        val out = StringBuilder()
        var remoteId = 0
        while (true) {
            val m = read()
            if (m.arg1 != localId) continue
            when (m.command) {
                A_OKAY -> remoteId = m.arg0
                A_WRTE -> {
                    out.append(String(m.data))
                    write(Message(A_OKAY, localId, m.arg0, ByteArray(0)))
                }
                A_CLSE -> {
                    if (remoteId != 0) write(Message(A_CLSE, localId, remoteId, ByteArray(0)))
                    return out.toString()
                }
            }
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) { }
    }

    private fun write(m: Message) {
        output.write(m.encode())
        output.flush()
    }

    private fun read(): Message = Message.read(input)

    /** Thrown when adbd refuses this app's key (not paired / not "always allowed"). */
    class NotAuthorizedException(message: String) : IOException(message)

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_STLS = 0x534c5453
        private const val A_VERSION = 0x01000001
        private const val A_STLS_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 1024 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        /**
         * Connects and authenticates. [onKeyPrompt] is called when the phone is showing the
         * "Allow debugging?" prompt for this key (legacy transport only); the call then waits up to
         * [promptTimeoutMs] for the user's answer.
         */
        fun connect(
            host: String,
            port: Int,
            key: AdbKey,
            connectTimeoutMs: Int = 5_000,
            promptTimeoutMs: Int = 60_000,
            onKeyPrompt: () -> Unit = {},
        ): AdbConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = 10_000
            val conn = AdbConnection(socket, DataInputStream(BufferedInputStream(socket.getInputStream())), socket.getOutputStream())
            try {
                conn.handshake(host, port, key, promptTimeoutMs, onKeyPrompt)
            } catch (e: Exception) {
                conn.close()
                throw e
            }
            return conn
        }

        /** True if [port] on 127.0.0.1 is adbd's Wireless debugging server (answers CNXN with STLS). */
        fun answersTls(port: Int): Boolean = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                socket.soTimeout = 1_000
                socket.getOutputStream().apply {
                    write(Message(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray()).encode())
                    flush()
                }
                Message.read(DataInputStream(BufferedInputStream(socket.getInputStream()))).command == A_STLS
            }
        } catch (_: Exception) {
            false
        }

        private fun AdbConnection.handshake(host: String, port: Int, key: AdbKey, promptTimeoutMs: Int, onKeyPrompt: () -> Unit) {
            write(Message(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::features=shell_v2,cmd\u0000".toByteArray()))
            var signed = false
            while (true) {
                val m = try {
                    read()
                } catch (e: SocketTimeoutException) {
                    throw NotAuthorizedException("adbd did not answer (prompt not accepted, or key not authorised)")
                } catch (e: SSLException) {
                    // TLS 1.3: the client side of the handshake completes before adbd checks our
                    // certificate, so an unknown key only shows up here, as a CERTIFICATE_UNKNOWN
                    // alert on the first read (seen on the G05 after Developer options were off).
                    if (!tls) throw e
                    throw NotAuthorizedException("TLS refused: this app's key is not authorised for Wireless debugging (${e.message})")
                }
                when (m.command) {
                    A_STLS -> {
                        write(Message(A_STLS, A_STLS_VERSION, 0, ByteArray(0)))
                        val ssl = tlsContext(key).socketFactory.createSocket(socket, host, port, true) as SSLSocket
                        ssl.useClientMode = true
                        try {
                            ssl.startHandshake()
                        } catch (e: IOException) {
                            throw NotAuthorizedException("TLS refused: this app's key is not authorised for Wireless debugging (${e.message})")
                        }
                        input = DataInputStream(BufferedInputStream(ssl.inputStream))
                        output = ssl.outputStream
                        tls = true
                    }
                    A_AUTH -> if (m.arg0 == AUTH_TOKEN) {
                        if (!signed) {
                            signed = true
                            write(Message(A_AUTH, AUTH_SIGNATURE, 0, key.signToken(m.data)))
                        } else {
                            write(Message(A_AUTH, AUTH_RSAPUBLICKEY, 0, (key.adbPublicKey() + "\u0000").toByteArray()))
                            socket.soTimeout = promptTimeoutMs
                            onKeyPrompt()
                        }
                    }
                    A_CNXN -> {
                        deviceBanner = String(m.data).trimEnd('\u0000')
                        socket.soTimeout = 10_000
                        return
                    }
                    else -> Unit
                }
            }
        }

        private fun tlsContext(key: AdbKey): SSLContext {
            val keyManager = object : X509ExtendedKeyManager() {
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
                override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
                override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(key.certificate)
                override fun getPrivateKey(alias: String?): PrivateKey = key.privateKey
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            }
            // adbd's certificate is self-signed and per-device; there is no CA to check it against.
            // The connection only ever goes to 127.0.0.1, i.e. this phone's own adbd.
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(trustAll), null) }
        }

        private const val ALIAS = "adb"
    }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(data.size)
            putInt(data.fold(0) { sum, b -> sum + (b.toInt() and 0xff) })
            putInt(command.inv())
            put(data)
        }.array()

        companion object {
            fun read(input: InputStream): Message {
                val header = ByteArray(24)
                DataInputStream(input).readFully(header)
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val command = bb.int
                val arg0 = bb.int
                val arg1 = bb.int
                val length = bb.int
                if (length < 0 || length > MAX_PAYLOAD) throw IOException("bad adb payload length $length")
                val data = ByteArray(length)
                DataInputStream(input).readFully(data)
                return Message(command, arg0, arg1, data)
            }
        }
    }
}
