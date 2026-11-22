package io.github.thisisankit27.voicemail.greeting

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.core.Pcm
import io.github.thisisankit27.voicemail.core.Wav
import io.github.thisisankit27.voicemail.data.AppSettings
import io.github.thisisankit27.voicemail.data.GreetingSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/** An installed TTS voice the greeting can use. */
data class VoiceOption(val name: String, val locale: Locale, val offline: Boolean, val quality: Int) {
    val label: String get() = "${locale.displayLanguage} (${locale.displayCountry}) · ${name.substringAfterLast("-x-").substringBefore("-")}"
}

enum class GreetingKind { MESSAGE, CLOSING }

/**
 * Owns the greeting audio files:
 * - `greetings/message.wav` and `greetings/closing.wav` rendered offline with TextToSpeech
 * - `greetings/recorded.wav` when the owner recorded their own greeting
 * A small stamp file next to each render records what it was rendered from, so a changed text,
 * name or voice triggers a re-render before the next call.
 */
class GreetingRepository(private val context: Context) {
    private val dir = File(context.filesDir, "greetings").apply { mkdirs() }
    private val mutex = Mutex()

    fun file(kind: GreetingKind): File = File(dir, if (kind == GreetingKind.MESSAGE) "message.wav" else "closing.wav")
    val recordedFile: File get() = File(dir, "recorded.wav")

    fun text(settings: AppSettings, kind: GreetingKind): String {
        val template = if (kind == GreetingKind.MESSAGE) settings.greetingText else settings.closingText
        val name = settings.ownerName.ifBlank { "me" }
        return template.replace("{name}", name)
    }

    private fun stamp(settings: AppSettings, kind: GreetingKind) =
        "${text(settings, kind)}|${settings.voiceName}|${settings.voicePitch}|${settings.voiceRate}"

    private fun stampFile(kind: GreetingKind) = File(dir, "${file(kind).name}.stamp")

    fun isRendered(settings: AppSettings, kind: GreetingKind): Boolean =
        file(kind).exists() && stampFile(kind).takeIf { it.exists() }?.readText() == stamp(settings, kind)

    /**
     * Returns the PCM to play for this call: the recorded greeting when chosen (MESSAGE only),
     * otherwise the TTS render, re-rendering first if it is stale.
     */
    suspend fun pcmFor(settings: AppSettings, kind: GreetingKind): Pcm? = withContext(Dispatchers.IO) {
        if (kind == GreetingKind.MESSAGE && settings.greetingSource == GreetingSource.RECORDED && recordedFile.exists()) {
            return@withContext runCatching { Wav.toMono(Wav.read(recordedFile)) }.getOrNull()
        }
        if (!isRendered(settings, kind)) render(settings, kind)
        runCatching { Wav.toMono(Wav.read(file(kind))) }.onFailure { AppLog.w("greeting unreadable", it) }.getOrNull()
    }

    /** Renders one greeting with the configured voice. Returns true on success. */
    suspend fun render(settings: AppSettings, kind: GreetingKind): Boolean = mutex.withLock {
        val target = file(kind)
        val tmp = File(dir, "${target.name}.tmp")
        val ok = synthesize(text(settings, kind), settings.voiceName, settings.voicePitch, settings.voiceRate, tmp)
        if (ok && tmp.length() > 44) {
            tmp.renameTo(target)
            stampFile(kind).writeText(stamp(settings, kind))
            AppLog.i("greeting ${kind.name.lowercase()} rendered (${target.length()} bytes)")
        } else {
            tmp.delete()
            AppLog.w("greeting ${kind.name.lowercase()} render failed")
        }
        ok
    }

    suspend fun renderAll(settings: AppSettings) {
        render(settings, GreetingKind.MESSAGE)
        render(settings, GreetingKind.CLOSING)
    }

    /** Offline-capable voices, best first: en-IN, then other English, then Hindi. */
    suspend fun voices(): List<VoiceOption> = withTts { tts ->
        tts.voices.orEmpty()
            .filter { it.locale.language in setOf("en", "hi") }
            .map { VoiceOption(it.name, it.locale, it.isOffline(), it.quality) }
            .sortedWith(compareBy({ !it.offline }, { it.locale.country != "IN" }, { it.locale.language != "en" }, { it.name }))
    } ?: emptyList()

    private fun Voice.isOffline() = !isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features

    private suspend fun synthesize(text: String, voiceName: String, pitch: Float, rate: Float, out: File): Boolean =
        withTts { tts ->
            val voices = tts.voices.orEmpty()
            val voice = voices.firstOrNull { it.name == voiceName && it.isOffline() }
                ?: voices.filter { it.isOffline() && it.locale == Locale("en", "IN") }.maxByOrNull { it.quality }
                ?: voices.filter { it.isOffline() && it.locale.language == "en" }.maxByOrNull { it.quality }
                ?: return@withTts false
            tts.voice = voice
            tts.setPitch(pitch)
            tts.setSpeechRate(rate)
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
            })
            if (tts.synthesizeToFile(text, Bundle(), out, "greeting") != TextToSpeech.SUCCESS) return@withTts false
            withTimeout(30_000) { done.await() }
        } ?: false

    /** Speaks a voice sample through the phone speaker (voice picker preview). */
    suspend fun preview(text: String, voiceName: String, pitch: Float, rate: Float) {
        withTts { tts ->
            tts.voices.orEmpty().firstOrNull { it.name == voiceName }?.let { tts.voice = it }
            tts.setPitch(pitch)
            tts.setSpeechRate(rate)
            val done = CompletableDeferred<Unit>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(Unit) }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
            withTimeout(60_000) { done.await() }
        }
    }

    /** Opens a TextToSpeech engine, runs [block], and always shuts the engine down. */
    private suspend fun <T> withTts(block: suspend (TextToSpeech) -> T): T? {
        val ready = CompletableDeferred<Int>()
        val tts = withContext(Dispatchers.Main) { TextToSpeech(context.applicationContext) { ready.complete(it) } }
        return try {
            if (withTimeout(10_000) { ready.await() } != TextToSpeech.SUCCESS) null else block(tts)
        } catch (e: Exception) {
            AppLog.w("TTS failed", e)
            null
        } finally {
            tts.shutdown()
        }
    }
}
