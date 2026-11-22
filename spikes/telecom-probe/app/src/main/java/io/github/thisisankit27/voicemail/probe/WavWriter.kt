package io.github.thisisankit27.voicemail.probe

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit mono PCM WAV writer. The header is re-patched about once a second, so a process killed
 * mid-recording still leaves a valid file covering what had been captured up to that point.
 */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(HEADER_BYTES))
    }
    private var dataBytes = 0L
    private var bytesSincePatch = 0L

    val bytesWritten: Long get() = dataBytes

    fun write(samples: ShortArray, count: Int) {
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
                putInt(16) // PCM fmt chunk size
                putShort(1) // PCM
                putShort(1) // mono
                putInt(sampleRate)
                putInt(sampleRate * 2) // byte rate
                putShort(2) // block align
                putShort(16) // bits per sample
                put("data".toByteArray())
                putInt(dataBytes.toInt())
            }.array()
    }
}
