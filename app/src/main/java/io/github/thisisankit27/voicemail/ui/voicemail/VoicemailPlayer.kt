package io.github.thisisankit27.voicemail.ui.voicemail

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val itemId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val speaker: Boolean = true,
)

/**
 * One shared player for voicemails and greeting previews. Speaker plays as media; "earpiece"
 * plays as voice communication routed to the earpiece, for listening privately with the phone at the ear.
 */
class VoicemailPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var ticker: Job? = null
    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
                if (isPlaying) startTicker() else tick()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) tick()
                if (playbackState == Player.STATE_ENDED) {
                    p.pause()
                    p.seekTo(0)
                    _state.value = _state.value.copy(playing = false, positionMs = 0)
                    releaseEarpiece()
                }
            }
        })
        player = p
        applyRoute(p, _state.value.speaker)
    }

    /** Plays [file] as [id]; tapping the same item again toggles pause. */
    fun toggle(id: String, file: File) {
        val p = ensurePlayer()
        if (_state.value.itemId == id) {
            if (p.isPlaying) p.pause() else { applyRoute(p, _state.value.speaker); p.play() }
            return
        }
        _state.value = PlayerState(itemId = id, speed = _state.value.speed, speaker = _state.value.speaker)
        p.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        p.setPlaybackParameters(PlaybackParameters(_state.value.speed))
        p.prepare()
        applyRoute(p, _state.value.speaker)
        p.play()
    }

    fun seekTo(ms: Long) { player?.seekTo(ms); tick() }
    fun skip(deltaMs: Long) = player?.let { seekTo((it.currentPosition + deltaMs).coerceIn(0, it.duration.coerceAtLeast(0))) }

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(_state.value.speed) + 1).mod(SPEEDS.size)]
        player?.setPlaybackParameters(PlaybackParameters(next))
        _state.value = _state.value.copy(speed = next)
    }

    fun setSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speaker = on)
        player?.let { applyRoute(it, on) }
    }

    fun stop() {
        player?.stop()
        releaseEarpiece()
        _state.value = PlayerState(speed = _state.value.speed, speaker = _state.value.speaker)
    }

    fun release() {
        ticker?.cancel()
        player?.release()
        player = null
        releaseEarpiece()
    }

    private fun applyRoute(p: ExoPlayer, speaker: Boolean) {
        val usage = if (speaker) C.USAGE_MEDIA else C.USAGE_VOICE_COMMUNICATION
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), speaker)
        if (speaker) releaseEarpiece() else {
            audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?.let { audioManager.setCommunicationDevice(it) }
        }
    }

    private fun releaseEarpiece() = runCatching { audioManager.clearCommunicationDevice() }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch { while (isActive) { tick(); delay(100) } }
    }

    private fun tick() {
        val p = player ?: return
        _state.value = _state.value.copy(positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.takeIf { it > 0 } ?: _state.value.durationMs)
    }

    companion object {
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    }
}
