package io.github.thisisankit27.voicemail.ui.dialer

import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.contacts.ContactsRepository
import io.github.thisisankit27.voicemail.contacts.SimAccount
import io.github.thisisankit27.voicemail.ui.components.Avatar
import io.github.thisisankit27.voicemail.ui.placeCall
import io.github.thisisankit27.voicemail.ui.theme.CallColors

private val KEYS = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

/** Formats as the user types (e.g. "98765 43210" or "+1 555-0100"), using libphonenumber. */
fun formatAsYouType(raw: String, region: String): String {
    if (raw.any { it == '*' || it == '#' || it == ',' || it == ';' }) return raw
    val f = PhoneNumberUtil.getInstance().getAsYouTypeFormatter(region)
    var out = raw
    raw.forEach { out = f.inputDigit(it) }
    return out
}

/**
 * T9 dial pad with contact suggestions, paste, long-press 0 for "+", local key tones (when the
 * system "Dial pad tones" setting is on), and a call button with a SIM picker on dual-SIM phones.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dialpad(initial: String = "", onPlaced: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.app
    val haptics = LocalHapticFeedback.current
    var number by remember { mutableStateOf(initial.filter { it.isDigit() || it in "+*#,;" }) }
    var simPicker by remember { mutableStateOf(false) }
    val contacts by app.contactsRepo.contacts.collectAsState(initial = emptyList())
    val suggestions = remember(number, contacts) { ContactsRepository.matchDigits(contacts, number) }
    val region = remember { app.contacts.countryIso() }
    val tones = remember {
        if (Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1)
            runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull() else null
    }
    DisposableEffect(Unit) { onDispose { tones?.release() } }

    fun press(c: Char) {
        number += c
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        tones?.startTone(toneFor(c), 120)
    }

    fun call(account: SimAccount? = null) {
        if (number.isBlank()) {
            // Empty dial pad + call fills in the last number you dialled, like most dialers.
            number = runCatching { android.provider.CallLog.Calls.getLastOutgoingCall(context) }.getOrNull().orEmpty()
            return
        }
        val sims = app.sims.list()
        if (account == null && sims.size > 1 && app.sims.defaultAccount() == null) { simPicker = true; return }
        context.placeCall(number, account?.handle)
        onPlaced()
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Suggestions
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            suggestions.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { number = p.number }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p.name, p.photoUri, p.name, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.label} · ${p.number}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { number = p.number; call() }) { Icon(Icons.Filled.Call, "Call ${p.name}", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }

        // Number display
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp)) {
                if (number.isNotEmpty()) IconButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Icon(Icons.Filled.PersonAdd, "Add to contacts") }
                else {
                    val clip = remember { context.getSystemService(ClipboardManager::class.java) }
                    IconButton(onClick = {
                        clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?.filter { it.isDigit() || it in "+*#" }?.takeIf { it.isNotEmpty() }?.let { number = it }
                    }) { Icon(Icons.Filled.ContentPaste, "Paste number") }
                }
            }
            Text(
                formatAsYouType(number, region),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Number: $number" },
                style = MaterialTheme.typography.displaySmall.copy(fontSize = if (number.length > 13) 28.sp else 36.sp),
                textAlign = TextAlign.Center, maxLines = 1,
            )
            Box(Modifier.size(48.dp)) {
                if (number.isNotEmpty()) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                            onClick = { number = number.dropLast(1) },
                            onLongClick = { number = ""; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Backspace, "Delete (hold to clear)") }
                }
            }
        }

        // Keys
        Column(Modifier.padding(horizontal = 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KEYS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { (digit, letters) ->
                        Key(
                            digit, letters, Modifier.weight(1f),
                            onClick = { press(digit) },
                            onLongClick = when (digit) {
                                '0' -> ({ number += '+'; haptics.performHapticFeedback(HapticFeedbackType.LongPress) })
                                '*' -> ({ number += ',' })
                                '#' -> ({ number += ';' })
                                else -> null
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(CallColors.accept).clickable { call() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Call, "Call", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(16.dp))
    }

    if (simPicker) {
        AlertDialog(
            onDismissRequest = { simPicker = false },
            title = { Text("Call with") },
            text = {
                Column {
                    app.sims.list().forEach { sim ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { simPicker = false; call(sim) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.SimCard, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(sim.label, style = MaterialTheme.typography.bodyLarge)
                                sim.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { simPicker = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Key(digit: Char, letters: String, modifier: Modifier = Modifier, dark: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Surface(
        shape = CircleShape,
        color = if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (dark) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.aspectRatio(1.45f).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(digit.toString(), fontSize = 28.sp, fontWeight = FontWeight.Normal)
            if (letters.isNotEmpty()) Text(letters, fontSize = 10.sp, letterSpacing = 1.5.sp, color = LocalContentColorAlpha())
        }
    }
}

@Composable
private fun LocalContentColorAlpha() = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f)

fun toneFor(c: Char): Int = when (c) {
    in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (c - '0')
    '*' -> ToneGenerator.TONE_DTMF_S
    '#' -> ToneGenerator.TONE_DTMF_P
    else -> ToneGenerator.TONE_DTMF_0
}
