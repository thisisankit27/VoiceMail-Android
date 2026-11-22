package io.github.thisisankit27.voicemail.greeting

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import io.github.thisisankit27.voicemail.core.Pcm
import io.github.thisisankit27.voicemail.core.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Result of importing an audio file as a greeting. */
data class ImportedGreeting(val pcm: Pcm, val sourceName: String, val originalMs: Long, val trimmedToMax: Boolean)

/**
 * Turns any audio file the phone can play (MP3, M4A/AAC, WAV, OGG/Opus, FLAC, AMR…) into a
 * greeting: decoded on the phone with MediaExtractor + MediaCodec, mixed to mono, silence trimmed
 * at both ends, capped at [MAX_MS] and peak-normalised. Nothing leaves the phone.
 */
object AudioImport {
    const val MAX_MS = 60_000L

    suspend fun import(context: Context, uri: Uri): ImportedGreeting = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val decoded = decode(context, uri)
        val originalMs = decoded.durationMs
        val trimmed = Wav.trimSilence(Wav.toMono(decoded))
        val maxSamples = (trimmed.sampleRate * MAX_MS / 1000).toInt()
        val capped = trimmed.samples.size > maxSamples
        val body = if (capped) Pcm(trimmed.sampleRate, 1, fadeOut(trimmed.samples.copyOf(maxSamples), trimmed.sampleRate)) else trimmed
        if (body.durationMs < 500) throw IOException("The file has no audible sound.")
        ImportedGreeting(Wav.normalize(body), name, originalMs, capped)
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "audio file"

    /** Decodes the first audio track to interleaved 16-bit PCM. */
    private suspend fun decode(context: Context, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("That file has no audio.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val out = ShortBuilder()
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                // Stop decoding a little past the cap: no need to decode a whole song.
                val limitFrames = { rate.toLong() * (MAX_MS + 30_000) / 1000 }
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0 || out.size / channels > limitFrames()) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val o = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        else -> if (o >= 0) {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val fb = buf.asFloatBuffer()
                                    while (fb.hasRemaining()) out.add((fb.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                                }
                                else -> {
                                    val sb = buf.asShortBuffer()
                                    while (sb.hasRemaining()) out.add(sb.get())
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (out.size == 0) throw IOException("Couldn't decode that file.")
            return Pcm(rate, channels, out.toArray())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("That file type isn't supported (${e.javaClass.simpleName}).", e)
        } finally {
            extractor.release()
        }
    }

    /** Half-second fade so a capped file doesn't end mid-word with a click. */
    private fun fadeOut(s: ShortArray, rate: Int): ShortArray {
        val n = minOf(s.size, rate / 2)
        for (i in 0 until n) {
            val idx = s.size - n + i
            s[idx] = (s[idx] * (1.0 - i.toDouble() / n)).toInt().toShort()
        }
        return s
    }

    /** Growable ShortArray without boxing. */
    private class ShortBuilder {
        private var data = ShortArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}
