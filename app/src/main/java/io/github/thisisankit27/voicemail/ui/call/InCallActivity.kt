package io.github.thisisankit27.voicemail.ui.call

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.telephony.AudioRoute
import io.github.thisisankit27.voicemail.telephony.AudioUi
import io.github.thisisankit27.voicemail.telephony.CallManager
import io.github.thisisankit27.voicemail.telephony.CallPhase
import io.github.thisisankit27.voicemail.telephony.CallUi
import io.github.thisisankit27.voicemail.telephony.VoicemailPhase
import io.github.thisisankit27.voicemail.ui.components.Avatar
import io.github.thisisankit27.voicemail.ui.components.Formats
import io.github.thisisankit27.voicemail.ui.dialer.DialActivity
import io.github.thisisankit27.voicemail.ui.theme.CallColors
import io.github.thisisankit27.voicemail.ui.theme.VoicemailTheme
import kotlinx.coroutines.delay

/**
 * Incoming, outgoing and ongoing call screen, shown over the lock screen.
 *
 * - Incoming: answer / decline, reply with a message, silence the ringer, and (when the app's
 *   voicemail will answer) a live countdown with "Send to voicemail now".
 * - Ongoing: mute, keypad (DTMF), speaker / Bluetooth / wired route, add call, hold, merge, swap,
 *   conference management, and the held call shown as a banner.
 * - Call waiting: hold & answer, end & answer, or decline.
 * - Voicemail in progress: what the app is doing (greeting / recording with a live level), with
 *   "Take over" to pick the call up yourself and "Listen on speaker" to screen the caller.
 */
class InCallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        proximity = getSystemService(PowerManager::class.java)
            .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "voicemail:proximity")
        setContent { VoicemailTheme(dark = true) { CallScreen() } }
    }

    override fun onDestroy() {
        setProximity(false)
        super.onDestroy()
    }

    /** Screen off at the ear: only during a normal call on the earpiece. */
    private fun setProximity(on: Boolean) {
        val lock = proximity ?: return
        if (on && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
        if (!on && lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    @Composable
    private fun CallScreen() {
        val calls by CallManager.calls.collectAsState()
        val audio by CallManager.audio.collectAsState()
        val ended by CallManager.ended.collectAsState()

        // Keep "Call ended" on screen briefly, then close.
        LaunchedEffect(calls.isEmpty()) {
            if (calls.isEmpty()) { delay(if (ended != null) 1_200 else 0); finish() }
        }

        val ringing = calls.firstOrNull { it.phase == CallPhase.RINGING && it.incoming }
        val topLevel = calls.filter { it.parentId == null }
        val foreground = topLevel.firstOrNull { it.phase == CallPhase.ACTIVE || it.phase == CallPhase.DIALING || it.phase == CallPhase.CONNECTING }
            ?: topLevel.firstOrNull { it.phase != CallPhase.RINGING } ?: ringing
        val held = topLevel.firstOrNull { it.phase == CallPhase.HOLDING && it.id != foreground?.id }
        val waiting = ringing?.takeIf { foreground != null && foreground.id != it.id }
        val main = if (waiting != null) foreground else (ringing ?: foreground)

        LaunchedEffect(main?.phase, main?.voicemail, audio.route) {
            setProximity(main?.phase == CallPhase.ACTIVE && main.voicemail == null && audio.route == AudioRoute.EARPIECE)
        }

        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CallColors.callBackground, CallColors.callBackgroundEnd))),
        ) {
            val shown = main ?: ended
            if (shown == null) return@Box
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                held?.let { HeldBanner(it) }
                Spacer(Modifier.height(if (held != null) 16.dp else 48.dp))
                Header(shown, ended = main == null)
                Spacer(Modifier.weight(1f))
                when {
                    main == null -> Unit
                    waiting != null -> CallWaitingPanel(waiting, main)
                    main.phase == CallPhase.RINGING -> IncomingPanel(main)
                    main.voicemail != null && main.phase == CallPhase.ACTIVE -> VoicemailPanel(main, audio)
                    else -> OngoingPanel(main, held, audio)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun Header(call: CallUi, ended: Boolean) {
        val now by ticker()
        val status = when {
            ended -> listOfNotNull("Call ended", call.disconnectLabel?.takeIf { it != "Call ended" }).joinToString(" · ")
            call.phase == CallPhase.RINGING -> listOfNotNull("Incoming call", call.simLabel).joinToString(" · ")
            call.phase == CallPhase.DIALING || call.phase == CallPhase.CONNECTING -> "Calling…"
            call.phase == CallPhase.HOLDING -> "On hold"
            call.phase == CallPhase.DISCONNECTING -> "Hanging up…"
            call.phase == CallPhase.ACTIVE && call.connectTimeMillis > 0 -> Formats.duration(now - call.connectTimeMillis)
            else -> ""
        }
        Avatar(call.displayName, call.contact?.photoUri, call.number ?: call.displayName, size = 112.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            call.displayName, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (call.displayName != call.formattedNumber && !call.isConference) {
            Text(call.formattedNumber, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(status, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun HeldBanner(held: CallUi) {
        Surface(
            onClick = { CallManager.unhold(held.id) },
            color = Color.White.copy(alpha = 0.12f), contentColor = Color.White, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Pause, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(held.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("On hold · tap to switch", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }

    // ---- Incoming ---------------------------------------------------------------------------

    @Composable
    private fun IncomingPanel(call: CallUi) {
        var replies by remember { mutableStateOf(false) }
        val countdown = call.voicemail as? VoicemailPhase.Countdown
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (countdown != null) CountdownChip(countdown)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallAction(Icons.AutoMirrored.Filled.Message, "Message") { replies = true }
                SmallAction(Icons.Filled.NotificationsOff, "Silence") {
                    runCatching { getSystemService(TelecomManager::class.java).silenceRinger() }
                }
                if (countdown != null) SmallAction(Icons.Filled.Voicemail, "Voicemail now") { app.engine.session(call.id)?.sendToVoicemailNow() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigButton(Icons.Filled.CallEnd, "Decline", CallColors.decline) { CallManager.reject(call.id) }
                PulsingAnswer { CallManager.answer(call.id) }
            }
        }
        if (replies) QuickReplies(onDismiss = { replies = false }) { text ->
            replies = false
            CallManager.rejectWithMessage(call.id, text)
        }
    }

    @Composable
    private fun CountdownChip(c: VoicemailPhase.Countdown) {
        val now by ticker(250)
        val left = ((c.answerAtElapsed - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
        @Suppress("UNUSED_EXPRESSION") now
        Surface(color = Color.White.copy(alpha = 0.12f), contentColor = Color.White, shape = RoundedCornerShape(50)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Voicemail, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (left > 0) "Voicemail answers in $left s" else "Voicemail is answering…", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    @Composable
    private fun PulsingAnswer(onClick: () -> Unit) {
        val t = rememberInfiniteTransition(label = "answer")
        val lift by t.animateFloat(0f, -10f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "lift")
        Box(Modifier.padding(top = (10 + lift).coerceAtLeast(0f).dp)) {
            BigButton(Icons.Filled.Call, "Answer", CallColors.accept, onClick = onClick)
        }
    }

    @Composable
    private fun CallWaitingPanel(waiting: CallUi, current: CallUi) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(color = Color.White.copy(alpha = 0.12f), contentColor = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(waiting.displayName, waiting.contact?.photoUri, waiting.number, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Incoming call", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                        Text(waiting.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigButton(Icons.Filled.CallEnd, "Decline", CallColors.decline) { CallManager.reject(waiting.id) }
                if (current.canHold) BigButton(Icons.Filled.Pause, "Hold & answer", CallColors.accept) { CallManager.answer(waiting.id) }
                BigButton(Icons.Filled.SwapCalls, "End & answer", CallColors.accept) {
                    CallManager.hangup(current.id)
                    CallManager.answer(waiting.id)
                }
            }
        }
    }

    // ---- Ongoing ----------------------------------------------------------------------------

    @Composable
    private fun OngoingPanel(call: CallUi, held: CallUi?, audio: AudioUi) {
        var keypad by remember { mutableStateOf(false) }
        var routes by remember { mutableStateOf(false) }
        var conference by remember { mutableStateOf(false) }
        val active = call.phase == CallPhase.ACTIVE || call.phase == CallPhase.HOLDING
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            AnimatedVisibility(keypad, enter = fadeIn(), exit = fadeOut()) { InCallKeypad(call.id) }
            if (!keypad) {
                val btAvailable = AudioRoute.BLUETOOTH in audio.supported
                val routeIcon = when (audio.route) {
                    AudioRoute.BLUETOOTH -> Icons.Filled.Bluetooth
                    AudioRoute.WIRED_HEADSET -> Icons.Filled.Headset
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                }
                val controls = buildList {
                    add(Control(if (audio.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (audio.muted) "Unmute" else "Mute", audio.muted, active) { CallManager.setMuted(!audio.muted) })
                    add(Control(Icons.Filled.Dialpad, "Keypad", false, active) { keypad = true })
                    add(
                        Control(routeIcon, if (btAvailable) "Audio" else "Speaker", audio.route == AudioRoute.SPEAKER || audio.route == AudioRoute.BLUETOOTH, true) {
                            if (btAvailable) routes = true
                            else CallManager.setRoute(if (audio.route == AudioRoute.SPEAKER) AudioRoute.EARPIECE else AudioRoute.SPEAKER)
                        },
                    )
                    add(Control(Icons.Filled.AddIcCall, "Add call", false, call.phase == CallPhase.ACTIVE) {
                        startActivity(Intent(this@InCallActivity, DialActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                    if (held != null) add(Control(Icons.Filled.SwapCalls, "Swap", false, true) { CallManager.unhold(held.id) })
                    else add(Control(Icons.Filled.Pause, if (call.phase == CallPhase.HOLDING) "Resume" else "Hold", call.phase == CallPhase.HOLDING, call.canHold && active) {
                        if (call.phase == CallPhase.HOLDING) CallManager.unhold(call.id) else CallManager.hold(call.id)
                    })
                    when {
                        call.isConference -> add(Control(Icons.Filled.Groups, "Manage", false, call.childIds.isNotEmpty()) { conference = true })
                        call.canSwap -> add(Control(Icons.Filled.SwapCalls, "Swap", false, true) { CallManager.swap(call.id) })
                        else -> add(Control(Icons.Filled.CallMerge, "Merge", false, call.canMerge || held != null) {
                            CallManager.merge(call.id)
                        })
                    }
                }
                ControlGrid(controls)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (keypad) TextButton(onClick = { keypad = false }) { Text("Hide keypad", color = Color.White) }
                Spacer(Modifier.width(if (keypad) 24.dp else 0.dp))
                BigButton(Icons.Filled.CallEnd, "End call", CallColors.decline, showLabel = false) { CallManager.hangup(call.id) }
            }
        }
        if (routes) RoutePicker(audio) { routes = false }
        if (conference) ConferenceDialog(call) { conference = false }
    }

    private data class Control(val icon: ImageVector, val label: String, val on: Boolean, val enabled: Boolean, val onClick: () -> Unit)

    @Composable
    private fun ControlGrid(controls: List<Control>) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            controls.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { c -> ToggleButton(c) }
                    repeat(3 - row.size) { Spacer(Modifier.width(76.dp)) }
                }
            }
        }
    }

    @Composable
    private fun ToggleButton(c: Control) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(88.dp).alpha(if (c.enabled) 1f else 0.38f).semantics {
                role = Role.Button
                stateDescription = if (c.on) "On" else "Off"
            },
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(if (c.on) Color.White else Color.White.copy(alpha = 0.12f))
                    .clickable(enabled = c.enabled, onClick = c.onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(c.icon, c.label, tint = if (c.on) CallColors.callBackground else Color.White) }
            Spacer(Modifier.height(6.dp))
            Text(c.label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }

    @Composable
    private fun InCallKeypad(callId: String) {
        var typed by remember { mutableStateOf("") }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(typed.ifEmpty { " " }, color = Color.White, fontSize = 28.sp, maxLines = 1)
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { d ->
                        Box(
                            Modifier.size(width = 84.dp, height = 60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                                .semantics { contentDescription = "Tone $d"; role = Role.Button }
                                .pointerInput(d) {
                                    detectTapGestures(onPress = {
                                        typed += d
                                        CallManager.playDtmf(callId, d)
                                        tryAwaitRelease()
                                        CallManager.stopDtmf(callId)
                                    })
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(d.toString(), color = Color.White, fontSize = 26.sp) }
                    }
                }
            }
        }
    }

    @Composable
    private fun RoutePicker(audio: AudioUi, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Audio output") },
            text = {
                Column {
                    @Composable
                    fun Option(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick(); onDismiss() }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(16.dp))
                            Text(label, Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    if (AudioRoute.WIRED_HEADSET in audio.supported) Option(Icons.Filled.Headset, "Wired headset", audio.route == AudioRoute.WIRED_HEADSET) { CallManager.setRoute(AudioRoute.WIRED_HEADSET) }
                    else Option(Icons.Filled.PhoneInTalk, "Phone", audio.route == AudioRoute.EARPIECE) { CallManager.setRoute(AudioRoute.EARPIECE) }
                    Option(Icons.AutoMirrored.Filled.VolumeUp, "Speaker", audio.route == AudioRoute.SPEAKER) { CallManager.setRoute(AudioRoute.SPEAKER) }
                    if (audio.bluetoothDevices.isEmpty() && AudioRoute.BLUETOOTH in audio.supported) {
                        Option(Icons.Filled.Bluetooth, "Bluetooth", audio.route == AudioRoute.BLUETOOTH) { CallManager.setRoute(AudioRoute.BLUETOOTH) }
                    }
                    audio.bluetoothDevices.forEach { d ->
                        val name = runCatching { d.name }.getOrNull() ?: "Bluetooth device"
                        Option(Icons.Filled.Bluetooth, name, audio.route == AudioRoute.BLUETOOTH && audio.activeBluetooth?.address == d.address) { CallManager.useBluetooth(d) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }

    @Composable
    private fun ConferenceDialog(conference: CallUi, onDismiss: () -> Unit) {
        val calls by CallManager.calls.collectAsState()
        val children = calls.filter { it.id in conference.childIds }
        LaunchedEffect(children.isEmpty()) { if (children.isEmpty()) onDismiss() }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Conference call") },
            text = {
                Column {
                    children.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.displayName, c.contact?.photoUri, c.number, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(c.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { CallManager.splitFromConference(c.id) }) { Text("Private") }
                            TextButton(onClick = { CallManager.hangup(c.id) }) { Text("End", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
    }

    // ---- Voicemail in progress --------------------------------------------------------------

    @Composable
    private fun VoicemailPanel(call: CallUi, audio: AudioUi) {
        val now by ticker(200)
        val vm = call.voicemail
        val (title, detail) = when (vm) {
            is VoicemailPhase.Answering, is VoicemailPhase.Countdown -> "Voicemail is answering" to "Connecting…"
            is VoicemailPhase.Greeting -> "Playing your greeting" to "The caller hears your greeting. Your microphone is off."
            is VoicemailPhase.Recording -> "Recording a message" to Formats.duration(SystemClock.elapsedRealtime() - vm.startedAtElapsed) + " · the caller can't hear you"
            is VoicemailPhase.Closing -> "Playing your closing message" to "The call ends after it."
            null -> "" to ""
        }
        @Suppress("UNUSED_EXPRESSION") now
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(color = Color.White.copy(alpha = 0.10f), contentColor = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Voicemail, null)
                        Spacer(Modifier.width(10.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
                    if (vm is VoicemailPhase.Recording) LevelBars(vm.levelDb)
                    if (vm is VoicemailPhase.Greeting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = 0.2f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToggleButton(
                    Control(Icons.Filled.Hearing, if (audio.route == AudioRoute.SPEAKER) "Listening" else "Listen in", audio.route == AudioRoute.SPEAKER, true) {
                        CallManager.setRoute(if (audio.route == AudioRoute.SPEAKER) AudioRoute.EARPIECE else AudioRoute.SPEAKER)
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigButton(Icons.Filled.CallEnd, "End", CallColors.decline) {
                    app.engine.session(call.id)?.cancel("ended from call screen")
                    CallManager.hangup(call.id)
                }
                BigButton(Icons.Filled.Call, "Take over", CallColors.accept) {
                    app.engine.session(call.id)?.takeOver()
                    CallManager.setRoute(AudioRoute.EARPIECE)
                }
            }
        }
    }

    @Composable
    private fun LevelBars(db: Double) {
        val level = ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(24) { i ->
                val shape = 1f - kotlin.math.abs(i - 11.5f) / 12f
                val h = (6 + 30 * level * shape).dp
                Box(Modifier.weight(1f).height(h).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.4f + 0.6f * level)))
            }
        }
    }

    // ---- Shared bits ------------------------------------------------------------------------

    @Composable
    private fun BigButton(icon: ImageVector, label: String, color: Color, showLabel: Boolean = true, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(color).clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            if (showLabel) {
                Spacer(Modifier.height(8.dp))
                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    @Composable
    private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp).semantics(mergeDescendants = true) { role = Role.Button },
        ) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
    }

    @Composable
    private fun QuickReplies(onDismiss: () -> Unit, onSend: (String) -> Unit) {
        val replies = listOf(
            "Can't talk now. I'll call you back.",
            "I'll call you right back.",
            "I'm in a meeting. Can you text me?",
            "On my way.",
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Decline with a message") },
            text = {
                Column {
                    replies.forEach { r ->
                        Text(r, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSend(r) }.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    /** Current wall-clock time, ticking every [periodMs]. */
    @Composable
    private fun ticker(periodMs: Long = 1_000) = remember { mutableLongStateOf(System.currentTimeMillis()) }.also { state ->
        LaunchedEffect(periodMs) { while (true) { state.longValue = System.currentTimeMillis(); delay(periodMs) } }
    }
}
