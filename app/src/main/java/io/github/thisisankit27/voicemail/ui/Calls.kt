package io.github.thisisankit27.voicemail.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.widget.Toast
import io.github.thisisankit27.voicemail.core.AppLog

/** Places an outgoing call through Telecom (which also handles emergency numbers correctly). */
fun Context.placeCall(number: String, account: PhoneAccountHandle? = null) {
    if (!granted(Manifest.permission.CALL_PHONE)) {
        Toast.makeText(this, "Allow the Phone permission to make calls", Toast.LENGTH_SHORT).show()
        return
    }
    val extras = Bundle().apply { account?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) } }
    try {
        getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), extras)
    } catch (e: SecurityException) {
        AppLog.w("placeCall refused", e)
        Toast.makeText(this, "Couldn't place the call", Toast.LENGTH_SHORT).show()
    }
}
