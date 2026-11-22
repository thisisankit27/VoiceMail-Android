package io.github.thisisankit27.voicemail.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `write then read round-trips samples and rate`() {
        val samples = ShortArray(16_000) { (it % 200 - 100).toShort() }
        val f = File(tmp.root, "a.wav")
        Wav.write(f, Pcm(16_000, 1, samples))
        val back = Wav.read(f)
        assertEquals(16_000, back.sampleRate)
        assertEquals(1, back.channels)
        assertArrayEquals(samples, back.samples)
        assertEquals(1000, back.durationMs)
    }

    @Test fun `reader skips extra chunks before data`() {
        val f = File(tmp.root, "b.wav")
        Wav.write(f, Pcm(24_000, 1, ShortArray(10) { 7 }))
        val bytes = f.readBytes()
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        val patched = bytes.copyOfRange(0, 36) + list + bytes.copyOfRange(36, bytes.size)
        f.writeBytes(patched)
        val back = Wav.read(f)
        assertEquals(24_000, back.sampleRate)
        assertEquals(10, back.samples.size)
    }

    @Test fun `a partially written file is still valid`() {
        val f = File(tmp.root, "c.wav")
        val w = WavWriter(f, 16_000)
        w.write(ShortArray(40_000) { 1 }) // > 1 s, so the header was patched mid-way
        // no close(): simulate a killed process
        val back = Wav.read(f)
        assertTrue(back.samples.size >= 16_000)
    }

    @Test fun `rms of silence is the floor and of full scale is about 0 dBFS`() {
        assertEquals(Wav.SILENT_DB, Wav.rmsDbfs(ShortArray(100)), 0.0)
        assertEquals(0.0, Wav.rmsDbfs(ShortArray(100) { Short.MAX_VALUE }), 0.01)
    }

    @Test fun `stereo is down-mixed to mono`() {
        val mono = Wav.toMono(Pcm(8000, 2, shortArrayOf(100, 300, -100, -300)))
        assertArrayEquals(shortArrayOf(200, -200), mono.samples)
    }
}
