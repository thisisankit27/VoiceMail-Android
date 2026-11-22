package io.github.thisisankit27.voicemail.probe

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Minimal ACTION_DIAL handler, required for ROLE_DIALER. Emergency numbers are always routed to
 * the preloaded system dialer by the platform, so the probe never handles them.
 */
class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefilled = intent?.data?.schemeSpecificPart.orEmpty()
        setContent {
            MaterialTheme {
                Surface {
                    var number by remember { mutableStateOf(prefilled) }
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Probe dialer", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            value = number,
                            onValueChange = { number = it },
                            label = { Text("Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { place(number) }, enabled = number.isNotBlank()) { Text("Call") }
                    }
                }
            }
        }
    }

    private fun place(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            ProbeLog.i("placeCall to ${redactNumber(number)}")
        } catch (e: SecurityException) {
            ProbeLog.e("placeCall refused", e)
        }
    }
}
