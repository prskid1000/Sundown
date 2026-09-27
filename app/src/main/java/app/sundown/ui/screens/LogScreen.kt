package app.sundown.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sundown.data.LogEntry
import app.sundown.data.Outcome
import app.sundown.ui.AppIcon
import app.sundown.ui.MainViewModel
import app.sundown.ui.dayWord
import app.sundown.ui.formatInstantTime
import app.sundown.ui.components.NCard
import app.sundown.ui.components.NHelp
import app.sundown.ui.components.NTag
import app.sundown.ui.components.NTagStyle
import app.sundown.ui.components.PhoneScaffold
import app.sundown.ui.components.RootToolbar
import app.sundown.ui.components.ToolbarAction
import app.sundown.ui.theme.NIcons
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import app.sundown.ui.theme.Space

@Composable
fun LogScreen(vm: MainViewModel, bottomBar: @Composable () -> Unit) {
    val log by vm.log.collectAsStateWithLifecycle()
    val runs = log.groupBy { it.runId }.values.toList()

    PhoneScaffold(
        toolbar = {
            RootToolbar(
                title = "Log",
                subtitle = { Text("${runs.size} runs", style = NocturneType.MonoXs, color = NocturneColors.TextMuted) },
                trailing = { if (log.isNotEmpty()) ToolbarAction(NIcons.Trash, "Clear log", onClick = { vm.clearLog() }) },
            )
        },
        bottomBar = bottomBar,
    ) {
        if (runs.isEmpty()) {
            NHelp("Every closing is recorded here — including the ones that only partly worked, and why.", modifier = Modifier.padding(top = Space.s4))
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            items(runs, key = { it.first().runId.toString() + it.first().id }) { run -> RunCard(run) }
        }
    }
}

@Composable
private fun RunCard(run: List<LogEntry>) {
    val first = run.first()
    NCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp), gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(first.ruleName, style = NocturneType.CardTitleLg, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${dayWord(first.at).lowercase()} ${formatInstantTime(first.at)}",
                style = NocturneType.MonoTimestamp,
                color = NocturneColors.TextMuted,
            )
        }
        run.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.packageName, 24.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        e.activity?.substringAfterLast('.')?.let { "${e.label} · $it" } ?: e.label,
                        style = NocturneType.Row,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (e.detail.isNotBlank()) {
                        Text(e.detail, style = NocturneType.Help, color = NocturneColors.TextMuted)
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutcomeTag(e.outcome)
            }
        }
    }
}

@Composable
private fun OutcomeTag(o: Outcome) {
    val (text, style) = when (o) {
        Outcome.ForceStopped -> "stopped" to NTagStyle.Accent
        Outcome.ScreenClosed -> "closed" to NTagStyle.Accent
        Outcome.NotRunning -> "not running" to NTagStyle.Neutral
        Outcome.ScreenNotOpen -> "not open" to NTagStyle.Neutral
        Outcome.Deferred -> "on unlock" to NTagStyle.Outline
        Outcome.BackgroundKilled -> "background only" to NTagStyle.Outline
        Outcome.Failed -> "failed" to NTagStyle.Outline
    }
    NTag(text, style = style)
}
