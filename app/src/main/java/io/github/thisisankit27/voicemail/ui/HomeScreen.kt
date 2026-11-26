package io.github.thisisankit27.voicemail.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.ui.dialer.ContactsScreen
import io.github.thisisankit27.voicemail.ui.dialer.RecentsScreen
import io.github.thisisankit27.voicemail.ui.voicemail.VoicemailScreen
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    RECENTS("Recents", Icons.Filled.History),
    CONTACTS("Contacts", Icons.Filled.Contacts),
    VOICEMAIL("Voicemail", Icons.Filled.Voicemail),
}

/** The phone app's home: Recents · Contacts · Voicemail, with the dial pad one tap away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    openVoicemailId: String?,
    onOpenDialpad: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenGreeting: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val unread by app.voicemails.unreadCount.collectAsState(initial = 0)
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val askAll = {
        perms.launch(
            arrayOf(
                Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    if (tab == Tab.VOICEMAIL && unread > 0) IconButton(onClick = { scope.launch { app.voicemails.markAllRead() } }) { Icon(Icons.Filled.DoneAll, "Mark all as read") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = {
                            if (t == Tab.VOICEMAIL && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { Icon(t.icon, null) }
                            else Icon(t.icon, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenDialpad) { Icon(Icons.Filled.Dialpad, "Open dial pad") }
        },
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.RECENTS -> RecentsScreen(onOpenVoicemail = { onTab(Tab.VOICEMAIL) }, onRequestPermissions = askAll)
                Tab.CONTACTS -> ContactsScreen(onRequestPermissions = askAll)
                Tab.VOICEMAIL -> VoicemailScreen(
                    openId = openVoicemailId,
                    onOpenSetup = onOpenSetup,
                    onOpenGreeting = onOpenGreeting,
                    onOpenSettings = onOpenSettings,
                    onCall = { context.placeCall(it) },
                    showTopBar = false,
                )
            }
        }
    }
}
