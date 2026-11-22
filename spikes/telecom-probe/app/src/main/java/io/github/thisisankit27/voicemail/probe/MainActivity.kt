package io.github.thisisankit27.voicemail.probe

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Control panel for the Phase 0 experiments. Every action can also be driven over ADB so runs are
 * reproducible:  adb shell am start -n io.github.thisisankit27.voicemail.probe/.MainActivity --es probe_action <action>
 * The action list lives in [ProbeActions]; spikes/telecom-probe/README.md documents it.
 */
class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ProbeLog.i("E1 role request result=${result.resultCode} (RESULT_OK=-1)")
        logStatus()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        ProbeLog.i("permissions: " + grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        refresh.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { ControlPanel() } } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    private fun handle(intent: Intent?) {
        if (intent != null && ProbeActions.run(this, intent)) refresh.intValue++
    }

    private fun roleManager() = getSystemService(RoleManager::class.java)

    private fun logStatus() {
        val telecom = getSystemService(TelecomManager::class.java)
        val roles = roleManager()
        ProbeLog.i(
            "E1 status: defaultDialer=${telecom.defaultDialerPackage} systemDialer=${telecom.systemDialerPackage} " +
                "holdsDialer=${roles.isRoleHeld(RoleManager.ROLE_DIALER)} holdsScreening=${roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)} " +
                "inCallServiceBound=${ProbeInCallService.instance != null}",
        )
        ProbeLog.i("config: ${ProbeConfig(this).summary()}")
        refresh.intValue++
    }

    private fun requestRole(role: String) {
        val roles = roleManager()
        if (!roles.isRoleAvailable(role)) {
            ProbeLog.i("E1 role $role not available on this device")
            return
        }
        ProbeLog.i("E1 requesting $role (user must confirm the system dialog)")
        roleRequest.launch(roles.createRequestRoleIntent(role))
    }

    @Composable
    private fun ControlPanel() {
        val tick by refresh
        val lines by ProbeLog.lines.collectAsState()
        val config = remember(tick) { ProbeConfig(this) }
        val roles = roleManager()
        val holdsDialer = remember(tick) { roles.isRoleHeld(RoleManager.ROLE_DIALER) }
        val holdsScreening = remember(tick) { roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }

        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Telecom Probe — Phase 0", style = MaterialTheme.typography.headlineSmall)
                Text("Throwaway experiment app. Not the product.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("Roles: dialer=$holdsDialer · screening=$holdsScreening", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requestRole(RoleManager.ROLE_DIALER) }) { Text("Become dialer (E1)") }
                    OutlinedButton(onClick = { requestRole(RoleManager.ROLE_CALL_SCREENING) }) { Text("Become screener (E10)") }
                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) {
                        Text("Revert: default apps")
                    }
                    OutlinedButton(onClick = {
                        permissionRequest.launch(
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE),
                        )
                    }) { Text("Grant permissions") }
                    OutlinedButton(onClick = { logStatus() }) { Text("Log status") }
                }
            }
            item { HorizontalDivider() }
            item {
                Text("Experiment settings", style = MaterialTheme.typography.titleSmall)
                Stepper("Auto-answer after (E2)", "${config.autoAnswerSeconds} s (0 = off)",
                    { config.autoAnswerSeconds -= 5; refresh.intValue++ }, { config.autoAnswerSeconds += 5; refresh.intValue++ })
                Stepper("Record after beep (E4)", "${config.captureSeconds} s",
                    { config.captureSeconds -= 5; refresh.intValue++ }, { config.captureSeconds += 5; refresh.intValue++ })
                Toggle("Speaker on auto-answer (E3)", config.speakerOnAnswer) { config.speakerOnAnswer = it; refresh.intValue++ }
                Toggle("Screening: reject all incoming (E10)", config.screeningReject) { config.screeningReject = it; refresh.intValue++ }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        config.greetingUsage = ProbeConfig.next(ProbeConfig.USAGES, config.greetingUsage); refresh.intValue++
                    }) { Text("Greeting usage: ${ProbeConfig.usageName(config.greetingUsage)}") }
                    OutlinedButton(onClick = {
                        config.captureSource = ProbeConfig.next(ProbeConfig.SOURCES, config.captureSource); refresh.intValue++
                    }) { Text("Capture source: ${ProbeConfig.sourceName(config.captureSource)}") }
                }
            }
            item { HorizontalDivider() }
            item {
                Text("No-SIM experiments", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act("tts_voices") }) { Text("List TTS voices (E7)") }
                    OutlinedButton(onClick = { act("render_greeting") }) { Text("Render greeting (E7)") }
                    OutlinedButton(onClick = { act("render_caller") }) { Text("Render caller clip") }
                    OutlinedButton(onClick = { act("play_greeting") }) { Text("Play greeting") }
                    OutlinedButton(onClick = { act("mic_baseline_all") }) { Text("Mic baseline, all sources") }
                }
            }
            item { HorizontalDivider() }
            item {
                Text("Loopback calls (no network; E3 not testable)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act("register_loopback") }) { Text("Register loopback account") }
                    Button(onClick = { act("fake_incoming") }) { Text("Fake call: leaves message") }
                    OutlinedButton(onClick = { act("fake_incoming", "hangup_ringing") }) { Text("Fake call: hangs up ringing") }
                    OutlinedButton(onClick = { act("fake_incoming", "silent") }) { Text("Fake call: silent") }
                }
            }
            item { HorizontalDivider(); Text("Timeline (newest first)", style = MaterialTheme.typography.titleSmall) }
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }

    private fun act(action: String, scenario: String? = null) {
        val intent = Intent().putExtra("probe_action", action)
        if (scenario != null) intent.putExtra("scenario", scenario)
        ProbeActions.run(this, intent)
        refresh.intValue++
    }

    @Composable
    private fun Stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(value, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = minus) { Text("−5") }
            OutlinedButton(onClick = plus) { Text("+5") }
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
