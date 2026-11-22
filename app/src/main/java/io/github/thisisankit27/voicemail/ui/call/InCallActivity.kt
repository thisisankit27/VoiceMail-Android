package io.github.thisisankit27.voicemail.ui.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.telephony.CallManager
import io.github.thisisankit27.voicemail.telephony.CallPhase
import io.github.thisisankit27.voicemail.telephony.CallUi
import io.github.thisisankit27.voicemail.ui.theme.CallColors
import io.github.thisisankit27.voicemail.ui.theme.VoicemailTheme

/** Incoming / in-call screen (PR-1 minimum; the full call UI comes with the dialer work). */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent { VoicemailTheme { Surface { Screen() } } }
    }

    @Composable
    private fun Screen() {
        val calls by CallManager.calls.collectAsState()
        LaunchedEffect(calls.isEmpty()) { if (calls.isEmpty()) finish() }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            calls.forEach { CallCard(it) }
        }
    }

    @Composable
    private fun CallCard(call: CallUi) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(call.displayName, style = MaterialTheme.typography.headlineMedium)
            Text("${call.phase} ${call.voicemail ?: ""}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (call.phase == CallPhase.RINGING) {
                    Button(onClick = { CallManager.answer(call.id) }, colors = ButtonDefaults.buttonColors(containerColor = CallColors.accept)) { Text("Answer") }
                    Button(onClick = { CallManager.reject(call.id) }, colors = ButtonDefaults.buttonColors(containerColor = CallColors.decline)) { Text("Decline") }
                    if (call.voicemail != null) OutlinedButton(onClick = { app.engine.session(call.id)?.sendToVoicemailNow() }) { Text("To voicemail") }
                } else {
                    if (call.voicemail != null) OutlinedButton(onClick = { app.engine.session(call.id)?.takeOver() }) { Text("Take over") }
                    Button(onClick = { CallManager.hangup(call.id) }, colors = ButtonDefaults.buttonColors(containerColor = CallColors.decline)) { Text("End") }
                }
            }
        }
    }
}
