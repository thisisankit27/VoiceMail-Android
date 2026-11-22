package io.github.thisisankit27.voicemail.helper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class HelperProtocolTest {
    @Test fun `frames round-trip`() {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        HelperProtocol.writeText(out, HelperProtocol.HELLO, "secret")
        HelperProtocol.write(out, HelperProtocol.PING)
        val input = DataInputStream(ByteArrayInputStream(buf.toByteArray()))
        val a = HelperProtocol.read(input)
        assertEquals(HelperProtocol.HELLO, a.type)
        assertEquals("secret", a.text())
        assertEquals(HelperProtocol.PING, HelperProtocol.read(input).type)
    }

    @Test(expected = IOException::class)
    fun `oversized frames are rejected`() {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).apply { writeInt(1); writeInt(HelperProtocol.MAX_FRAME + 1) }
        HelperProtocol.read(DataInputStream(ByteArrayInputStream(buf.toByteArray())))
    }
}
