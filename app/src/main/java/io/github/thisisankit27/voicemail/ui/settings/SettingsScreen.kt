package io.github.thisisankit27.voicemail.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thisisankit27.voicemail.BuildConfig
import io.github.thisisankit27.voicemail.VoicemailApp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.data.AppSettings
import io.github.thisisankit27.voicemail.data.RoutingMode
import io.github.thisisankit27.voicemail.helper.HelperStatus
import io.github.thisisankit27.voicemail.ui.components.Formats
import io.github.thisisankit27.voicemail.ui.components.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSetup: () -> Unit, onOpenGreeting: () -> Unit, onOpenLog: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val s by app.settings.settings.collectAsState(initial = AppSettings())
    val helper by app.helper.status.collectAsState()
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(t) }
    var routingDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionHeader("Voicemail")
            SwitchRow("Answer calls I miss", "Callers hear your greeting and can leave a message", s.enabled) { on -> update { it.copy(enabled = on) } }
            SliderRow("Ring time before answering", "${s.ringSeconds} seconds. Your carrier may forward unanswered calls sooner (often about 20–30 s).", s.ringSeconds.toFloat(), 5f..45f, 7) {
                update { st -> st.copy(ringSeconds = it.toInt()) }
            }
            SliderRow("Longest message", "${s.maxMessageSeconds} s. Then the caller hears two beeps and the call ends.", s.maxMessageSeconds.toFloat(), 10f..120f, 21) {
                update { st -> st.copy(maxMessageSeconds = (Math.round(it / 5f) * 5)) }
            }
            SliderRow("End after silence", if (s.silenceStopSeconds == 0) "Off" else "${s.silenceStopSeconds} s of silence after the caller stops", s.silenceStopSeconds.toFloat(), 0f..20f, 9) {
                update { st -> st.copy(silenceStopSeconds = it.toInt()) }
            }
            NavRow("Who goes to voicemail", if (s.routing == RoutingMode.EVERYONE) "Everyone" else "Only numbers not in my contacts") { routingDialog = true }
            NavRow("Greeting", if (s.allowMessages) "Taking messages" else "Not taking messages", onOpenGreeting)
            SwitchRow("Notify me about new voicemail", null, s.notifyNewVoicemail) { on -> update { it.copy(notifyNewVoicemail = on) } }

            SectionHeader("Audio helper")
            val line = when (val h = helper) {
                is HelperStatus.Running -> "Running" + (app.helper.lastStartedAt.takeIf { it > 0 }?.let { " · started ${Formats.relative(it)}" } ?: "")
                is HelperStatus.Starting -> h.step + "…"
                is HelperStatus.Stopped -> VoicemailApp.describe(h)
                HelperStatus.Unknown -> "Checking…"
            }
            NavRow("Status", line, onOpenSetup)
            var turnOff by remember { mutableStateOf(app.helper.turnOffWirelessDebugging) }
            SwitchRow("Switch Wireless debugging off after starting", "Recommended. The helper keeps running without it.", turnOff) {
                app.helper.turnOffWirelessDebugging = it
                turnOff = it
            }
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { app.onHelperStatus(app.helper.ensureRunning("settings")) } }) { Text("Start / check") }
                TextButton(onClick = { scope.launch { app.helper.stop() } }) { Text("Stop helper") }
            }

            SectionHeader("Phone")
            NavRow("Default phone app", "Switch back to Google Phone here") {
                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            NavRow("Notification settings", "Sounds, lock screen") {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            SectionHeader("About")
            NavRow("Setup checklist", null, onOpenSetup)
            NavRow("Diagnostics log", "Recent events, phone numbers redacted", onOpenLog)
            SwitchRow(
                "Keep what callers hear",
                "Saves a copy of the call audio sent to the caller (your greeting and the beeps) with each voicemail, so you can check it. Your microphone is muted, so it never contains you.",
                s.saveCallerSide,
            ) { on -> update { it.copy(saveCallerSide = on) } }
            Text(
                "Voicemail ${BuildConfig.VERSION_NAME} · personal build. Messages and greetings are stored only on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (routingDialog) {
        AlertDialog(
            onDismissRequest = { routingDialog = false },
            title = { Text("Who goes to voicemail") },
            text = {
                Column {
                    listOf(RoutingMode.EVERYONE to "Everyone", RoutingMode.UNKNOWN_ONLY to "Only numbers not in my contacts").forEach { (mode, label) ->
                        Row(Modifier.fillMaxWidth().clickable { update { it.copy(routing = mode) }; routingDialog = false }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(s.routing == mode, { update { it.copy(routing = mode) }; routingDialog = false })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { routingDialog = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SliderRow(title: String, subtitle: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onDone(v) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val lines by AppLog.lines.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Diagnostics") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            if (lines.isEmpty()) item { Text("Nothing logged since the app started.", Modifier.padding(16.dp)) }
            items(lines) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 3.dp))
                HorizontalDivider()
            }
        }
    }
}
