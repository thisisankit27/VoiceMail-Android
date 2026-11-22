package io.github.thisisankit27.voicemail.ui.dialer

import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.contacts.Contact
import io.github.thisisankit27.voicemail.contacts.ContactsRepository
import io.github.thisisankit27.voicemail.ui.components.Avatar
import io.github.thisisankit27.voicemail.ui.components.EmptyState
import io.github.thisisankit27.voicemail.ui.placeCall

@Composable
fun ContactsScreen(onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val contacts by app.contactsRepo.contacts.collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var choose by remember { mutableStateOf<Contact?>(null) }

    if (!app.contactsRepo.hasPermission()) {
        EmptyState(Icons.Filled.Contacts, "Your contacts", "Allow access to contacts to see and call them here.") {
            Button(onClick = onRequestPermissions) { Text("Allow") }
        }
        return
    }

    fun call(c: Contact) {
        if (c.phones.size == 1) context.placeCall(c.phones.first().number) else choose = c
    }

    val all = contacts.orEmpty()
    val filtered = remember(all, query) { ContactsRepository.matchText(all, query) }
    val favorites = all.filter { it.starred }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Search contacts") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (query.isBlank() && favorites.isNotEmpty()) {
            item { Text("Favourites", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 8.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(favorites, key = { it.id }) { c ->
                        Column(
                            Modifier.clip(RoundedCornerShape(16.dp)).clickable { call(c) }.padding(8.dp).widthIn(max = 80.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(c.name, c.photoUri, c.name, size = 60.dp)
                            Text(c.name.substringBefore(' '), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable {
                    context.startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PersonAdd, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(22.dp))
                Text("Create new contact", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (contacts != null && filtered.isEmpty()) item {
            EmptyState(Icons.Filled.Contacts, if (query.isBlank()) "No contacts with phone numbers" else "No matches", "")
        }
        var lastLetter: Char? = null
        filtered.forEach { c ->
            val letter = c.name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
            if (letter != lastLetter && query.isBlank()) {
                lastLetter = letter
                item(key = "l-$letter-${c.id}") {
                    Text(letter.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp))
                }
            }
            item(key = c.id) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, c.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.name, c.photoUri, c.name)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (query.isNotBlank() || c.phones.size > 1) Text(
                            c.phones.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { call(c) }) { Icon(Icons.Filled.Call, "Call ${c.name}", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }

    choose?.let { c ->
        AlertDialog(
            onDismissRequest = { choose = null },
            title = { Text("Call ${c.name}") },
            text = {
                Column {
                    c.phones.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { choose = null; context.placeCall(p.number) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.number, style = MaterialTheme.typography.bodyLarge)
                                Text(p.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.Call, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = null }) { Text("Cancel") } },
        )
    }
}
