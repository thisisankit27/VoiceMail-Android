package io.github.thisisankit27.voicemail.probe

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** E7 instrument: which voices are usable offline, and does `synthesizeToFile` produce a greeting? */
object TtsProbe {
    const val GREETING =
        "Hello! You've reached the voicemail probe. I'm unable to answer your call right now. " +
            "Please leave a message after the tone."

    /** Spoken by the loopback "caller" (proxy downlink), in a different voice from the greeting. */
    const val CALLER =
        "Hi, this is a simulated caller from the loopback test. I am leaving a test message. " +
            "Please call me back when you are free. Thank you, bye."

    /**
     * Owner choice (2026-11-22): a male, confident greeting. Measured median F0 on the G60:
     * en-in-x-end-local 138 Hz (deepest male), ene 154 Hz (male), ena/enc/en-IN-language 216–250 Hz (female).
     * "Confident" = the deeper male voice, slightly lower pitch and slightly slower delivery.
     */
    enum class Render(val text: String, val preferredVoice: String, val pitch: Float, val rate: Float) {
        GREETING(TtsProbe.GREETING, "en-in-x-end-local", 0.92f, 0.95f),
        CALLER(TtsProbe.CALLER, "en-in-x-ena-local", 1f, 1f),
    }

    private val interesting = listOf(Locale("en", "IN"), Locale("hi", "IN"), Locale.US, Locale.UK)

    /** Overrides for a render: a specific voice, pitch and speech rate (1.0 = engine default). */
    data class Style(val voiceName: String? = null, val pitch: Float? = null, val rate: Float? = null)

    /** [render] null = only report the voice inventory. */
    fun run(context: Context, render: Render?, style: Style = Style()) {
        val app = context.applicationContext
        var holder: TextToSpeech? = null
        holder = TextToSpeech(app) { status ->
            val tts = holder ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                ProbeLog.i("E7 TTS init failed status=$status")
                tts.shutdown()
                return@TextToSpeech
            }
            report(tts)
            if (render != null) render(app, tts, render, style) else tts.shutdown()
        }
    }

    private fun Voice.offline(): Boolean =
        !isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features

    private fun report(tts: TextToSpeech) {
        val voices = tts.voices.orEmpty()
        ProbeLog.i(
            "E7 engine=${tts.defaultEngine} engines=${tts.engines.joinToString { it.name }} " +
                "voices=${voices.size} offlineInstalled=${voices.count { it.offline() }}",
        )
        for (locale in interesting) {
            val forLocale = voices.filter { it.locale == locale }.sortedBy { it.name }
            ProbeLog.i("E7 ${locale.toLanguageTag()} available=${tts.isLanguageAvailable(locale)} voices=${forLocale.size}")
            forLocale.forEach {
                ProbeLog.i(
                    "E7   ${it.name} quality=${it.quality} latency=${it.latency} network=${it.isNetworkConnectionRequired} " +
                        "notInstalled=${TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features}",
                )
            }
        }
    }

    /** Renders [GREETING] once per installed offline en-IN voice into files/samples/<voice>.wav. */
    fun renderSamples(context: Context) {
        val app = context.applicationContext
        var holder: TextToSpeech? = null
        holder = TextToSpeech(app) { status ->
            val tts = holder ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { tts.shutdown(); return@TextToSpeech }
            val voices = tts.voices.orEmpty().filter { it.offline() && it.locale == Locale("en", "IN") }.sortedBy { it.name }
            val dir = java.io.File(app.filesDir, "samples").apply { mkdirs() }
            var remaining = voices.size
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    ProbeLog.i("E7 sample rendered $utteranceId")
                    if (--remaining == 0) { ProbeLog.i("E7 samples done (${voices.size})"); tts.shutdown() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (--remaining == 0) tts.shutdown() }
            })
            for (v in voices) {
                tts.voice = v
                tts.synthesizeToFile(GREETING, Bundle(), java.io.File(dir, "${v.name}.wav"), v.name)
            }
            ProbeLog.i("E7 queued ${voices.size} samples: ${voices.joinToString { it.name }}")
        }
    }

    private fun render(context: Context, tts: TextToSpeech, what: Render, style: Style) {
        val voices = tts.voices.orEmpty()
        val voice = voices.firstOrNull { it.offline() && it.name == (style.voiceName ?: what.preferredVoice) }
            ?: voices.filter { it.offline() && it.locale == Locale("en", "IN") }.maxByOrNull { it.quality }
            ?: voices.filter { it.offline() && it.locale.language == "en" }.maxByOrNull { it.quality }
        if (voice == null) {
            ProbeLog.i("E7 no offline English voice installed; cannot render greeting offline")
            tts.shutdown()
            return
        }
        tts.voice = voice
        val pitch = style.pitch ?: what.pitch
        val rate = style.rate ?: what.rate
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        ProbeLog.i("E7 render style voice=${voice.name} pitch=$pitch rate=$rate")
        val file = if (what == Render.GREETING) GreetingPlayer.greetingFile(context) else LoopbackConnection.callerFile(context)
        val started = System.currentTimeMillis()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                ProbeLog.i("E7 rendered ${what.name.lowercase()} voice=${voice.name} bytes=${file.length()} in ${System.currentTimeMillis() - started} ms")
                tts.shutdown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                ProbeLog.i("E7 render failed")
                tts.shutdown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                ProbeLog.i("E7 render failed code=$errorCode")
                tts.shutdown()
            }
        })
        val result = tts.synthesizeToFile(what.text, Bundle(), file, what.name)
        ProbeLog.i("E7 synthesizeToFile queued result=$result voice=${voice.name}")
    }
}
