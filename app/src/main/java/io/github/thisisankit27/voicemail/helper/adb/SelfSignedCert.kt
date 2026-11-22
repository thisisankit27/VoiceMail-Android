package io.github.thisisankit27.voicemail.helper.adb

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal DER encoder for a self-signed X.509 v1 certificate (SHA256withRSA). adbd ignores the
 * certificate's contents and only compares its public key with the authorised keys, so a tiny
 * hand-built certificate avoids pulling in BouncyCastle.
 */
internal object SelfSignedCert {
    private val SHA256_WITH_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun build(publicKey: RSAPublicKey, privateKey: PrivateKey, cn: String): ByteArray {
        val algorithm = sequence(tlv(0x06, SHA256_WITH_RSA), byteArrayOf(0x05, 0x00))
        val name = sequence(tlv(0x31, sequence(tlv(0x06, COMMON_NAME), tlv(0x0c, cn.toByteArray()))))
        val now = System.currentTimeMillis()
        val validity = sequence(utcTime(Date(now - DAY)), utcTime(Date(now + 20 * 365 * DAY)))
        val tbs = sequence(
            tlv(0x02, BigInteger.valueOf(now).toByteArray()),
            algorithm,
            name,
            validity,
            name,
            publicKey.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        return sequence(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
    }

    private const val DAY = 24L * 60 * 60 * 1000

    private fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(date).toByteArray())
    }

    private fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = value.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xff); out.write(len and 0xff) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
