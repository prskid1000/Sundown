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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sundown.BuildConfig
import app.sundown.Graph
import app.sundown.closer.Closer
import app.sundown.closer.CloserService
import app.sundown.schedule.Scheduler
import app.sundown.ui.StatusGlyph
import app.sundown.ui.VSpace
import app.sundown.ui.rememberResumeCount
import app.sundown.ui.components.NButton
import app.sundown.ui.components.NButtonStyle
import app.sundown.ui.components.NCard
import app.sundown.ui.components.NHelp
import app.sundown.ui.components.NSwitch
import app.sundown.ui.components.PhoneScaffold
import app.sundown.ui.components.RootToolbar
import app.sundown.ui.components.SectionKicker
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import app.sundown.ui.theme.Space

@Composable
fun SetupScreen(bottomBar: @Composable () -> Unit) {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    var bump by remember { mutableIntStateOf(0) }
    val connected by CloserService.connected.collectAsStateWithLifecycle()
    val cover by Graph.prefs.cover.collectAsStateWithLifecycle()
    val resultNotice by Graph.prefs.resultNotice.collectAsStateWithLifecycle()

    // Read on every resume: all three are changed in Settings, which has no
    // callback to tell us.
    val a11y = remember(resumes, connected) { Closer.isEnabled(context) }
    val exact = remember(resumes) { Scheduler.canExact(context) }
    val notify = remember(resumes, bump) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bump++ }

    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    PhoneScaffold(
        toolbar = {
            RootToolbar(
                title = "Setup",
                subtitle = {
                    Text(
                        "${listOf(a11y, exact, notify).count { it }} of 3 ready",
                        style = NocturneType.MonoXs,
                        color = NocturneColors.TextMuted,
                    )
                },
            )
        },
        bottomBar = bottomBar,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            SectionKicker("PERMISSIONS")

            Check(
                ok = a11y && connected,
                title = "Accessibility service",
                status = when {
                    a11y && connected -> "On — Sundown can press Force stop"
                    a11y -> "On, but not running yet — turn it off and on again"
                    else -> "Off — nothing can be closed"
                },
                body = "Android gives apps no way to stop another app. Sundown opens its App info page and presses Force stop, " +
                    "exactly as you would, and presses Back to leave an activity you chose. Turn on \"Sundown closer\".",
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

            Check(
                ok = exact,
                title = "Alarms & reminders",
                status = if (exact) "Allowed — closings happen on the minute" else "Not allowed — closings may be several minutes late",
                body = "Android 14 and later turn this off by default. Without it, Android batches Sundown's alarms with others to save battery.",
            ) {
                if (!exact) {
                    NButton(
                        "Allow exact alarms",
                        onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) },
                        style = NButtonStyle.Primary,
                    )
                }
            }

            Check(
                ok = notify,
                title = "Notifications",
                status = if (notify) "Allowed" else "Off — no warnings before closing, and no word when something fails",
                body = "Used for the heads-up before a closing (with +10 min and Skip), and to tell you if a closing didn't work.",
            ) {
                if (!notify && Build.VERSION.SDK_INT >= 33) {
                    NButton("Allow notifications", onClick = { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }, style = NButtonStyle.Primary)
                }
            }

            VSpace(Space.s3)
            SectionKicker("BEHAVIOUR")
            Toggle(
                "Cover the screen while closing",
                "A dark sheet hides the Settings pages flicking past. Turn off to watch what Sundown does.",
                cover,
            ) { Graph.prefs.setCover(it) }
            Toggle(
                "Tell me after each closing",
                "A quiet notification listing what was closed. Problems are always reported.",
                resultNotice,
            ) { Graph.prefs.setResultNotice(it) }

            VSpace(Space.s3)
            SectionKicker("HOW IT WORKS")
            NHelp(
                "At closing time Sundown opens each app's App info page, presses Force stop and OK, then presses Back to return you where you were. " +
                    "That is a real force stop: music, video and background work all end. " +
                    "If the phone is locked, background processes are stopped at once and the rest happens the moment you unlock. " +
                    "If a button can't be found (an unusual Settings layout), Sundown falls back to stopping background processes and says so in the Log.",
            )
            Text("Sundown ${BuildConfig.VERSION_NAME}", style = NocturneType.MonoXs, color = NocturneColors.TextMeta, modifier = Modifier.padding(top = Space.s4))
            VSpace(Space.s6)
        }
    }
}

@Composable
private fun Check(
    ok: Boolean,
    title: String,
    status: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    NCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp), gap = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(ok)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = NocturneType.CardTitleLg)
                Text(status, style = NocturneType.MonoXs, color = if (ok) NocturneColors.Accent300 else NocturneColors.Neutral300)
            }
        }
        NHelp(body)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}

@Composable
private fun Toggle(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = NocturneType.CardTitle)
            NHelp(help)
        }
        Spacer(Modifier.width(12.dp))
        NSwitch(checked, onCheckedChange = onChange)
    }
}
