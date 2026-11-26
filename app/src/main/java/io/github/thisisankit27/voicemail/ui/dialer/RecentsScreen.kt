package io.github.thisisankit27.voicemail.ui.dialer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.contacts.CallKind
import io.github.thisisankit27.voicemail.contacts.RecentCall
import io.github.thisisankit27.voicemail.ui.components.Avatar
import io.github.thisisankit27.voicemail.ui.components.EmptyState
import io.github.thisisankit27.voicemail.ui.components.Formats
import io.github.thisisankit27.voicemail.ui.placeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RecentsScreen(onOpenVoicemail: (String?) -> Unit, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val recents by app.callLog.recents.collectAsState(initial = null)
    val voicemails by app.voicemails.all.collectAsState(initial = emptyList())
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { app.callLog.markMissedSeen() } }

    if (!app.callLog.hasPermission()) {
        EmptyState(Icons.Filled.History, "See your recent calls", "Allow access to your call log to see recent calls here.") {
            Button(onClick = onRequestPermissions) { Text("Allow") }
        }
        return
    }
    val list = recents.orEmpty().filter { !missedOnly || it.kind == CallKind.MISSED }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!missedOnly, { missedOnly = false }, label = { Text("All") })
                FilterChip(missedOnly, { missedOnly = true }, label = { Text("Missed") })
            }
        }
        if (recents != null && list.isEmpty()) item {
            EmptyState(Icons.Filled.History, if (missedOnly) "No missed calls" else "No recent calls", "Calls you make and receive show up here.")
        }
        var lastHeader: String? = null
        list.forEach { r ->
            val header = Formats.dayHeader(r.date)
            if (header != lastHeader) {
                lastHeader = header
                item(key = "h-$header-${r.ids.first()}") {
                    Text(header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                }
            }
            // The app's own voicemail answered this call if a message was saved within a minute of it.
            val vm = voicemails.firstOrNull { v -> kotlin.math.abs(v.createdAt - r.date) < 90_000 && android.telephony.PhoneNumberUtils.compare(v.callerNumber.orEmpty(), r.number) }
            item(key = r.ids.first()) {
                RecentRow(r, vm, expanded == r.ids.first(), onToggle = { expanded = if (expanded == r.ids.first()) null else r.ids.first() }, onOpenVoicemail = onOpenVoicemail)
            }
        }
    }
}

@Composable
private fun RecentRow(r: RecentCall, voicemail: io.github.thisisankit27.voicemail.data.Voicemail?, expanded: Boolean, onToggle: () -> Unit, onOpenVoicemail: (String?) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val contact by produceState(r.cachedName?.let { io.github.thisisankit27.voicemail.contacts.ContactMatch(it, r.photoUri, null, false) }, r.number) {
        withContext(Dispatchers.IO) { app.contacts.lookup(r.number) }?.let { value = it }
    }
    val name = contact?.name ?: app.contacts.format(r.number.ifBlank { null })
    val missed = r.kind == CallKind.MISSED || r.kind == CallKind.REJECTED
    val (icon, tint, what) = kindStyle(r.kind)
    Column(
        Modifier.fillMaxWidth()
            .background(if (expanded) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .clickable { if (r.number.isNotBlank()) context.placeCall(r.number) }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, contact?.photoUri, r.number)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name + if (r.count > 1) " (${r.count})" else "",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (r.isNew) FontWeight.Bold else FontWeight.Normal,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (voicemail != null) Icons.Filled.Voicemail else icon, null, Modifier.size(16.dp), tint = if (voicemail != null) MaterialTheme.colorScheme.primary else tint)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (when {
                            voicemail == null -> what
                            voicemail.status == io.github.thisisankit27.voicemail.data.RecordingStatus.RECORDED -> "Voicemail"
                            else -> "Voicemail · no message"
                        }) + " · " + Formats.relative(r.date) +
                            (if (r.durationSec > 0 && voicemail == null) " · " + Formats.duration(r.durationSec * 1000) else ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onToggle) { Icon(Icons.Filled.Info, "Details for $name", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        AnimatedVisibility(expanded) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (r.number.isNotBlank()) {
                    RowAction(Icons.Filled.Call, "Call") { context.placeCall(r.number) }
                    RowAction(Icons.AutoMirrored.Filled.Message, "Message") {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${r.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    if (voicemail?.status == io.github.thisisankit27.voicemail.data.RecordingStatus.RECORDED) RowAction(Icons.Filled.Voicemail, "Listen") { onOpenVoicemail(voicemail.id) }
                    if (contact == null) RowAction(Icons.Filled.PersonAdd, "Add") {
                        context.startActivity(
                            Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, r.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    RowAction(Icons.Filled.ContentCopy, "Copy") {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Phone number", r.number))
                        Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                    }
                }
                RowAction(Icons.Filled.Delete, "Delete") { app.callLog.delete(r.ids) }
            }
        }
    }
}

@Composable
private fun kindStyle(kind: CallKind): Triple<ImageVector, Color, String> = when (kind) {
    CallKind.INCOMING -> Triple(Icons.AutoMirrored.Filled.CallReceived, Color(0xFF1E8E3E), "Incoming")
    CallKind.OUTGOING -> Triple(Icons.AutoMirrored.Filled.CallMade, MaterialTheme.colorScheme.primary, "Outgoing")
    CallKind.MISSED -> Triple(Icons.AutoMirrored.Filled.CallMissed, MaterialTheme.colorScheme.error, "Missed")
    CallKind.REJECTED -> Triple(Icons.Filled.CallEnd, MaterialTheme.colorScheme.error, "Declined")
    CallKind.BLOCKED -> Triple(Icons.Filled.Block, MaterialTheme.colorScheme.onSurfaceVariant, "Blocked")
    CallKind.VOICEMAIL -> Triple(Icons.Filled.Voicemail, MaterialTheme.colorScheme.primary, "Voicemail")
    CallKind.OTHER -> Triple(Icons.Filled.Call, MaterialTheme.colorScheme.onSurfaceVariant, "Call")
}

@Composable
fun RowAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
