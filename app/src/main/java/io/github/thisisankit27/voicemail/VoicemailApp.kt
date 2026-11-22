package io.github.thisisankit27.voicemail

import android.app.Application
import android.content.Context
import androidx.room.Room
import io.github.thisisankit27.voicemail.contacts.ContactLookup
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.data.AppDatabase
import io.github.thisisankit27.voicemail.data.RecordingStatus
import io.github.thisisankit27.voicemail.data.SettingsStore
import io.github.thisisankit27.voicemail.data.VoicemailRepository
import io.github.thisisankit27.voicemail.ui.voicemail.VoicemailPlayer
import io.github.thisisankit27.voicemail.greeting.GreetingRepository
import io.github.thisisankit27.voicemail.helper.HelperLauncher
import io.github.thisisankit27.voicemail.helper.HelperStatus
import io.github.thisisankit27.voicemail.helper.HelperWatchdog
import io.github.thisisankit27.voicemail.notifications.Notifier
import io.github.thisisankit27.voicemail.telephony.VoicemailEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Application and hand-written dependency graph (no DI framework: the graph is small). */
class VoicemailApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsStore(this) }
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "voicemail.db").build() }
    val contacts by lazy { ContactLookup(this) }
    val greetings by lazy { GreetingRepository(this) }
    val notifier by lazy { Notifier(this, contacts) }
    val voicemails by lazy { VoicemailRepository(this, database.voicemails()) }
    val player by lazy { VoicemailPlayer(this) }
    val callLog by lazy { io.github.thisisankit27.voicemail.contacts.CallLogRepository(this) }
    val contactsRepo by lazy { io.github.thisisankit27.voicemail.contacts.ContactsRepository(this) }
    val sims by lazy { io.github.thisisankit27.voicemail.contacts.SimAccounts(this) }
    val helper: HelperLauncher by lazy { HelperLauncher(this) }
    val engine by lazy {
        VoicemailEngine(
            context = this,
            settingsStore = settings,
            greetings = greetings,
            voicemails = database.voicemails(),
            contacts = contacts,
            helperClient = helper.client,
            notifier = notifier,
            scope = appScope,
            onHelperDown = {
                notifier.helperDown("A call came in but the audio helper wasn't running, so voicemail didn't answer. Tap to restart it.")
                HelperWatchdog.startSoon(this, "helper missing during a call")
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i("process started")
        notifier.createChannels()
        HelperWatchdog.schedule(this)
        appScope.launch { settings.migrate(); recoverInterruptedRecordings(); voicemails.purgeDeleted() }
    }

    /** Reflects a helper start result in the status notification. */
    fun onHelperStatus(status: HelperStatus) {
        when (status) {
            is HelperStatus.Running -> notifier.helperUp()
            is HelperStatus.Stopped -> notifier.helperDown(describe(status))
            else -> Unit
        }
    }

    /** A process killed mid-message leaves a RECORDING row; its WAV prefix is valid, so keep it. */
    private suspend fun recoverInterruptedRecordings() {
        val dao = database.voicemails()
        for (v in dao.interrupted()) {
            val file = v.audioFile?.let { File(File(filesDir, "voicemails"), it) }
            val ms = file?.takeIf { it.exists() }?.let { (it.length() - 44).coerceAtLeast(0) / 2 * 1000 / 16_000 } ?: 0
            dao.update(v.copy(status = if (ms > 1000) RecordingStatus.RECORDED else RecordingStatus.EMPTY, durationMs = ms))
            AppLog.i("recovered interrupted voicemail (${ms} ms)")
        }
    }

    companion object {
        fun describe(status: HelperStatus.Stopped): String = when (status.problem) {
            HelperStatus.Problem.NEEDS_SECURE_SETTINGS -> "Setup isn't finished: the app can't switch on Wireless debugging yet."
            HelperStatus.Problem.NO_WIFI -> "Connect to Wi-Fi. The audio helper starts over Wireless debugging, which needs Wi-Fi."
            HelperStatus.Problem.WIRELESS_DEBUGGING_UNAVAILABLE -> "Wireless debugging didn't start. If the phone asks \"Allow wireless debugging on this network?\", choose Always allow."
            HelperStatus.Problem.KEY_NOT_AUTHORIZED -> "The app's debugging key isn't authorised on this phone. Open Voicemail setup to authorise it."
            HelperStatus.Problem.LAUNCH_FAILED -> status.detail.ifBlank { "The audio helper didn't start." }
        }
    }
}

val Context.app: VoicemailApp get() = applicationContext as VoicemailApp
