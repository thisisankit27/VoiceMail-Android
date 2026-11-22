package io.github.thisisankit27.voicemail.ui

import android.Manifest
import android.app.role.RoleManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.helper.HelperStatus
import io.github.thisisankit27.voicemail.ui.theme.VoicemailTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * PR-1 control screen: setup status, the audio helper, the master switch, the greeting and the
 * message list. Replaced by the full app navigation in the UI pass.
 */
class MainActivity : ComponentActivity() {
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoicemailTheme { Screen() } }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val app = app
        val scope = rememberCoroutineScope()
        val settings by app.settings.settings.collectAsState(initial = null)
        val helper by app.helper.status.collectAsState()
        val voicemails by app.database.voicemails().observeAll().collectAsState(initial = emptyList())
        val log by AppLog.lines.collectAsState()
        val roleManager = getSystemService(RoleManager::class.java)
        var isDialer by remember { mutableStateOf(roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) }
        val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            isDialer = roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        }
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        LaunchedEffect(Unit) { app.helper.check() }

        Scaffold { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Voicemail", style = MaterialTheme.typography.headlineMedium) }
                item {
                    Card {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Setup", style = MaterialTheme.typography.titleMedium)
                            Text(if (isDialer) "✓ Default phone app" else "✗ Not the default phone app")
                            if (!isDialer) Button(onClick = { roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }) { Text("Make default phone app") }
                            OutlinedButton(onClick = {
                                permLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
                                        Manifest.permission.READ_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO,
                                    ),
                                )
                            }) { Text("Grant permissions") }
                            Text("Audio helper: ${describe(helper)}")
                            Text("Debugging key authorised: ${app.helper.keyAuthorized} · Secure settings: ${app.helper.hasSecureSettings()}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { scope.launch { app.onHelperStatus(app.helper.ensureRunning("user")) } }) { Text("Start helper") }
                                OutlinedButton(onClick = { scope.launch { app.helper.stop() } }) { Text("Stop") }
                            }
                        }
                    }
                }
                settings?.let { s ->
                    item {
                        Card {
                            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Answer unanswered calls", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Switch(checked = s.enabled, onCheckedChange = { on -> scope.launch { app.settings.update { it.copy(enabled = on) } } })
                                }
                                Text("Answers after ${s.ringSeconds} s of ringing")
                                var name by remember(s.ownerName) { mutableStateOf(s.ownerName) }
                                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Your name in the greeting") }, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        scope.launch {
                                            app.settings.update { it.copy(ownerName = name.trim()) }
                                            app.greetings.renderAll(app.settings.current())
                                        }
                                    }) { Text("Save & render") }
                                    OutlinedButton(onClick = { play(app.greetings.file(io.github.thisisankit27.voicemail.greeting.GreetingKind.MESSAGE)) }) { Text("Play greeting") }
                                }
                            }
                        }
                    }
                }
                item { Text("Messages (${voicemails.size})", style = MaterialTheme.typography.titleMedium) }
                items(voicemails, key = { it.id }) { v ->
                    Card(onClick = { v.audioFile?.let { play(File(File(filesDir, "voicemails"), it)) } }) {
                        Column(Modifier.padding(12.dp).fillMaxWidth()) {
                            Text(v.callerName ?: app.contacts.format(v.callerNumber), style = MaterialTheme.typography.titleSmall)
                            Text("${java.text.DateFormat.getDateTimeInstance().format(v.createdAt)} · ${v.durationMs / 1000}s · ${v.status}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("Log", style = MaterialTheme.typography.titleMedium) }
                items(log.take(60)) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            }
        }
    }

    private fun describe(s: HelperStatus) = when (s) {
        HelperStatus.Unknown -> "checking…"
        is HelperStatus.Starting -> s.step + "…"
        is HelperStatus.Running -> "running (uid ${s.info.uid})"
        is HelperStatus.Stopped -> "stopped: " + io.github.thisisankit27.voicemail.VoicemailApp.describe(s)
    }

    private fun play(file: File) {
        if (!file.exists()) return
        player?.release()
        player = MediaPlayer().apply { setDataSource(file.path); prepare(); start() }
    }

    companion object {
        const val EXTRA_VOICEMAIL_ID = "voicemail_id"
        const val EXTRA_OPEN_SETUP = "open_setup"
    }
}
