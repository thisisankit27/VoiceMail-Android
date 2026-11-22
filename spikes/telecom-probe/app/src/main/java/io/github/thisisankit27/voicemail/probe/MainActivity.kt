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
import java.io.File
import kotlin.concurrent.thread

/**
 * Control panel for the Phase 0 experiments. Every action can also be driven over ADB so runs are
 * reproducible:  adb shell am start -n io.github.thisisankit27.voicemail.probe/.MainActivity --es probe_action <action>
 * Actions: status, tts_voices, render_greeting, play_greeting, mic_baseline_all,
 *          set_auto_answer (--ei value N), set_source (--ei value N), set_usage (--ei value N), set_speaker (--ez value B)
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
        if (intent == null) return
        val action = intent.getStringExtra("probe_action") ?: return
        val config = ProbeConfig(this)
        ProbeLog.i("adb action: $action")
        when (action) {
            "status" -> logStatus()
            "tts_voices" -> TtsProbe.run(this, render = false)
            "render_greeting" -> TtsProbe.run(this, render = true)
            "play_greeting" -> playGreetingLocally()
            "mic_baseline_all" -> micBaselineAll()
            "set_auto_answer" -> config.autoAnswerSeconds = intent.getIntExtra("value", 0)
            "set_source" -> config.captureSource = intent.getIntExtra("value", config.captureSource)
            "set_usage" -> config.greetingUsage = intent.getIntExtra("value", config.greetingUsage)
            "set_speaker" -> config.speakerOnAnswer = intent.getBooleanExtra("value", true)
            else -> ProbeLog.i("unknown probe_action")
        }
        if (action.startsWith("set_")) ProbeLog.i("config: ${config.summary()}")
        refresh.intValue++
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

    private fun playGreetingLocally() {
        val player = GreetingPlayer(this, ProbeConfig(this).greetingUsage)
        player.play { player.beep { player.release() } }
    }

    /** E4 baseline: the noise floor and silencing state of every source with no call active. */
    private fun micBaselineAll() {
        thread(name = "baseline") {
            for (source in ProbeConfig.SOURCES) {
                val name = ProbeConfig.sourceName(source)
                val file = File(CaptureProbe.captureDir(this), "baseline-$name.wav")
                val done = java.util.concurrent.CountDownLatch(1)
                CaptureProbe(this, source, file).start(3, "E4(baseline)") { done.countDown() }
                done.await()
            }
            ProbeLog.i("E4(baseline) all sources done")
        }
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
                    OutlinedButton(onClick = { TtsProbe.run(this@MainActivity, render = false) }) { Text("List TTS voices (E7)") }
                    OutlinedButton(onClick = { TtsProbe.run(this@MainActivity, render = true) }) { Text("Render greeting (E7)") }
                    OutlinedButton(onClick = { playGreetingLocally() }) { Text("Play greeting") }
                    OutlinedButton(onClick = { micBaselineAll() }) { Text("Mic baseline, all sources") }
                }
            }
            item { HorizontalDivider(); Text("Timeline (newest first)", style = MaterialTheme.typography.titleSmall) }
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
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
