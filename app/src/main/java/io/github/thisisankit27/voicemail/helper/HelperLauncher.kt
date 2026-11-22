package io.github.thisisankit27.voicemail.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import io.github.thisisankit27.voicemail.core.AppLog
import io.github.thisisankit27.voicemail.helper.adb.AdbConnection
import io.github.thisisankit27.voicemail.helper.adb.AdbKey
import io.github.thisisankit27.voicemail.helper.adb.AdbPortFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/** Whether the shell-identity audio helper is available for the next call. */
sealed interface HelperStatus {
    data object Unknown : HelperStatus
    data class Starting(val step: String) : HelperStatus
    data class Running(val info: HelperInfo) : HelperStatus
    data class Stopped(val problem: Problem, val detail: String = "") : HelperStatus

    enum class Problem {
        /** Wireless debugging is off and the app may not switch it on (no WRITE_SECURE_SETTINGS). */
        NEEDS_SECURE_SETTINGS,
        /** Wireless debugging needs a Wi-Fi connection. */
        NO_WIFI,
        /** Wireless debugging was switched on but no port appeared (network not trusted, or off). */
        WIRELESS_DEBUGGING_UNAVAILABLE,
        /** adbd refused the app's key: the one-time authorisation is missing or was revoked. */
        KEY_NOT_AUTHORIZED,
        /** The helper was launched but never answered. */
        LAUNCH_FAILED,
    }
}

/**
 * Starts the helper on this phone without a PC, by being its own ADB client:
 * 1. If a helper already answers (same version, same APK), nothing to do.
 * 2. Otherwise switch Wireless debugging on (needs WRITE_SECURE_SETTINGS and Wi-Fi), find its port
 *    over mDNS, connect with the app's authorised key, and run the launch command as the shell user.
 * 3. Switch Wireless debugging off again. The helper runs in its own session (setsid), so it keeps
 *    running when the ADB connection, Wireless debugging or Wi-Fi goes away.
 */
class HelperLauncher(private val context: Context) {
    private val prefs = context.getSharedPreferences("helper", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _status = MutableStateFlow<HelperStatus>(HelperStatus.Unknown)
    val status: StateFlow<HelperStatus> = _status.asStateFlow()

    val client = HelperClient { token }

    val key: AdbKey by lazy { AdbKey.loadOrCreate(File(context.noBackupFilesDir, "adb")) }

    /** Shared secret the helper requires on every connection. Created once, kept in no-backup prefs. */
    val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: synchronized(this) {
            prefs.getString(KEY_TOKEN, null) ?: ByteArray(24).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
                .also { prefs.edit().putString(KEY_TOKEN, it).commit() }
        }

    /** True once adbd has accepted the app's key at least once. */
    var keyAuthorized: Boolean
        get() = prefs.getBoolean(KEY_AUTHORIZED, false)
        private set(value) = prefs.edit().putBoolean(KEY_AUTHORIZED, value).apply()

    /** Switch Wireless debugging back off once the helper runs (smaller attack surface, less battery). */
    var turnOffWirelessDebugging: Boolean
        get() = prefs.getBoolean(KEY_TURN_OFF, true)
        set(value) = prefs.edit().putBoolean(KEY_TURN_OFF, value).apply()

    val lastStartedAt: Long get() = prefs.getLong(KEY_LAST_START, 0)

    /** Last successful ADB connection. adbd forgets "always allow" keys unused for 7 days. */
    val lastAdbAt: Long get() = prefs.getLong(KEY_LAST_ADB, 0)

    fun hasSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun wirelessDebuggingOn(): Boolean = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    private fun apkPath(): String = context.applicationInfo.sourceDir

    /** Pings the helper and updates [status]. Does not try to start it. */
    suspend fun check(): HelperStatus = withContext(Dispatchers.IO) {
        val info = client.ping()
        val s = if (info != null && isCurrent(info)) HelperStatus.Running(info) else {
            (_status.value as? HelperStatus.Stopped) ?: HelperStatus.Stopped(HelperStatus.Problem.LAUNCH_FAILED, "Helper is not running")
        }
        _status.value = s
        s
    }

    private fun isCurrent(info: HelperInfo) = info.version == HelperProtocol.VERSION && info.apkPath == apkPath()

    /**
     * Makes sure the helper runs, starting it if needed. [onKeyPrompt] is called if the phone shows
     * the one-time "Allow debugging?" prompt (only on the legacy `adb tcpip` transport during setup).
     */
    suspend fun ensureRunning(trigger: String, onKeyPrompt: () -> Unit = {}): HelperStatus = mutex.withLock {
        withContext(Dispatchers.IO) {
            val result = start(trigger, onKeyPrompt)
            _status.value = result
            if (result is HelperStatus.Stopped) AppLog.w("helper not started ($trigger): ${result.problem} ${result.detail}")
            result
        }
    }

    private suspend fun start(trigger: String, onKeyPrompt: () -> Unit): HelperStatus {
        client.ping()?.let { info ->
            if (isCurrent(info)) return HelperStatus.Running(info)
            AppLog.i("helper is stale (v${info.version}, other APK); restarting it")
            client.shutdown()
            delay(300)
        }
        AppLog.i("starting helper ($trigger)")

        // Setup path: `adb tcpip 5555` from a PC exposes the legacy transport, where an unknown key
        // triggers the on-phone "Allow debugging?" prompt.
        if (AdbPortFinder.isOpen(AdbPortFinder.LEGACY_PORT)) {
            _status.value = HelperStatus.Starting("Connecting to ADB (port ${AdbPortFinder.LEGACY_PORT})")
            launchVia(AdbPortFinder.LEGACY_PORT, onKeyPrompt)?.let { return it }
        }

        var switchedOn = false
        if (!wirelessDebuggingOn()) {
            if (!hasSecureSettings()) return HelperStatus.Stopped(HelperStatus.Problem.NEEDS_SECURE_SETTINGS)
            if (!onWifi()) return HelperStatus.Stopped(HelperStatus.Problem.NO_WIFI)
            _status.value = HelperStatus.Starting("Switching on Wireless debugging")
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
            switchedOn = true
        }
        try {
            _status.value = HelperStatus.Starting("Finding Wireless debugging port")
            var port = AdbPortFinder.findTlsPort(context, 15_000)
            if (port == null && switchedOn) {
                // Seen once right after an app update: adbd never announced the port. Restarting
                // Wireless debugging makes it announce again.
                AppLog.i("no Wireless debugging port yet; restarting Wireless debugging once")
                Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 0)
                delay(1_000)
                Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
                port = AdbPortFinder.findTlsPort(context, 15_000)
            }
            port ?: return HelperStatus.Stopped(
                    if (onWifi()) HelperStatus.Problem.WIRELESS_DEBUGGING_UNAVAILABLE else HelperStatus.Problem.NO_WIFI,
                    "No Wireless debugging port appeared. If the phone asked \"Allow wireless debugging on this network?\", choose Always allow.",
                )
            _status.value = HelperStatus.Starting("Connecting to ADB")
            return launchVia(port!!, onKeyPrompt) ?: HelperStatus.Stopped(HelperStatus.Problem.LAUNCH_FAILED)
        } finally {
            if (switchedOn && turnOffWirelessDebugging && hasSecureSettings()) {
                Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 0)
                AppLog.i("Wireless debugging switched back off")
            }
        }
    }

    /** Returns Running, a Stopped describing why, or null if the port didn't speak ADB. */
    private suspend fun launchVia(port: Int, onKeyPrompt: () -> Unit): HelperStatus? {
        try {
            AdbConnection.connect("127.0.0.1", port, key, onKeyPrompt = onKeyPrompt).use { adb ->
                keyAuthorized = true
                prefs.edit().putLong(KEY_LAST_ADB, System.currentTimeMillis()).apply()
                AppLog.i("ADB connected on port $port (tls=${adb.tls})")
                _status.value = HelperStatus.Starting("Launching helper")
                val out = adb.shell(launchCommand())
                AppLog.i("launch output: ${out.trim().take(300)}")
            }
        } catch (e: AdbConnection.NotAuthorizedException) {
            keyAuthorized = false
            return HelperStatus.Stopped(HelperStatus.Problem.KEY_NOT_AUTHORIZED, e.message ?: "")
        } catch (e: java.io.IOException) {
            AppLog.w("ADB on port $port failed", e)
            return null
        }
        repeat(20) {
            delay(250)
            client.ping()?.let { info ->
                if (isCurrent(info)) {
                    prefs.edit().putLong(KEY_LAST_START, System.currentTimeMillis()).apply()
                    AppLog.i("helper running uid=${info.uid}")
                    return HelperStatus.Running(info)
                }
            }
        }
        return HelperStatus.Stopped(HelperStatus.Problem.LAUNCH_FAILED, "Launched, but the helper did not answer")
    }

    /**
     * Shell command run as uid 2000. It also (re)grants WRITE_SECURE_SETTINGS, which the app needs
     * to switch Wireless debugging on by itself after the next reboot.
     */
    fun launchCommand(): String {
        val pkg = context.packageName
        val main = HelperMain::class.java.name
        return "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>&1; " +
            "pkill ${HelperProtocol.PROCESS_NAME} 2>/dev/null; sleep 0.3; " +
            "${HelperProtocol.TOKEN_ENV}=$token CLASSPATH='${apkPath()}' " +
            "setsid app_process /system/bin --nice-name=${HelperProtocol.PROCESS_NAME} $main " +
            "</dev/null >/dev/null 2>&1 & sleep 0.2; echo launched"
    }

    /**
     * adbd revokes "always allow" keys that haven't connected for `adb_allowed_connection_time`
     * (7 days by default). While the helper keeps running there is no reason to connect, so the
     * watchdog reconnects every few days to keep the key valid for the next reboot.
     */
    suspend fun refreshKeyIfStale(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!keyAuthorized || System.currentTimeMillis() - lastAdbAt < KEY_REFRESH_MS) return@withContext false
            if (!hasSecureSettings() || !onWifi()) return@withContext false
            val wasOn = wirelessDebuggingOn()
            if (!wasOn) Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
            try {
                val port = AdbPortFinder.findTlsPort(context, 20_000) ?: return@withContext false
                AdbConnection.connect("127.0.0.1", port, key).use { it.shell("echo ok") }
                prefs.edit().putLong(KEY_LAST_ADB, System.currentTimeMillis()).apply()
                AppLog.i("ADB key refreshed")
                true
            } catch (e: Exception) {
                AppLog.w("ADB key refresh failed", e)
                false
            } finally {
                if (!wasOn) Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 0)
            }
        }
    }

    /** Asks a running helper to stop (used by "Stop helper" in settings). */
    suspend fun stop() = withContext(Dispatchers.IO) {
        client.shutdown()
        _status.value = HelperStatus.Stopped(HelperStatus.Problem.LAUNCH_FAILED, "Stopped by you")
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    companion object {
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTHORIZED = "key_authorized"
        private const val KEY_TURN_OFF = "turn_off_wireless_debugging"
        private const val KEY_LAST_START = "last_start"
        private const val KEY_LAST_ADB = "last_adb"
        private const val KEY_REFRESH_MS = 5L * 24 * 60 * 60 * 1000
    }
}
