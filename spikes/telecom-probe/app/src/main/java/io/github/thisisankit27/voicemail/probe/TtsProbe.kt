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

    private val interesting = listOf(Locale("en", "IN"), Locale("hi", "IN"), Locale.US, Locale.UK)

    fun run(context: Context, render: Boolean) {
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
            if (render) renderGreeting(app, tts) else tts.shutdown()
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

    private fun renderGreeting(context: Context, tts: TextToSpeech) {
        val voices = tts.voices.orEmpty()
        val voice = voices.filter { it.offline() && it.locale == Locale("en", "IN") }.maxByOrNull { it.quality }
            ?: voices.filter { it.offline() && it.locale.language == "en" }.maxByOrNull { it.quality }
        if (voice == null) {
            ProbeLog.i("E7 no offline English voice installed; cannot render greeting offline")
            tts.shutdown()
            return
        }
        tts.voice = voice
        val file = GreetingPlayer.greetingFile(context)
        val started = System.currentTimeMillis()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                ProbeLog.i("E7 rendered greeting voice=${voice.name} bytes=${file.length()} in ${System.currentTimeMillis() - started} ms")
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
        val result = tts.synthesizeToFile(GREETING, Bundle(), file, "greeting")
        ProbeLog.i("E7 synthesizeToFile queued result=$result voice=${voice.name}")
    }
}
