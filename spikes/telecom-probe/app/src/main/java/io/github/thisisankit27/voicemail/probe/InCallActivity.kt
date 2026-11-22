package io.github.thisisankit27.voicemail.probe

import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Incoming + ongoing call UI, the minimum ROLE_DIALER requires. Shown over the lock screen. */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.i("InCallActivity shown")
        setContent { MaterialTheme { Surface { InCallScreen() } } }
    }

    @Composable
    private fun InCallScreen() {
        val calls by CallRegistry.calls.collectAsState()
        val revision by CallRegistry.revision.collectAsState()
        val lines by ProbeLog.lines.collectAsState()
        LaunchedEffect(calls.isEmpty()) { if (calls.isEmpty()) finish() }

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Probe in-call", style = MaterialTheme.typography.headlineSmall)
            calls.forEach { CallCard(it, revision) }
            Text("Timeline", style = MaterialTheme.typography.titleSmall)
            LazyColumn { items(lines.take(40)) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
        }
    }

    @Composable
    private fun CallCard(call: Call, revision: Int) {
        val state = call.stateCompat()
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(call.describe() + "  (rev $revision)", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == Call.STATE_RINGING) {
                        Button(onClick = { call.answer(VideoProfile.STATE_AUDIO_ONLY) }) { Text("Answer") }
                        Button(
                            onClick = { call.reject(false, null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        ) { Text("Decline") }
                    } else {
                        Button(
                            onClick = { call.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        ) { Text("End") }
                        OutlinedButton(onClick = { ProbeInCallService.instance?.routeToSpeaker(true) }) { Text("Speaker") }
                        OutlinedButton(onClick = { ProbeInCallService.instance?.routeToSpeaker(false) }) { Text("Earpiece") }
                        if (state == Call.STATE_ACTIVE) {
                            OutlinedButton(onClick = { ProbeInCallService.instance?.manualCapture(call) }) { Text("E4 capture now") }
                        }
                    }
                }
            }
        }
    }
}
