package io.github.thisisankit27.voicemail.helper.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * The app's own ADB identity: an RSA-2048 key pair, exactly like the `~/.android/adbkey` a PC uses.
 * Once the phone has authorised this key ("Always allow"), the app can open an ADB shell on its own
 * device over Wireless debugging, which is how the shell-identity audio helper is started without a PC.
 *
 * The private key lives in no-backup app storage and never leaves the device.
 */
class AdbKey private constructor(
    val privateKey: PrivateKey,
    val publicKey: RSAPublicKey,
) {
    /** Self-signed certificate presented as the TLS client certificate (adbd checks only the key). */
    val certificate: X509Certificate by lazy {
        val der = SelfSignedCert.build(publicKey, privateKey, "voicemail-app")
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    /** Signs an AUTH token the way adbd expects: RSA PKCS#1 v1.5 over a SHA-1 DigestInfo of the raw token. */
    fun signToken(token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(privateKey)
            update(SHA1_DIGEST_INFO)
            update(token)
            sign()
        }

    /** The public key in adb's text format: base64(android RSAPublicKey struct) + " " + name. */
    fun adbPublicKey(name: String = "voicemail-app"): String =
        Base64.encodeToString(encodeAndroidPublicKey(publicKey), Base64.NO_WRAP) + " " + name

    /** Short fingerprint for display (MD5 of the struct, like the system prompt shows). */
    fun fingerprint(): String =
        java.security.MessageDigest.getInstance("MD5").digest(encodeAndroidPublicKey(publicKey))
            .joinToString(":") { "%02X".format(it) }

    companion object {
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        private const val KEY_WORDS = 64 // 2048 bits / 32

        /** Loads the key from [dir], generating and storing a new one on first use. */
        @Synchronized
        fun loadOrCreate(dir: File): AdbKey {
            val file = File(dir, "adbkey.pk8")
            val factory = KeyFactory.getInstance("RSA")
            if (!file.exists()) {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                dir.mkdirs()
                val tmp = File(dir, "adbkey.pk8.tmp")
                tmp.writeBytes(pair.private.encoded)
                tmp.renameTo(file)
            }
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
            val crt = private as java.security.interfaces.RSAPrivateCrtKey
            val public = factory.generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent)) as RSAPublicKey
            return AdbKey(private, public)
        }

        /**
         * Android's `RSAPublicKey` struct (system/core/libcrypto_utils): word count, n0inv, modulus
         * (little-endian words), R^2 mod n (little-endian words), exponent. 524 bytes for RSA-2048.
         */
        fun encodeAndroidPublicKey(key: RSAPublicKey): ByteArray {
            val n = key.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
            val rr = BigInteger.ONE.shiftLeft(KEY_WORDS * 32 * 2).mod(n)
            val buffer = ByteBuffer.allocate(4 + 4 + KEY_WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(KEY_WORDS)
            buffer.putInt(n0inv)
            putLittleEndianWords(buffer, n)
            putLittleEndianWords(buffer, rr)
            buffer.putInt(key.publicExponent.toInt())
            return buffer.array()
        }

        private fun putLittleEndianWords(buffer: ByteBuffer, value: BigInteger) {
            val mask = BigInteger.valueOf(0xffffffffL)
            var v = value
            repeat(KEY_WORDS) {
                buffer.putInt(v.and(mask).toInt())
                v = v.shiftRight(32)
            }
        }
    }
}
