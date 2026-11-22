package io.github.thisisankit27.voicemail.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.ui.greeting.GreetingScreen
import io.github.thisisankit27.voicemail.ui.settings.LogScreen
import io.github.thisisankit27.voicemail.ui.settings.SettingsScreen
import io.github.thisisankit27.voicemail.ui.setup.SetupScreen
import io.github.thisisankit27.voicemail.ui.theme.VoicemailTheme
import io.github.thisisankit27.voicemail.ui.dialer.DialpadScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openVoicemail by mutableStateOf<String?>(null)
    private var openSetup by mutableStateOf(false)
    private var tab by mutableStateOf(Tab.RECENTS)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        lifecycleScope.launch {
            val s = app.settings.current()
            if (!s.onboardingDone && !isDefaultDialer()) openSetup = true
        }
        setContent { VoicemailTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        app.player.stop()
        super.onStop()
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_VOICEMAIL_ID)?.let { openVoicemail = it }
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETUP, false) == true) openSetup = true
        // The system's missed-call notification opens the default dialer's call log.
        if (intent?.action == android.content.Intent.ACTION_VIEW && intent.type == android.provider.CallLog.Calls.CONTENT_TYPE) tab = Tab.RECENTS
    }

    @Composable
    private fun AppNav() {
        val nav = rememberNavController()
        LaunchedEffect(openSetup) { if (openSetup) { nav.navigate(SETUP) { launchSingleTop = true }; openSetup = false } }
        LaunchedEffect(openVoicemail) { if (openVoicemail != null) { tab = Tab.VOICEMAIL; nav.popBackStack(HOME, inclusive = false) } }
        NavHost(nav, startDestination = HOME) {
            composable(HOME) {
                HomeScreen(
                    tab = tab,
                    onTab = { tab = it },
                    openVoicemailId = openVoicemail,
                    onOpenDialpad = { nav.navigate(DIALPAD) },
                    onOpenSetup = { nav.navigate(SETUP) },
                    onOpenGreeting = { nav.navigate(GREETING) },
                    onOpenSettings = { nav.navigate(SETTINGS) },
                )
            }
            composable(DIALPAD) { DialpadScreen(onBack = { nav.popBackStack() }) }
            composable(SETUP) { SetupScreen(onBack = { nav.popBackStack() }, onOpenGreeting = { nav.navigate(GREETING) }) }
            composable(GREETING) { GreetingScreen(onBack = { nav.popBackStack() }) }
            composable(SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSetup = { nav.navigate(SETUP) },
                    onOpenGreeting = { nav.navigate(GREETING) },
                    onOpenLog = { nav.navigate(LOG) },
                )
            }
            composable(LOG) { LogScreen(onBack = { nav.popBackStack() }) }
        }
    }

    companion object {
        const val EXTRA_VOICEMAIL_ID = "voicemail_id"
        const val EXTRA_OPEN_SETUP = "open_setup"
        private const val HOME = "home"
        private const val SETUP = "setup"
        private const val GREETING = "greeting"
        private const val SETTINGS = "settings"
        private const val LOG = "log"
        private const val DIALPAD = "dialpad"
    }
}
