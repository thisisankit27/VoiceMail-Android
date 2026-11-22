package io.github.thisisankit27.voicemail.helper.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher

class AdbKeyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `key is created once and reloaded identically`() {
        val a = AdbKey.loadOrCreate(tmp.root)
        val b = AdbKey.loadOrCreate(tmp.root)
        assertEquals(a.publicKey, b.publicKey)
    }

    @Test fun `android public key struct matches libcrypto_utils layout`() {
        val key = AdbKey.loadOrCreate(tmp.root)
        val bytes = AdbKey.encodeAndroidPublicKey(key.publicKey)
        assertEquals(524, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.int)
        val n0inv = bb.int.toLong() and 0xffffffffL
        val n = key.publicKey.modulus
        // n0inv * n[0] == -1 (mod 2^32)
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32.subtract(BigInteger.ONE), n.mod(r32).multiply(BigInteger.valueOf(n0inv)).mod(r32))
        // modulus words round-trip
        val words = ByteArray(256).also { bb.get(it) }
        assertEquals(n, BigInteger(1, words.reversedArray()))
        val rr = ByteArray(256).also { bb.get(it) }
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, rr.reversedArray()))
        assertEquals(65537, bb.int)
    }

    @Test fun `token signature is PKCS1 over a SHA-1 DigestInfo of the raw token`() {
        val key = AdbKey.loadOrCreate(tmp.root)
        val token = ByteArray(20) { it.toByte() }
        val signature = key.signToken(token)
        val recovered = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, key.publicKey)
            doFinal(signature)
        }
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        assertArrayEquals(prefix + token, recovered)
    }

    @Test fun `self-signed certificate parses and carries the adb public key`() {
        val key = AdbKey.loadOrCreate(tmp.root)
        val der = SelfSignedCert.build(key.publicKey, key.privateKey, "voicemail-app")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        assertEquals(key.publicKey, cert.publicKey)
        cert.verify(key.publicKey) // signature over the TBS is valid
        cert.checkValidity()
    }
}
