package io.github.thisisankit27.voicemail.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Who the voicemail answers for. Contacts never reach voicemail in UNKNOWN_ONLY. */
enum class RoutingMode { EVERYONE, UNKNOWN_ONLY }

enum class GreetingSource { TTS, RECORDED }

data class AppSettings(
    /** Master switch: answer unanswered calls and take a message. */
    val enabled: Boolean = false,
    /** Seconds of ringing before the app answers. The carrier may forward earlier. */
    val ringSeconds: Int = 20,
    /** Owner choice (2026-11-23): 30 s, then a beep and the app hangs up. */
    val maxMessageSeconds: Int = 30,
    /** When false, callers hear the closing greeting and the call ends (no recording). */
    val allowMessages: Boolean = true,
    val routing: RoutingMode = RoutingMode.EVERYONE,
    /** End the message after this many seconds of silence once the caller has spoken. 0 = off (default: record the full time). */
    val silenceStopSeconds: Int = 0,
    val greetingSource: GreetingSource = GreetingSource.TTS,
    val ownerName: String = "",
    val greetingText: String = DEFAULT_GREETING,
    val closingText: String = DEFAULT_CLOSING,
    val voiceName: String = DEFAULT_VOICE,
    val voicePitch: Float = 0.92f,
    val voiceRate: Float = 0.95f,
    val notifyNewVoicemail: Boolean = true,
    val onboardingDone: Boolean = false,
    /** Diagnostics: also keep what the caller heard (the call uplink: greeting and beeps). */
    val saveCallerSide: Boolean = false,
) {
    companion object {
        const val DEFAULT_GREETING =
            "Hello! You've reached {name}. I can't take your call right now. Please leave a message after the tone, and I'll get back to you."
        const val DEFAULT_CLOSING =
            "Hello! You've reached {name}. I can't take your call right now, and messages aren't being accepted at the moment. Please try again later. Goodbye."

        /** Owner choice (2026-11-22): a male, confident voice. Deepest installed en-IN male voice on the G60. */
        const val DEFAULT_VOICE = "en-in-x-end-local"
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val ring = intPreferencesKey("ring_seconds")
        val maxMsg = intPreferencesKey("max_message_seconds")
        val allow = booleanPreferencesKey("allow_messages")
        val routing = stringPreferencesKey("routing")
        val silence = intPreferencesKey("silence_stop_seconds")
        val source = stringPreferencesKey("greeting_source")
        val owner = stringPreferencesKey("owner_name")
        val greeting = stringPreferencesKey("greeting_text")
        val closing = stringPreferencesKey("closing_text")
        val voice = stringPreferencesKey("voice")
        val pitch = floatPreferencesKey("pitch")
        val rate = floatPreferencesKey("rate")
        val notify = booleanPreferencesKey("notify")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val callerSide = booleanPreferencesKey("save_caller_side")
        val version = intPreferencesKey("defaults_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.enabled] = s.enabled
            prefs[K.ring] = s.ringSeconds.coerceIn(5, 60)
            prefs[K.maxMsg] = s.maxMessageSeconds.coerceIn(10, 600)
            prefs[K.allow] = s.allowMessages
            prefs[K.routing] = s.routing.name
            prefs[K.silence] = s.silenceStopSeconds.coerceIn(0, 60)
            prefs[K.source] = s.greetingSource.name
            prefs[K.owner] = s.ownerName
            prefs[K.greeting] = s.greetingText
            prefs[K.closing] = s.closingText
            prefs[K.voice] = s.voiceName
            prefs[K.pitch] = s.voicePitch
            prefs[K.rate] = s.voiceRate
            prefs[K.notify] = s.notifyNewVoicemail
            prefs[K.onboarding] = s.onboardingDone
            prefs[K.callerSide] = s.saveCallerSide
        }
    }

    /**
     * Applies changed defaults to settings saved by an older version. v2 (2026-11-23): messages
     * are capped at 30 s and silence no longer ends them early (owner request).
     */
    suspend fun migrate() {
        context.dataStore.edit { prefs ->
            if ((prefs[K.version] ?: 1) < 2) {
                if (prefs.contains(K.maxMsg)) prefs[K.maxMsg] = 30
                if (prefs.contains(K.silence)) prefs[K.silence] = 0
                prefs[K.version] = 2
            }
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            enabled = this[K.enabled] ?: d.enabled,
            ringSeconds = this[K.ring] ?: d.ringSeconds,
            maxMessageSeconds = this[K.maxMsg] ?: d.maxMessageSeconds,
            allowMessages = this[K.allow] ?: d.allowMessages,
            routing = this[K.routing]?.let { runCatching { RoutingMode.valueOf(it) }.getOrNull() } ?: d.routing,
            silenceStopSeconds = this[K.silence] ?: d.silenceStopSeconds,
            greetingSource = this[K.source]?.let { runCatching { GreetingSource.valueOf(it) }.getOrNull() } ?: d.greetingSource,
            ownerName = this[K.owner] ?: d.ownerName,
            greetingText = this[K.greeting] ?: d.greetingText,
            closingText = this[K.closing] ?: d.closingText,
            voiceName = this[K.voice] ?: d.voiceName,
            voicePitch = this[K.pitch] ?: d.voicePitch,
            voiceRate = this[K.rate] ?: d.voiceRate,
            notifyNewVoicemail = this[K.notify] ?: d.notifyNewVoicemail,
            onboardingDone = this[K.onboarding] ?: d.onboardingDone,
            saveCallerSide = this[K.callerSide] ?: d.saveCallerSide,
        )
    }
}
