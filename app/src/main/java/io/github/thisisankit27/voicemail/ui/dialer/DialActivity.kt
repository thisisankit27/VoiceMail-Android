package io.github.thisisankit27.voicemail.ui.dialer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.thisisankit27.voicemail.ui.theme.VoicemailTheme

/** ACTION_DIAL / tel: handler (required for ROLE_DIALER). Shows the dial pad with the number filled in. */
class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefilled = intent?.data?.schemeSpecificPart.orEmpty()
        setContent { VoicemailTheme { DialpadScreen(initial = prefilled, onBack = { finish() }, onPlaced = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadScreen(initial: String = "", onBack: () -> Unit, onPlaced: () -> Unit = onBack) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Keypad") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.BottomCenter) {
            Dialpad(initial = initial, onPlaced = onPlaced)
        }
    }
}
