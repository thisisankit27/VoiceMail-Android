package io.github.thisisankit27.voicemail.ui.greeting

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.data.AppSettings
import io.github.thisisankit27.voicemail.data.GreetingSource
import io.github.thisisankit27.voicemail.greeting.GreetingKind
import io.github.thisisankit27.voicemail.greeting.GreetingRecorder
import io.github.thisisankit27.voicemail.ui.components.Formats
import io.github.thisisankit27.voicemail.ui.granted
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreetingScreen(onBack: () -> Unit) {
    val app = LocalContext.current.app
    val saved by app.settings.settings.collectAsState(initial = null)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Greeting") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        val s = saved
        if (s == null) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else GreetingEditor(s, Modifier.padding(pad))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GreetingEditor(saved: AppSettings, modifier: Modifier) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val dirty = draft != saved
    val voices by produceState(emptyList<io.github.thisisankit27.voicemail.greeting.VoiceOption>()) { value = app.greetings.voices() }

    fun save(then: suspend () -> Unit = {}) = scope.launch {
        busy = true
        app.settings.update { draft }
        val ok = app.greetings.render(draft, GreetingKind.MESSAGE) and app.greetings.render(draft, GreetingKind.CLOSING)
        status = if (ok) "Saved. Callers will hear this greeting." else "Couldn't create the greeting audio. Pick an offline voice."
        busy = false
        then()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let callers leave a message", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (draft.allowMessages) "Greeting, then a tone, then the caller can speak" else "Callers hear the \"not taking messages\" greeting and the call ends",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(draft.allowMessages, onCheckedChange = { draft = draft.copy(allowMessages = it) })
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(draft.greetingSource == GreetingSource.TTS, { draft = draft.copy(greetingSource = GreetingSource.TTS) }, SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(18.dp)) }) { Text("Read aloud") }
            SegmentedButton(draft.greetingSource == GreetingSource.RECORDED, { draft = draft.copy(greetingSource = GreetingSource.RECORDED) }, SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)) }) { Text("My voice") }
        }

        if (draft.greetingSource == GreetingSource.RECORDED && draft.allowMessages) {
            RecordPanel(onUse = { scope.launch { app.settings.update { it.copy(greetingSource = GreetingSource.RECORDED) } } })
        } else {
            OutlinedTextField(draft.ownerName, { draft = draft.copy(ownerName = it) }, label = { Text("Your name") },
                supportingText = { Text("Replaces {name} in the greeting") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (draft.allowMessages) {
                OutlinedTextField(draft.greetingText, { draft = draft.copy(greetingText = it) }, label = { Text("Greeting") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { draft = draft.copy(greetingText = AppSettings.DEFAULT_GREETING) }) { Text("Restore default text") }
            } else {
                OutlinedTextField(draft.closingText, { draft = draft.copy(closingText = it) }, label = { Text("When not taking messages") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { draft = draft.copy(closingText = AppSettings.DEFAULT_CLOSING) }) { Text("Restore default text") }
            }

            Text("Voice", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (voices.isEmpty()) Text("Loading voices…", Modifier.padding(16.dp))
                    voices.filter { it.offline }.take(12).forEach { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { draft = draft.copy(voiceName = v.name) }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(draft.voiceName == v.name, { draft = draft.copy(voiceName = v.name) })
                            Column(Modifier.weight(1f)) {
                                Text(v.label, style = MaterialTheme.typography.bodyLarge)
                                val detail = listOfNotNull(v.character, if (v.name == AppSettings.DEFAULT_VOICE) "recommended" else null).joinToString(" · ")
                                if (detail.isNotEmpty()) Text(
                                    detail, style = MaterialTheme.typography.bodySmall,
                                    color = if (v.name == AppSettings.DEFAULT_VOICE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { scope.launch { app.greetings.preview("Hello, this is how I sound.", v.name, draft.voicePitch, draft.voiceRate) } }) {
                                Icon(Icons.Filled.PlayArrow, "Hear ${v.label}")
                            }
                        }
                    }
                    if (voices.any { !it.offline }) Text(
                        "Voices that need the internet are hidden: a greeting must work offline.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            LabeledSlider("Pitch", draft.voicePitch, 0.7f..1.3f) { draft = draft.copy(voicePitch = it) }
            LabeledSlider("Speed", draft.voiceRate, 0.7f..1.3f) { draft = draft.copy(voiceRate = it) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = {
                save {
                    val kind = if (draft.allowMessages) GreetingKind.MESSAGE else GreetingKind.CLOSING
                    val file = if (kind == GreetingKind.MESSAGE && draft.greetingSource == GreetingSource.RECORDED && app.greetings.recordedFile.exists()) app.greetings.recordedFile else app.greetings.file(kind)
                    app.player.toggle("greeting-preview-${System.nanoTime()}", file)
                }
            }) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Listen")
            }
            Button(enabled = !busy && dirty, onClick = { save() }) { Text("Save") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        }
        status?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text("%.2f×".format(value), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
private fun RecordPanel(onUse: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val recorder = remember { GreetingRecorder(app.greetings.recordedFile, File(context.cacheDir, "greeting-take.wav")) }
    val st by recorder.state.collectAsState()
    var used by remember { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) recorder.start() }
    DisposableEffect(Unit) { onDispose { recorder.stop() } }
    val level = ((st.levelDb + 60) / 60).coerceIn(0.0, 1.0).toFloat()
    val pulse by animateFloatAsState(if (st.recording) 1f + level * 0.25f else 1f, label = "pulse")

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                if (st.recording) "Recording… speak your greeting" else "Say something like: \"Hi, you've reached Sam. Leave me a message after the tone.\"",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.size(96.dp).scale(pulse).clip(CircleShape)
                    .background(if (st.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    .clickable {
                        if (st.recording) recorder.stop()
                        else if (context.granted(Manifest.permission.RECORD_AUDIO)) recorder.start() else mic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (st.recording) Icons.Filled.Stop else Icons.Filled.Mic, if (st.recording) "Stop recording" else "Start recording",
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp))
            }
            Text(Formats.duration(st.elapsedMs) + " / " + Formats.duration(GreetingRecorder.MAX_MS), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            AnimatedVisibility(st.recording) { LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth()) }
            if (!st.recording && st.hasTake) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { app.player.toggle("take-${st.elapsedMs}", File(context.cacheDir, "greeting-take.wav")) }) { Text("Play take") }
                    Button(onClick = { if (recorder.useTake()) { used = true; onUse() } }) { Text("Use this recording") }
                }
            }
            if (used || app.greetings.recordedFile.exists()) Text(
                if (used) "Saved. Callers will hear your recording." else "You have a recorded greeting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
