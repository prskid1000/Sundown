package app.sundown.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sundown.model.RuleKind
import app.sundown.ui.MainViewModel
import app.sundown.ui.Routes
import app.sundown.ui.components.PhoneScaffold
import app.sundown.ui.components.RootToolbar
import app.sundown.ui.components.ToolbarAction
import app.sundown.ui.components.nClickable
import app.sundown.ui.theme.NIcons
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import app.sundown.ui.theme.Radius
import app.sundown.ui.theme.Space

/**
 * The whole app on one page, like Warden: a toolbar whose action follows the
 * tab and in-page Schedule / Log tabs. Missing permissions show as cards on
 * Schedule and disappear once granted; there is no settings page. Only the
 * editor and pickers are pushed screens.
 *
 * [requestedTab] (a [Routes] tab id, e.g. from a notification) switches tabs.
 */
@Composable
fun HomeScreen(vm: MainViewModel, requestedTab: String?, onTabConsumed: () -> Unit, onEdit: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Routes.SCHEDULE) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onTabConsumed()
        }
    }

    val rules by vm.rules.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()

    PhoneScaffold(
        toolbar = {
            RootToolbar(
                title = "Sundown",
                trailing = {
                    when (tab) {
                        Routes.SCHEDULE -> ToolbarAction(NIcons.Plus, "New schedule", onClick = { vm.newDraft(RuleKind.Schedule); onEdit() })
                        Routes.LOG -> if (log.isNotEmpty()) ToolbarAction(NIcons.Trash, "Clear log", onClick = { vm.clearLog() })
                    }
                },
            )
        },
    ) {
        SegTabs(
            tab,
            listOf(
                Triple(Routes.SCHEDULE, "Schedule", "${rules.count { it.enabled }}"),
                Triple(Routes.LOG, "Log", "${log.map { it.runId }.distinct().size}"),
            ),
        ) { tab = it }

        val page = Modifier.weight(1f)
        when (tab) {
            Routes.LOG -> LogPage(vm, page)
            else -> SchedulePage(vm, onEdit, page)
        }
    }
}

/** Warden's segmented tabs: label + count, the selected one tinted. */
@Composable
private fun SegTabs(selected: String, tabs: List<Triple<String, String, String>>, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.s4).clip(Radius.Md).border(1.dp, NocturneColors.Divider, Radius.Md),
    ) {
        tabs.forEachIndexed { i, (id, label, count) ->
            if (i > 0) Box(Modifier.width(1.dp).height(46.dp).background(NocturneColors.Divider))
            val on = id == selected
            Row(
                Modifier.weight(1f)
                    .nClickable { onSelect(id) }
                    .then(if (on) Modifier.background(NocturneColors.Accent.copy(alpha = 0.10f)) else Modifier)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = NocturneType.CardTitle, color = if (on) NocturneColors.Accent400 else NocturneColors.TextLabel)
                Spacer(Modifier.width(6.dp))
                Text(count, style = NocturneType.MonoXs, color = if (on) NocturneColors.Accent400 else NocturneColors.TextMuted)
            }
        }
    }
}
