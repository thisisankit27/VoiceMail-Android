package io.github.thisisankit27.voicemail.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A contact matched to a phone number. */
data class ContactMatch(val name: String, val photoUri: String?, val lookupKey: String?, val starred: Boolean)

/** Resolves numbers to contacts (PhoneLookup) and formats numbers for display. Results are cached briefly. */
class ContactLookup(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Pair<Long, ContactMatch?>>()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Blocking (provider query); call off the main thread. Null when unknown or no permission. */
    fun lookup(number: String?): ContactMatch? {
        if (number.isNullOrBlank() || !hasPermission()) return null
        val now = System.currentTimeMillis()
        cache[number]?.let { (at, match) -> if (now - at < 60_000) return match }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val match = try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI,
                    ContactsContract.PhoneLookup.LOOKUP_KEY,
                    ContactsContract.PhoneLookup.STARRED,
                ),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) ContactMatch(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1) else null
            }
        } catch (_: Exception) {
            null
        }
        cache[number] = now to match
        return match
    }

    fun format(number: String?): String {
        if (number.isNullOrBlank()) return "Private number"
        return PhoneNumberUtils.formatNumber(number, countryIso()) ?: number
    }

    fun countryIso(): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return (tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: "in")
            .uppercase(Locale.US)
    }
}
