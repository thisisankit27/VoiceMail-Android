package io.github.thisisankit27.voicemail.ui.voicemail

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.data.RecordingStatus
import io.github.thisisankit27.voicemail.data.Voicemail
import io.github.thisisankit27.voicemail.greeting.GreetingKind
import io.github.thisisankit27.voicemail.ui.ServiceState
import io.github.thisisankit27.voicemail.ui.SetupSnapshot
import io.github.thisisankit27.voicemail.ui.components.Avatar
import io.github.thisisankit27.voicemail.ui.components.EmptyState
import io.github.thisisankit27.voicemail.ui.components.Formats
import io.github.thisisankit27.voicemail.ui.components.StatusPill
import io.github.thisisankit27.voicemail.ui.components.StatusTone
import io.github.thisisankit27.voicemail.ui.rememberSetupSnapshot
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { ALL("All"), UNREAD("Unread"), STARRED("Starred") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicemailScreen(
    openId: String?,
    onOpenSetup: () -> Unit,
    onOpenGreeting: () -> Unit,
    onOpenSettings: () -> Unit,
    onCall: (String) -> Unit,
    showTopBar: Boolean = true,
) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.voicemails
    val scope = rememberCoroutineScope()
    val all by repo.all.collectAsState(initial = null)
    val playerState by app.player.state.collectAsState()
    val setup = rememberSetupSnapshot()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var expanded by rememberSaveable { mutableStateOf(openId) }
    val listState = rememberLazyListState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(openId) {
        if (openId != null) {
            expanded = openId
            repo.setRead(openId, true)
            app.notifier.cancelVoicemail(openId)
        }
    }

    val items = all.orEmpty().filter {
        when (filter) {
            Filter.ALL -> true
            Filter.UNREAD -> !it.isRead && it.status == RecordingStatus.RECORDED
            Filter.STARRED -> it.isStarred
        }
    }

    fun delete(v: Voicemail) = scope.launch {
        if (playerState.itemId == v.id) app.player.stop()
        repo.softDelete(v.id)
        val result = snackbar.showSnackbar("Voicemail deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) repo.undoDelete(v.id) else repo.purgeDeleted()
    }

    Scaffold(
        modifier = if (showTopBar) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier,
        topBar = {
            if (showTopBar) {
                LargeTopAppBar(
                    title = { Text("Voicemail") },
                    actions = {
                        if (all.orEmpty().any { !it.isRead }) IconButton(onClick = { scope.launch { repo.markAllRead() } }) { Icon(Icons.Filled.DoneAll, "Mark all as read") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                    },
                    scrollBehavior = scroll,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize().padding(pad)) {
            item { StatusCard(setup, onOpenSetup, onOpenGreeting) }
            if (all.orEmpty().isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f ->
                            val count = when (f) {
                                Filter.UNREAD -> all.orEmpty().count { !it.isRead && it.status == RecordingStatus.RECORDED }
                                Filter.STARRED -> all.orEmpty().count { it.isStarred }
                                Filter.ALL -> all.orEmpty().size
                            }
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(if (count > 0) "${f.label} · $count" else f.label) })
                        }
                    }
                }
            }
            when {
                all == null -> Unit
                items.isEmpty() -> item {
                    if (setup.state == ServiceState.ACTIVE || filter != Filter.ALL) {
                        EmptyState(
                            Icons.Filled.Voicemail,
                            if (filter == Filter.ALL) "No voicemail yet" else "Nothing here",
                            if (filter == Filter.ALL) "When you miss a call, the caller hears your greeting and can leave you a message. It shows up here." else "No ${filter.label.lowercase()} messages.",
                        )
                    } else {
                        EmptyState(
                            Icons.Filled.Voicemail, "Let callers leave you a message",
                            "Finish setup and your phone answers calls you miss, plays your greeting and keeps the message. Everything stays on this phone.",
                        ) { Button(onClick = onOpenSetup) { Text("Set up voicemail") } }
                    }
                }
                else -> {
                    var lastHeader: String? = null
                    items.forEach { v ->
                        val header = Formats.dayHeader(v.createdAt)
                        if (header != lastHeader) {
                            lastHeader = header
                            item(key = "h-$header") {
                                Text(header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                            }
                        }
                        item(key = v.id) {
                            VoicemailRow(
                                v = v,
                                expanded = expanded == v.id,
                                onClick = {
                                    expanded = if (expanded == v.id) null else v.id
                                    if (!v.isRead) scope.launch { repo.setRead(v.id, true); app.notifier.cancelVoicemail(v.id) }
                                },
                                onDelete = { delete(v) },
                                onCall = onCall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(setup: SetupSnapshot, onOpenSetup: () -> Unit, onOpenGreeting: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val settings = setup.settings
    val (label, tone, body) = when (setup.state) {
        ServiceState.ACTIVE -> Triple("Answering missed calls", StatusTone.GOOD, "After ${settings?.ringSeconds ?: 20} seconds of ringing, callers hear your greeting and can leave a message.")
        ServiceState.PAUSED_HELPER -> Triple("Paused", StatusTone.WARN, "The audio helper isn't running, so missed calls ring out normally. It restarts by itself on Wi-Fi.")
        ServiceState.OFF -> Triple("Off", StatusTone.NEUTRAL, "Missed calls ring out normally. Turn voicemail on in setup.")
        ServiceState.NEEDS_SETUP -> Triple("Setup needed", StatusTone.BAD, "A few one-time steps are left before voicemail can answer calls.")
        ServiceState.CHECKING -> Triple("Checking…", StatusTone.NEUTRAL, "Checking the audio helper.")
    }
    Card(
        onClick = onOpenSetup,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(label, tone)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpenGreeting).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Hearing, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Your greeting", style = MaterialTheme.typography.labelLarge)
                        Text(
                            app.greetings.text(settings, if (settings.allowMessages) GreetingKind.MESSAGE else GreetingKind.CLOSING),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Filled.Edit, "Edit greeting", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicemailRow(v: Voicemail, expanded: Boolean, onClick: () -> Unit, onDelete: () -> Unit, onCall: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val contact = remember(v.callerNumber) { mutableStateOf(v.callerName) }
    LaunchedEffect(v.callerNumber) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.contacts.lookup(v.callerNumber) }?.let { contact.value = it.name }
    }
    val name = contact.value ?: app.contacts.format(v.callerNumber)
    val empty = v.status != RecordingStatus.RECORDED
    val unread = !v.isRead && !empty
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    val bg by animateColorAsState(if (expanded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "row")

    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = if (empty) 4.dp else 10.dp)) {
            if (empty && !expanded) {
                // Callers who hung up without a message: one quiet line, so real messages stand out.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                    Icon(Icons.AutoMirrored.Filled.CallMissed, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(24.dp))
                    Text(
                        "$name hung up · ${Formats.relative(v.createdAt)}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, null, v.callerNumber)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (empty) "${Formats.relative(v.createdAt)} · Hung up without a message" else "${Formats.relative(v.createdAt)} · ${Formats.duration(v.durationMs)}" + (v.simLabel?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (v.isStarred) Icon(Icons.Filled.Star, "Starred", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                if (unread) Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = "Unread" })
            }
            AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!empty) app.voicemails.audio(v)?.let { file -> PlayerControls(v.id, file, v.durationMs) }
                    v.transcript?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        v.callerNumber?.let { number ->
                            Action(Icons.Filled.Call, "Call back") { onCall(number) }
                            Action(Icons.AutoMirrored.Filled.Message, "Message") {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        if (!empty) {
                            Action(Icons.Filled.Share, "Share") { share(context, v) }
                            Action(if (v.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder, if (v.isStarred) "Unstar" else "Star") {
                                scope.launch { app.voicemails.setStarred(v.id, !v.isStarred) }
                            }
                            Action(Icons.Filled.MarkEmailUnread, "Unread") { scope.launch { app.voicemails.setRead(v.id, false) } }
                        }
                        Action(Icons.Filled.Delete, "Delete", onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(id: String, file: java.io.File, knownDurationMs: Long) {
    val app = LocalContext.current.app
    val state by app.player.state.collectAsState()
    val mine = state.itemId == id
    val duration = if (mine && state.durationMs > 0) state.durationMs else knownDurationMs
    val position = if (mine) state.positionMs else 0
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { app.player.toggle(id, file) }, modifier = Modifier.size(48.dp)) {
                Icon(if (mine && state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (mine && state.playing) "Pause" else "Play")
            }
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { if (mine) app.player.seekTo((it * duration).toLong()) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${Formats.duration(position)} / ${Formats.duration(duration)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { app.player.skip(-10_000) }, enabled = mine) { Icon(Icons.Filled.Replay10, "Back 10 seconds") }
            IconButton(onClick = { app.player.skip(10_000) }, enabled = mine) { Icon(Icons.Filled.Forward10, "Forward 10 seconds") }
            TextButton(onClick = { app.player.cycleSpeed() }) { Text("${state.speed}×".replace(".0×", "×")) }
            FilledTonalButton(onClick = { app.player.setSpeaker(!state.speaker) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(if (state.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Hearing, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.speaker) "Speaker" else "Earpiece")
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun share(context: android.content.Context, v: Voicemail) {
    val file = context.app.voicemails.audio(v) ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share voicemail",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
