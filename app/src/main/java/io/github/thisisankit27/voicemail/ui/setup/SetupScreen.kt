package io.github.thisisankit27.voicemail.ui.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.thisisankit27.voicemail.VoicemailApp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.helper.HelperStatus
import io.github.thisisankit27.voicemail.ui.SetupSnapshot
import io.github.thisisankit27.voicemail.ui.rememberSetupSnapshot
import kotlinx.coroutines.launch

/**
 * Setup checklist with live status. It is the first-run onboarding and stays reachable from the
 * status card, so a broken step (helper down after a reboot without Wi-Fi) is always one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: (() -> Unit)?, onOpenGreeting: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val s = rememberSetupSnapshot()
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val helperBusy = s.helper is HelperStatus.Starting

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Set up voicemail") },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Your phone answers the calls you miss, plays your greeting into the call and keeps the caller's message. Nothing leaves this phone.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Step(1, "Make this your phone app", s.isDialer,
                "Calls ring here instead of Google Phone. Its spam filter and call recorder stop working while this app is the default. " +
                    "To switch back: Settings → Apps → Default apps → Phone app.") {
                Button(onClick = {
                    roleLauncher.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER))
                }) { Text("Make default phone app") }
            }

            Step(2, "Allow access", s.permissionsOk,
                "Contacts show caller names. Phone and call log power the dialer and recents. Notifications tell you about new messages.") {
                Button(onClick = {
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
                            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT,
                        ),
                    )
                }) { Text("Allow") }
            }

            Step(3, "Let it run in the background", s.batteryUnrestricted,
                "Set battery use to Unrestricted. Without it, Android stops the app from reaching the audio helper while the app isn't open, so calls after a reboot would ring out. Starting the helper also sets this for you.") {
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Allow") }
            }

            Step(4, "Authorise the audio helper (once)", s.keyAuthorized,
                "The audio helper is what plays your greeting into the call and records the caller. The app starts it by itself over Wireless debugging. " +
                    "It needs a one-time OK from you, given from a computer over USB:") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Code("adb tcpip 5555")
                    Text("Then tap Authorise. When the phone asks \"Allow USB debugging?\", tick Always allow and tap Allow. Afterwards run:", style = MaterialTheme.typography.bodyMedium)
                    Code("adb usb")
                    Button(enabled = !helperBusy, onClick = { scope.launch { app.onHelperStatus(app.helper.ensureRunning("setup")) } }) { Text("Authorise") }
                }
            }

            HelperStep(s, helperBusy) { scope.launch { app.onHelperStatus(app.helper.ensureRunning("setup")) } }

            Step(6, "Choose your greeting", s.greetingReady,
                "Callers hear it before the tone. Pick a voice and add your name. You can also record it in your own voice.") {
                OutlinedButton(onClick = onOpenGreeting) { Text("Set up greeting") }
            }

            val enabled = s.settings?.enabled == true
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Answer calls I miss", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (s.isDialer) "After ${s.settings?.ringSeconds ?: 20} s of ringing" else "Make this your phone app first",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = enabled, enabled = s.isDialer, onCheckedChange = { on ->
                        scope.launch {
                            app.settings.update { it.copy(enabled = on, onboardingDone = true) }
                            if (on) {
                                app.greetings.renderAll(app.settings.current())
                                app.onHelperStatus(app.helper.ensureRunning("enabled"))
                            }
                        }
                    })
                }
            }

            Text("Good to know", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            Text(
                "• Your carrier may forward unanswered calls to its own voicemail before this app answers. If that happens, turn off \"forward when unanswered\" with your carrier, or shorten the ring time.\n" +
                    "• After a reboot, unlock the phone once and connect to Wi-Fi. The helper then starts by itself.\n" +
                    "• If you ever reset battery usage to Optimised, step 3 turns red again.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open app battery settings") }
        }
    }
}

@Composable
private fun HelperStep(s: SetupSnapshot, busy: Boolean, onStart: () -> Unit) {
    val running = s.helper is HelperStatus.Running
    val body = when (val h = s.helper) {
        is HelperStatus.Running -> "Running. It restarts by itself after a reboot, as soon as the phone is on Wi-Fi."
        is HelperStatus.Starting -> h.step + "…"
        is HelperStatus.Stopped -> VoicemailApp.describe(h)
        HelperStatus.Unknown -> "Checking…"
    }
    Step(5, "Audio helper", running, body) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !busy, onClick = onStart) { Text(if (s.helper is HelperStatus.Stopped) "Start helper" else "Check again") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, body: String, action: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                else Text("$number", style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!done) action()
            }
        }
    }
}

@Composable
private fun Code(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp)) }
    }
}
