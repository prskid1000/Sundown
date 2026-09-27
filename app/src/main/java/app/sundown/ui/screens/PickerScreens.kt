package app.sundown.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sundown.closer.CloserService
import app.sundown.data.Apps
import app.sundown.model.Target
import app.sundown.ui.AppIcon
import app.sundown.ui.MainViewModel
import app.sundown.ui.components.NButton
import app.sundown.ui.components.NButtonStyle
import app.sundown.ui.components.NHelp
import app.sundown.ui.components.NInput
import app.sundown.ui.components.PhoneScaffold
import app.sundown.ui.components.PushToolbar
import app.sundown.ui.components.SectionKicker
import app.sundown.ui.components.nClickable
import app.sundown.ui.theme.NIcons
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import app.sundown.ui.theme.Space
import app.sundown.ui.theme.ruleBelow
import app.sundown.ui.formatInstantTime
import app.sundown.ui.dayWord

@Composable
fun AppPickerScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadApps() }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val draft = vm.draft
    var query by remember { mutableStateOf("") }
    val chosen = draft?.targets?.filter { !it.isScreen }?.map { it.packageName }?.toSet().orEmpty()

    PhoneScaffold(
        toolbar = {
            PushToolbar("Choose apps", onBack = onBack, subtitle = "${chosen.size} chosen")
        },
    ) {
        NInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search apps",
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.s3),
            trailing = { Icon(NIcons.Search, null, tint = NocturneColors.TextMuted, modifier = Modifier.size(16.dp)) },
        )
        val list = apps
        if (list == null) {
            NHelp("Loading apps…")
        } else {
            val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            // Chosen apps float to the top so they are easy to find and undo.
            val ordered = shown.sortedByDescending { it.packageName in chosen }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(ordered, key = { it.packageName }) { app ->
                    PickRow(
                        pkg = app.packageName,
                        title = app.label,
                        subtitle = app.packageName,
                        selected = app.packageName in chosen,
                        onClick = { vm.toggleTarget(Target(app.packageName, app.label)) },
                    )
                }
            }
        }
        NButton("Done", onClick = onBack, style = NButtonStyle.Primary, block = true, modifier = Modifier.navigationBarsPadding().padding(top = Space.s3))
    }
}

@Composable
fun ScreenPickerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val seen by vm.seen.collectAsStateWithLifecycle()
    val connected by CloserService.connected.collectAsStateWithLifecycle()
    val chosen = vm.draft?.targets?.filter { it.isScreen }?.map { it.packageName to it.activity }?.toSet().orEmpty()
    val launchers = remember { launcherPackages(context) }
    // Home screens and Settings are screens, but closing them is never what
    // anyone means.
    var query by remember { mutableStateOf("") }
    val shown = seen
        .filter { it.packageName !in launchers && it.packageName != "com.android.settings" }
        .filter { s ->
            // App name, screen name or full class: whichever the person remembers.
            query.isBlank() ||
                Apps.label(context, s.packageName).contains(query, true) ||
                s.activity.contains(query, true)
        }
    // Chosen screens first, then apps in name order, so a long list stays navigable.
    val grouped = shown
        .sortedWith(compareByDescending<app.sundown.data.SeenScreen> { (it.packageName to it.activity) in chosen }.thenBy { Apps.label(context, it.packageName).lowercase() })
        .groupBy { it.packageName }

    PhoneScaffold(
        toolbar = { PushToolbar("Choose a screen", onBack = onBack, subtitle = "${chosen.size} chosen") },
    ) {
        NHelp(
            if (connected) "Screens you have opened recently. Open the one you want in its app, come back, and it will be here."
            else "Turn on Sundown's accessibility service first — that is how it learns which screens exist.",
            modifier = Modifier.padding(top = Space.s3, bottom = Space.s2),
        )
        NInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search apps or screens",
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.s3),
            trailing = { Icon(NIcons.Search, null, tint = NocturneColors.TextMuted, modifier = Modifier.size(16.dp)) },
        )
        if (query.isNotBlank() && shown.isEmpty()) {
            NHelp("No screen matches \"$query\".")
        }
        NHelp(
            "A screen is closed with Back only if it is on top at closing time. Many apps draw Shorts, Reels and the like inside one screen; those can't be told apart, so choose the whole app instead.",
            modifier = Modifier.padding(bottom = Space.s3),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            grouped.forEach { (pkg, screens) ->
                item(key = "h:$pkg") {
                    SectionKicker(Apps.label(context, pkg).uppercase(), modifier = Modifier.padding(top = Space.s4, bottom = 4.dp))
                }
                items(screens, key = { "${it.packageName}/${it.activity}" }) { s ->
                    PickRow(
                        pkg = s.packageName,
                        title = s.activity.substringAfterLast('.'),
                        subtitle = "seen ${dayWord(s.lastSeen).lowercase()} ${formatInstantTime(s.lastSeen)} · ${s.activity}",
                        selected = (s.packageName to s.activity) in chosen,
                        onClick = { vm.toggleTarget(Target(s.packageName, Apps.label(context, s.packageName), s.activity)) },
                    )
                }
            }
        }
        NButton("Done", onClick = onBack, style = NButtonStyle.Primary, block = true, modifier = Modifier.navigationBarsPadding().padding(top = Space.s3))
    }
}

private fun launcherPackages(context: android.content.Context): Set<String> {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
    return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
}

@Composable
private fun PickRow(pkg: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .nClickable(onClick = onClick)
            .ruleBelow()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = NocturneType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = NocturneType.MonoXs, color = NocturneColors.TextMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(22.dp)
                .background(if (selected) NocturneColors.Accent else NocturneColors.Neutral900, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(NIcons.Check, "Chosen", tint = NocturneColors.Accent100, modifier = Modifier.size(12.dp))
        }
    }
}
