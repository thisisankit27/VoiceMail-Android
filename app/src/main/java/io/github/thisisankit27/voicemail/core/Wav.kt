package io.github.thisisankit27.voicemail.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 16-bit mono PCM WAV writer. The header is re-patched about once a second, so a process killed
 * mid-recording still leaves a valid file covering what was captured up to that point.
 */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(HEADER_BYTES))
    }
    private var dataBytes = 0L
    private var bytesSincePatch = 0L

    val bytesWritten: Long get() = dataBytes
    val durationMs: Long get() = dataBytes / 2 * 1000 / sampleRate

    fun write(samples: ShortArray, count: Int = samples.size) {
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(samples[i])
        raf.write(buffer.array())
        dataBytes += count * 2
        bytesSincePatch += count * 2
        if (bytesSincePatch >= sampleRate * 2L) {
            patchHeader()
            bytesSincePatch = 0
        }
    }

    private fun patchHeader() {
        val position = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate))
        raf.seek(position)
    }

    override fun close() {
        patchHeader()
        raf.close()
    }

    companion object {
        const val HEADER_BYTES = 44

        fun header(dataBytes: Long, sampleRate: Int): ByteArray =
            ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt((36 + dataBytes).toInt())
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(dataBytes.toInt())
            }.array()
    }
}

/** Decoded 16-bit PCM. */
class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val durationMs: Long get() = samples.size.toLong() * 1000 / channels / sampleRate
}

object Wav {
    /** Reads a 16-bit PCM WAV, walking the chunks (TTS engines may add LIST chunks before "data"). */
    fun read(file: File): Pcm {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var rate = 16_000
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(bits == 16) { "only 16-bit PCM is supported" }
                val len = minOf(if (size <= 0) bytes.size - body else size, bytes.size - body)
                val samples = ShortArray(len / 2)
                ByteBuffer.wrap(bytes, body, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                return Pcm(rate, channels, samples)
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk")
    }

    fun write(file: File, pcm: Pcm) {
        WavWriter(file, pcm.sampleRate).use { it.write(pcm.samples) }
    }

    const val SILENT_DB = -96.0

    fun rmsDbfs(samples: ShortArray, count: Int = samples.size): Double {
        if (count == 0) return SILENT_DB
        var sum = 0.0
        for (i in 0 until count) { val s = samples[i].toDouble(); sum += s * s }
        val rms = sqrt(sum / count)
        return if (rms < 1.0) SILENT_DB else 20 * log10(rms / Short.MAX_VALUE)
    }

    /** Scales so the peak sits at [targetPeak] (0..1); used to even out recorded greetings. */
    fun normalize(pcm: Pcm, targetPeak: Double = 0.85): Pcm {
        var peak = 1
        for (s in pcm.samples) peak = max(peak, kotlin.math.abs(s.toInt()))
        val gain = targetPeak * Short.MAX_VALUE / peak
        if (gain in 0.95..1.05) return pcm
        val out = ShortArray(pcm.samples.size) { (pcm.samples[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
        return Pcm(pcm.sampleRate, pcm.channels, out)
    }

    /** Down-mixes to mono so the in-call path gets the simplest format. */
    fun toMono(pcm: Pcm): Pcm {
        if (pcm.channels == 1) return pcm
        val frames = pcm.samples.size / pcm.channels
        val out = ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until pcm.channels) sum += pcm.samples[f * pcm.channels + c]
            (sum / pcm.channels).toShort()
        }
        return Pcm(pcm.sampleRate, 1, out)
    }
}
