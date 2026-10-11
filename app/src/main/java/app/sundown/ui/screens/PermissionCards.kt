package app.sundown.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sundown.closer.Closer
import app.sundown.closer.CloserService
import app.sundown.closer.WardenBridge
import app.sundown.schedule.Scheduler
import app.sundown.ui.StatusGlyph
import app.sundown.ui.rememberResumeCount
import app.sundown.ui.components.NButton
import app.sundown.ui.components.NButtonStyle
import app.sundown.ui.components.NCard
import app.sundown.ui.components.NHelp
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One card per permission Sundown still needs, each with the button that fixes
 * it. A card disappears once its permission is granted; with all three granted
 * this draws nothing. The accessibility card also stays hidden while Warden is
 * reachable, since closing then goes through Warden instead.
 */
@Composable
fun PermissionCards() {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    var bump by remember { mutableIntStateOf(0) }
    val connected by CloserService.connected.collectAsStateWithLifecycle()

    // Read on every resume: all three are changed in Settings, which has no
    // callback to tell us.
    val a11y = remember(resumes, connected) { Closer.isEnabled(context) }
    val exact = remember(resumes) { Scheduler.canExact(context) }
    val notify = remember(resumes, bump) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bump++ }
    // With Warden reachable, closing goes through it and accessibility isn't
    // needed. Null until the first check returns, so the card doesn't flash.
    val warden by produceState<Boolean?>(null, resumes) {
        value = withContext(Dispatchers.IO) { WardenBridge.available(context) }
    }

    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    if (warden == false && !(a11y && connected)) {
        Card(
            title = "Turn on Sundown in Accessibility",
            status = if (a11y) "On, but not running yet — turn it off and on again" else "Off — nothing can be closed",
            body = "Android gives apps no way to stop another app. Sundown opens its App info page and presses Force stop, " +
                "exactly as you would, and presses Back to leave an activity you chose.",
        ) {
            NButton("Open accessibility settings", onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, style = NButtonStyle.Primary)
            if (!a11y) {
                NHelp(
                    "Switch greyed out, with \"Restricted setting\"? Android does that to apps installed outside the Play Store. " +
                        "Open Sundown's App info, tap ⋮ at the top right, choose \"Allow restricted settings\", then try again.",
                )
                NButton("Open Sundown's App info", onClick = { open(appInfo) })
            }
        }
    }

    if (!exact) {
        Card(
            title = "Allow alarms & reminders",
            status = "Not allowed — closings may be several minutes late",
            body = "Android 14 and later turn this off by default. Without it, Android batches Sundown's alarms with others to save battery.",
        ) {
            NButton(
                "Allow exact alarms",
                onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) },
                style = NButtonStyle.Primary,
            )
        }
    }

    if (!notify && Build.VERSION.SDK_INT >= 33) {
        Card(
            title = "Allow notifications",
            status = "Off — no warnings before closing, and no word when something fails",
            body = "Used for the heads-up before a closing (with +10 min and Skip), and to tell you if a closing didn't work.",
        ) {
            NButton("Allow notifications", onClick = { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }, style = NButtonStyle.Primary)
        }
    }
}

@Composable
private fun Card(title: String, status: String, body: String, actions: @Composable () -> Unit) {
    NCard(
        Modifier.fillMaxWidth(),
        ring = NocturneColors.Accent700,
        fill = NocturneColors.Accent900,
        padding = PaddingValues(14.dp),
        gap = 8.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = NocturneType.CardTitleLg)
                Text(status, style = NocturneType.MonoXs, color = NocturneColors.Neutral300)
            }
        }
        NHelp(body)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}
