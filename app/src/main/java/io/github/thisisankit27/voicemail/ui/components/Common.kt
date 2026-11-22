package io.github.thisisankit27.voicemail.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Stable, pleasant colour per caller, so the same person always gets the same avatar. */
private val avatarPalette = listOf(
    Color(0xFF0E6B5C), Color(0xFF436278), Color(0xFF8E4E2B), Color(0xFF6B4E9E),
    Color(0xFF2F6B2F), Color(0xFF9E3B5B), Color(0xFF3A5A9E), Color(0xFF7A6A1E),
)

fun avatarColor(key: String?): Color = avatarPalette[abs((key ?: "").hashCode()) % avatarPalette.size]

fun initials(name: String?): String? {
    if (name.isNullOrBlank() || name.first().let { it.isDigit() || it == '+' }) return null
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return (parts.first().take(1) + (if (parts.size > 1) parts.last().take(1) else "")).uppercase()
}

@Composable
fun rememberContactPhoto(uri: String?): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(it))) }.getOrNull()
            }
        }
    }
    return bitmap
}

@Composable
fun Avatar(name: String?, photoUri: String?, colorKey: String?, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val photo = rememberContactPhoto(photoUri)
    Box(
        modifier.size(size).clip(CircleShape).background(avatarColor(colorKey ?: name)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> Image(photo.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
            initials(name) != null -> Text(
                initials(name)!!, color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.38f).sp,
            )
            else -> Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
        }
    }
}

enum class StatusTone { GOOD, WARN, BAD, NEUTRAL }

@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val (bg, fg) = when (tone) {
        StatusTone.GOOD -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.WARN -> Color(0xFFFFE08A) to Color(0xFF3D2E00)
        StatusTone.BAD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            else Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

object Formats {
    private val time = DateTimeFormatter.ofPattern("h:mm a")
    private val dayMonth = DateTimeFormatter.ofPattern("EEE, d MMM")
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val longDay = DateTimeFormatter.ofPattern("d MMMM yyyy")

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun clock(epochMs: Long): String = time.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** "Just now", "12 min ago", "3:40 PM", "Yesterday", "Mon, 3 Nov", "3 Nov 2025". */
    fun relative(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - epochMs
        if (diff in 0..59_000) return "Just now"
        if (diff in 60_000 until 3_600_000) return "${diff / 60_000} min ago"
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            day == today -> clock(epochMs)
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> dayMonth.format(day)
            else -> full.format(day)
        }
    }

    /** Section header for a day: "Today", "Yesterday", "Monday", "3 November 2025". */
    fun dayHeader(epochMs: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            else -> longDay.format(day)
        }
    }
}
