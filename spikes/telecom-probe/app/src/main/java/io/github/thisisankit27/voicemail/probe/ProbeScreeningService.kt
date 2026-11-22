package io.github.thisisankit27.voicemail.probe

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * E10 instrument. Allows every call by default. With "screening reject" on, it rejects incoming
 * calls so that the second phone can record what a rejected caller hears on each operator.
 * Telecom gives the service 5 s to respond before the phone rings.
 */
class ProbeScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val reject = incoming && ProbeConfig(this).screeningReject
        ProbeLog.i(
            "E10 onScreenCall incoming=$incoming number=${redactNumber(callDetails.handle?.schemeSpecificPart)} " +
                "-> ${if (reject) "REJECT" else "ALLOW"}",
        )
        val response = CallResponse.Builder()
            .setDisallowCall(reject)
            .setRejectCall(reject)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }
}
