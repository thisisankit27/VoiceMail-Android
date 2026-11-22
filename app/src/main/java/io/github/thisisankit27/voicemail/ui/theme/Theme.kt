package io.github.thisisankit27.voicemail.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Brand fallback palette (deep teal + ink), used when dynamic color is off. */
private val Light = lightColorScheme(
    primary = Color(0xFF0E6B5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2DD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635D),
    secondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFF436278),
    tertiaryContainer = Color(0xFFC9E6FF),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
    surfaceContainer = Color(0xFFEBEFEC),
    surfaceContainerHigh = Color(0xFFE5E9E7),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AD5C1),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005144),
    onPrimaryContainer = Color(0xFFA6F2DD),
    secondary = Color(0xFFB1CCC4),
    secondaryContainer = Color(0xFF334B45),
    tertiary = Color(0xFFABCAE4),
    tertiaryContainer = Color(0xFF2B4A5F),
    background = Color(0xFF0F1513),
    surface = Color(0xFF0F1513),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    error = Color(0xFFFFB4AB),
)

/** Accept/decline colours stay fixed (green/red) regardless of the wallpaper palette. */
object CallColors {
    val accept = Color(0xFF1E8E3E)
    val decline = Color(0xFFD93025)
    val callBackground = Color(0xFF0B1F2A)
    val callBackgroundEnd = Color(0xFF12343F)
}

@Composable
fun VoicemailTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
