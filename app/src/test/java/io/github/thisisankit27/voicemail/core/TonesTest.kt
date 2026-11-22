package io.github.thisisankit27.voicemail.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonesTest {
    @Test fun `greeting and beep are one clip at the greeting's rate`() {
        val greeting = Pcm(24_000, 1, ShortArray(24_000)) // 1 s
        val clip = Tones.greetingWithBeep(greeting)
        assertEquals(24_000, clip.sampleRate)
        assertEquals(1000 + 350 + 500, clip.durationMs.toInt())
        // the beep is audible (not silence) and sits at the end
        val tail = clip.samples.copyOfRange(clip.samples.size - 12_000, clip.samples.size)
        assertTrue(Wav.rmsDbfs(tail) > -12)
    }

    @Test fun `beep fades in and out without clicks`() {
        val b = Tones.beep(16_000)
        assertEquals(0, b.first().toInt())
        assertTrue(kotlin.math.abs(b.last().toInt()) < 400)
    }

    @Test fun `end beeps are two beeps with a gap`() {
        val e = Tones.endBeeps(16_000)
        assertEquals(16_000 * 650 / 1000, e.size)
        assertEquals(Wav.SILENT_DB, Wav.rmsDbfs(e.copyOfRange(4_000 + 400, 4_000 + 2_000)), 0.0)
    }
}
