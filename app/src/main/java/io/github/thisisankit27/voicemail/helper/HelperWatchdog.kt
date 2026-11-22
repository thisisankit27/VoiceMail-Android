package io.github.thisisankit27.voicemail.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.core.AppLog
import java.util.concurrent.TimeUnit

/**
 * Keeps the audio helper running without a PC:
 * - after a reboot (BOOT_COMPLETED), as soon as Wi-Fi is available;
 * - every 15 minutes while on Wi-Fi, in case it died;
 * - after the app is updated (the old helper exits when its APK disappears).
 * "Unmetered network" is WorkManager's closest constraint to "on Wi-Fi"; the launcher re-checks.
 */
object HelperWatchdog {
    private const val ONE_SHOT = "helper-start"
    private const val PERIODIC = "helper-watchdog"

    private val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HelperStartWorker>(15, TimeUnit.MINUTES).setConstraints(wifi).build(),
        )
    }

    /** Start as soon as Wi-Fi allows (replaces any pending one-shot). */
    fun startSoon(context: Context, reason: String) {
        AppLog.i("helper start requested: $reason")
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HelperStartWorker>()
                .setConstraints(wifi)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}

class HelperStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.settings.current().enabled) return Result.success()
        val status = app.helper.ensureRunning("watchdog")
        app.onHelperStatus(status)
        if (status is HelperStatus.Running) app.helper.refreshKeyIfStale()
        return if (status is HelperStatus.Running) Result.success() else {
            // Retry a few times (Wi-Fi may still be settling after boot); the periodic run covers the rest.
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

/** Re-arms the helper after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                HelperWatchdog.schedule(context)
                HelperWatchdog.startSoon(context, intent.action ?: "boot")
            }
        }
    }
}
