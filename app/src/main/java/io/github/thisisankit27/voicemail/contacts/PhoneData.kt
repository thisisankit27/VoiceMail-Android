package io.github.thisisankit27.voicemail.contacts

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Emits once now and again whenever [uri] changes (debounced by the consumer's recomposition). */
private fun Context.changes(uri: Uri): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
    }
    trySend(Unit)
    try { contentResolver.registerContentObserver(uri, true, observer) } catch (_: SecurityException) { }
    awaitClose { contentResolver.unregisterContentObserver(observer) }
}

private fun Context.has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

enum class CallKind { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, OTHER }

/** One row in Recents: consecutive calls with the same number and kind are grouped. */
data class RecentCall(
    val ids: List<Long>,
    val number: String,
    val cachedName: String?,
    val kind: CallKind,
    val date: Long,
    val durationSec: Long,
    val count: Int,
    val isNew: Boolean,
    val accountId: String?,
    val photoUri: String?,
)

class CallLogRepository(private val context: Context) {
    fun hasPermission() = context.has(Manifest.permission.READ_CALL_LOG)

    val recents: Flow<List<RecentCall>> = context.changes(CallLog.Calls.CONTENT_URI)
        .map { if (hasPermission()) query() else emptyList() }
        .flowOn(Dispatchers.IO)

    private fun query(): List<RecentCall> {
        val rows = mutableListOf<RecentCall>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "500").build(),
                arrayOf(
                    CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE,
                    CallLog.Calls.DURATION, CallLog.Calls.NEW, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.CACHED_PHOTO_URI,
                ),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val kind = when (c.getInt(3)) {
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallKind.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallKind.REJECTED
                        CallLog.Calls.BLOCKED_TYPE -> CallKind.BLOCKED
                        CallLog.Calls.VOICEMAIL_TYPE -> CallKind.VOICEMAIL
                        else -> CallKind.OTHER
                    }
                    rows += RecentCall(
                        listOf(c.getLong(0)), c.getString(1).orEmpty(), c.getString(2)?.ifBlank { null }, kind, c.getLong(4),
                        c.getLong(5), 1, c.getInt(6) == 1 && kind == CallKind.MISSED, c.getString(7), c.getString(8),
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return group(rows)
    }

    fun delete(ids: List<Long>) {
        if (!context.has(Manifest.permission.WRITE_CALL_LOG)) return
        context.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ids.joinToString()})", null)
    }

    /** Clears the "new" flag on missed calls and the system missed-call notification. */
    fun markMissedSeen() {
        if (context.has(Manifest.permission.WRITE_CALL_LOG)) {
            runCatching {
                context.contentResolver.update(
                    CallLog.Calls.CONTENT_URI, ContentValues().apply { put(CallLog.Calls.NEW, 0) },
                    "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}", null,
                )
            }
        }
        runCatching { context.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification() }
    }

    companion object {
        /** Groups consecutive calls with the same number and kind on the same day (like most dialers). */
        fun group(rows: List<RecentCall>): List<RecentCall> {
            val out = mutableListOf<RecentCall>()
            for (r in rows) {
                val last = out.lastOrNull()
                if (last != null && last.kind == r.kind && PhoneNumberUtils.compare(last.number, r.number) &&
                    sameDay(last.date, r.date)
                ) {
                    out[out.lastIndex] = last.copy(ids = last.ids + r.ids, count = last.count + 1, isNew = last.isNew || r.isNew)
                } else {
                    out += r
                }
            }
            return out
        }

        private fun sameDay(a: Long, b: Long): Boolean {
            val zone = java.time.ZoneId.systemDefault()
            return java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        }
    }
}

/** A phone number belonging to a contact. */
data class ContactPhone(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val number: String,
    val label: String,
    val photoUri: String?,
    val starred: Boolean,
)

/** A contact with all its numbers. */
data class Contact(val id: Long, val lookupKey: String?, val name: String, val photoUri: String?, val starred: Boolean, val phones: List<ContactPhone>) {
    val uri: Uri get() = ContactsContract.Contacts.getLookupUri(id, lookupKey)
}

class ContactsRepository(private val context: Context) {
    fun hasPermission() = context.has(Manifest.permission.READ_CONTACTS)

    val contacts: Flow<List<Contact>> = context.changes(ContactsContract.Contacts.CONTENT_URI)
        .map { if (hasPermission()) query() else emptyList() }
        .flowOn(Dispatchers.IO)

    private fun query(): List<Contact> {
        val phones = mutableListOf<ContactPhone>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.STARRED,
                ),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(4), c.getString(5)).toString()
                    phones += ContactPhone(c.getLong(0), c.getString(1), c.getString(2) ?: continue, c.getString(3) ?: continue, label, c.getString(6), c.getInt(7) == 1)
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return phones.groupBy { it.contactId }.map { (id, list) ->
            val first = list.first()
            // The same number is often stored twice in different formats; keep one.
            val unique = list.distinctBy { PhoneNumberUtils.normalizeNumber(it.number).takeLast(10) }
            Contact(id, first.lookupKey, first.name, first.photoUri, first.starred, unique)
        }
    }

    companion object {
        private val T9 = mapOf(
            'a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3', 'g' to '4', 'h' to '4', 'i' to '4',
            'j' to '5', 'k' to '5', 'l' to '5', 'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
            't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
        )

        fun t9(name: String): String = name.lowercase().map { T9[it] ?: if (it.isDigit()) it else ' ' }.joinToString("")

        /** Dial-pad suggestions: number contains the digits, or a word of the name starts with them on T9. */
        fun matchDigits(contacts: List<Contact>, digits: String, limit: Int = 5): List<ContactPhone> {
            if (digits.length < 2) return emptyList()
            val clean = digits.filter { it.isDigit() }
            if (clean.isEmpty()) return emptyList()
            // Rank: first name on T9, then any other word, then the number itself.
            fun rank(p: ContactPhone): Int? {
                val words = t9(p.name).split(' ').filter { it.isNotEmpty() }
                return when {
                    words.firstOrNull()?.startsWith(clean) == true -> 0
                    words.drop(1).any { it.startsWith(clean) } || words.joinToString("").startsWith(clean) -> 1
                    PhoneNumberUtils.normalizeNumber(p.number).contains(clean) -> 2
                    else -> null
                }
            }
            return contacts.asSequence().flatMap { it.phones.asSequence() }
                .mapNotNull { p -> rank(p)?.let { it to p } }
                .sortedBy { it.first }
                .map { it.second }
                .distinctBy { it.contactId }.take(limit).toList()
        }

        fun matchText(contacts: List<Contact>, query: String): List<Contact> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return contacts
            val digits = q.filter { it.isDigit() }
            return contacts.filter { c ->
                c.name.lowercase().split(' ').any { it.startsWith(q) } || c.name.lowercase().contains(q) ||
                    (digits.length >= 3 && c.phones.any { PhoneNumberUtils.normalizeNumber(it.number).contains(digits) })
            }
        }
    }
}

/** A SIM / calling account the user can place calls from. */
data class SimAccount(val handle: PhoneAccountHandle, val label: String, val detail: String?)

class SimAccounts(private val context: Context) {
    fun list(): List<SimAccount> {
        if (!context.has(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val telecom = context.getSystemService(TelecomManager::class.java)
        return try {
            telecom.callCapablePhoneAccounts.mapNotNull { h ->
                val acct = telecom.getPhoneAccount(h) ?: return@mapNotNull null
                SimAccount(h, acct.label?.toString() ?: "SIM", acct.shortDescription?.toString())
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** The user's default for outgoing calls, or null for "ask every time". */
    fun defaultAccount(): PhoneAccountHandle? = try {
        context.getSystemService(TelecomManager::class.java).getDefaultOutgoingPhoneAccount("tel")
    } catch (_: SecurityException) {
        null
    }
}
