package io.github.thisisankit27.voicemail.core

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Beeps and silence generated as PCM, so they can be joined to a greeting into one playback. */
object Tones {
    fun silence(rate: Int, ms: Int) = ShortArray(rate * ms / 1000)

    /** A sine beep with 10 ms fades (no clicks). 1 kHz sits well inside a phone call's 300–3400 Hz band. */
    fun beep(rate: Int, ms: Int = 500, hz: Int = 1000, level: Double = 0.5): ShortArray {
        val n = rate * ms / 1000
        val fade = rate / 100
        return ShortArray(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (sin(2 * PI * hz * i / rate) * level * env * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Greeting, a short pause, then the "start speaking" beep, as one mono clip. */
    fun greetingWithBeep(greeting: Pcm): Pcm {
        val r = greeting.sampleRate
        return Pcm(r, 1, Wav.toMono(greeting).samples + silence(r, 350) + beep(r))
    }

    /** "Time's up": two short beeps. */
    fun endBeeps(rate: Int = 16_000): ShortArray = beep(rate, 250) + silence(rate, 150) + beep(rate, 250)
}
