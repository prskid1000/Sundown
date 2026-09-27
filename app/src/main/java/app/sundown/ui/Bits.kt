package app.sundown.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sundown.data.Apps
import app.sundown.model.Rule
import app.sundown.ui.theme.NIcons
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The app's icon, or a quiet placeholder while it loads or if it cannot be read. */
@Composable
fun AppIcon(pkg: String, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, pkg) { value = Apps.icon(context, pkg) }
    val shape = RoundedCornerShape(size * 0.28f)
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Box(modifier.size(size).background(NocturneColors.Neutral800, shape))
    }
}

/** Up to [max] overlapping icons, then a "+N". */
@Composable
fun IconStack(pkgs: List<String>, max: Int = 5, size: Dp = 22.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        pkgs.distinct().take(max).forEachIndexed { i, p ->
            AppIcon(p, size, Modifier.offset(x = (-6 * i).dp))
        }
        val extra = pkgs.distinct().size - max
        if (extra > 0) {
            Text("+$extra", style = NocturneType.MonoXs, color = NocturneColors.TextMuted, modifier = Modifier.offset(x = (-6 * max + 4).dp))
        }
    }
}

/** Wall-clock "now", ticking every [periodMs] while the screen is resumed. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, periodMs) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(periodMs - now % periodMs)
            }
        }
    }
    return now
}

/** Increments each time the screen resumes — for state read from Settings, which has no flow. */
@Composable
fun rememberResumeCount(): Int {
    var n by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { n++ }
    }
    return n
}

// — formatting —

private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun formatMinuteOfDay(m: Int): String =
    java.time.LocalTime.of(m / 60, m % 60).format(timeFmt)

fun formatInstantTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)

/** "Today", "Tomorrow", or the weekday. */
fun dayWord(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    }
}

/** "2 h 14 m", "8 m", "40 s". */
fun formatSpan(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "$h h ${m} m"
        m > 0 -> "$m m"
        else -> "$s s"
    }
}

/** "1:04:09" / "12:05" for a running timer. */
fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatMinutes(min: Int): String = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}

val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun daysLabel(mask: Int): String = when (mask and Rule.ALL_DAYS) {
    Rule.ALL_DAYS -> "Every day"
    Rule.WEEKDAYS -> "Weekdays"
    Rule.WEEKEND -> "Weekends"
    0 -> "No days"
    else -> (0..6).filter { mask and (1 shl it) != 0 }.joinToString(" ") { DAY_SHORT[it] }
}

/**
 * The day as a 24-hour strip with the closing time marked, and "now" as a
 * hairline — so a glance says how far away tonight's closing is.
 */
@Composable
fun DayStrip(minuteOfDay: Int, now: Long, active: Boolean, modifier: Modifier = Modifier) {
    val nowMin = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute }
    val fill = if (active) NocturneColors.Accent else NocturneColors.Neutral600
    val track = NocturneColors.Neutral900
    val tick = NocturneColors.Neutral700
    val nowColor = NocturneColors.Text.copy(alpha = 0.55f)
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val h = size.height
        val barH = 4.dp.toPx()
        val y = (h - barH) / 2
        drawRoundRect(track, Offset(0f, y), Size(size.width, barH), CornerRadius(barH / 2))
        // Six-hour ticks: midnight, 06, 12, 18.
        for (i in 1..3) {
            val x = size.width * i / 4f
            drawLine(tick, Offset(x, y - 2.dp.toPx()), Offset(x, y + barH + 2.dp.toPx()), 1f)
        }
        // From the closing time to the end of the day reads as "after sundown".
        val cx = size.width * minuteOfDay / 1440f
        drawRoundRect(fill.copy(alpha = 0.35f), Offset(cx, y), Size(size.width - cx, barH), CornerRadius(barH / 2))
        drawCircle(fill, radius = 4.5.dp.toPx(), center = Offset(cx, h / 2))
        val nx = size.width * nowMin / 1440f
        drawLine(nowColor, Offset(nx, 0f), Offset(nx, h), 1.5.dp.toPx())
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun StatusGlyph(ok: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .background(if (ok) NocturneColors.Accent900 else NocturneColors.Neutral800, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (ok) NIcons.Check else NIcons.Bang,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) NocturneColors.Accent300 else NocturneColors.Neutral300,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * A mono section rule with a small action on the same baseline. The shared
 * [app.sundown.ui.components.SectionKicker] trailing slot is sized for a tag,
 * and a full-height button pushed the rule below it.
 */
@Composable
fun SectionHeader(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = NocturneType.SectionKicker,
            color = NocturneColors.Neutral500,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            app.sundown.ui.components.NButton(
                action,
                onClick = onAction,
                style = app.sundown.ui.components.NButtonStyle.Ghost,
                leadingIcon = NIcons.Plus,
                minHeight = 32.dp,
            )
        }
    }
}
