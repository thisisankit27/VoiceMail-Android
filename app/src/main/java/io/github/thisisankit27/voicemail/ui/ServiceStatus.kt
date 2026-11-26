package io.github.thisisankit27.voicemail.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thisisankit27.voicemail.app
import io.github.thisisankit27.voicemail.data.AppSettings
import io.github.thisisankit27.voicemail.helper.HelperStatus

/** The headline state of the answering service, as the dashboard pill shows it. */
enum class ServiceState { ACTIVE, PAUSED_HELPER, OFF, NEEDS_SETUP, CHECKING }

data class SetupSnapshot(
    val isDialer: Boolean,
    val contacts: Boolean,
    val phone: Boolean,
    val callLog: Boolean,
    val notifications: Boolean,
    val microphone: Boolean,
    val keyAuthorized: Boolean,
    val secureSettings: Boolean,
    val helper: HelperStatus,
    val settings: AppSettings?,
    val greetingReady: Boolean,
) {
    val permissionsOk get() = contacts && phone && callLog && notifications
    val state: ServiceState get() = when {
        settings == null -> ServiceState.CHECKING
        !isDialer -> ServiceState.NEEDS_SETUP
        !settings.enabled -> ServiceState.OFF
        helper is HelperStatus.Running -> ServiceState.ACTIVE
        helper is HelperStatus.Unknown || helper is HelperStatus.Starting -> ServiceState.CHECKING
        !keyAuthorized -> ServiceState.NEEDS_SETUP
        else -> ServiceState.PAUSED_HELPER
    }
}

fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

fun Context.isDefaultDialer() = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

/** Live setup snapshot; re-reads roles and permissions whenever the screen resumes. */
@Composable
fun rememberSetupSnapshot(): SetupSnapshot {
    val context = LocalContext.current
    val app = context.app
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val settings by app.settings.settings.collectAsState(initial = null)
    val helper by app.helper.status.collectAsState()
    LaunchedEffect(tick) { app.helper.check() }
    return remember(tick, settings, helper) { snapshot(context, settings, helper) }
}

private fun snapshot(context: Context, settings: AppSettings?, helper: HelperStatus): SetupSnapshot {
    val app = context.app
    return SetupSnapshot(
        isDialer = context.isDefaultDialer(),
        contacts = context.granted(Manifest.permission.READ_CONTACTS),
        phone = context.granted(Manifest.permission.READ_PHONE_STATE) && context.granted(Manifest.permission.CALL_PHONE),
        callLog = context.granted(Manifest.permission.READ_CALL_LOG),
        notifications = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled(),
        microphone = context.granted(Manifest.permission.RECORD_AUDIO),
        keyAuthorized = app.helper.keyAuthorized,
        secureSettings = app.helper.hasSecureSettings(),
        helper = helper,
        settings = settings,
        greetingReady = settings?.let { app.greetings.isRendered(it, io.github.thisisankit27.voicemail.greeting.GreetingKind.MESSAGE) } ?: false,
    )
}
