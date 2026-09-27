package app.sundown.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sundown.closer.Closer
import app.sundown.closer.CloserService
import app.sundown.model.Rule
import app.sundown.model.RuleKind
import app.sundown.schedule.RuleEngine
import app.sundown.schedule.Runner
import app.sundown.schedule.Scheduler
import app.sundown.ui.DayStrip
import app.sundown.ui.IconStack
import app.sundown.ui.MainViewModel
import app.sundown.ui.SectionHeader
import app.sundown.ui.VSpace
import app.sundown.ui.dayWord
import app.sundown.ui.daysLabel
import app.sundown.ui.formatClock
import app.sundown.ui.formatInstantTime
import app.sundown.ui.formatMinuteOfDay
import app.sundown.ui.formatMinutes
import app.sundown.ui.formatSpan
import app.sundown.ui.rememberNow
import app.sundown.ui.rememberResumeCount
import app.sundown.ui.components.NButton
import app.sundown.ui.components.NButtonStyle
import app.sundown.ui.components.NCard
import app.sundown.ui.components.NHelp
import app.sundown.ui.components.NProgressBar
import app.sundown.ui.components.NSwitch
import app.sundown.ui.components.NTag
import app.sundown.ui.components.NTagStyle
import app.sundown.ui.components.PhoneScaffold
import app.sundown.ui.components.RootToolbar
import app.sundown.ui.components.SectionKicker
import app.sundown.ui.components.ToolbarAction
import app.sundown.ui.components.nClickable
import app.sundown.ui.theme.NIcons
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import app.sundown.ui.theme.Space
import java.time.ZoneId

private val QUICK_MINUTES = listOf(15, 30, 45, 60, 90)

@Composable
fun TodayScreen(
    vm: MainViewModel,
    bottomBar: @Composable () -> Unit,
    onEdit: () -> Unit,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    val rules by vm.rules.collectAsStateWithLifecycle()
    val connected by CloserService.connected.collectAsStateWithLifecycle()
    val now = rememberNow()
    val resumes = rememberResumeCount()
    val setupOk = remember(resumes, connected) {
        Closer.isEnabled(context) && Scheduler.canExact(context)
    }

    val zone = ZoneId.systemDefault()
    val next = rules
        .mapNotNull { r -> RuleEngine.nextOccurrence(r, now, zone)?.let { r to it } }
        .minByOrNull { it.second }
    val timers = rules.filter { it.kind == RuleKind.Timer }
    val schedules = rules.filter { it.kind == RuleKind.Schedule }

    PhoneScaffold(
        toolbar = {
            RootToolbar(
                title = "Sundown",
                subtitle = {
                    Text(
                        if (connected) "closer on · ${rules.count { it.enabled }} active" else "closer off",
                        style = NocturneType.MonoXs,
                        color = if (connected) NocturneColors.TextMuted else NocturneColors.Accent400,
                    )
                },
                trailing = {
                    ToolbarAction(NIcons.Plus, "New schedule", onClick = { vm.newDraft(RuleKind.Schedule); onEdit() })
                },
            )
        },
        bottomBar = bottomBar,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            if (!setupOk) {
                NCard(
                    modifier = Modifier.fillMaxWidth().nClickable(onClick = onSetup),
                    ring = NocturneColors.Accent700,
                    fill = NocturneColors.Accent900,
                    padding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Text("Finish setup", style = NocturneType.CardTitleLg)
                    NHelp(
                        if (!Closer.isEnabled(context)) "Sundown can't close anything until its accessibility service is on."
                        else "Exact alarms are off, so closings may run a few minutes late.",
                    )
                }
            }

            Hero(next, now)

            SectionHeader("TIMERS", "New timer") { vm.newDraft(RuleKind.Timer); onEdit() }
            if (timers.isEmpty()) {
                NHelp("A timer closes apps once, after a set time — a sleep timer for YouTube or Spotify.")
            }
            timers.forEach { t ->
                TimerCard(t, now, vm, onEdit = { vm.editDraft(t); onEdit() })
            }

            VSpace(Space.s2)
            SectionHeader("SCHEDULES", "New schedule") { vm.newDraft(RuleKind.Schedule); onEdit() }
            if (schedules.isEmpty()) {
                NHelp("A schedule closes apps at the same time on the days you choose — every night at 23:30, say.")
            }
            schedules.forEach { r ->
                ScheduleCard(r, now, onToggle = { vm.setEnabled(r, it) }, onEdit = { vm.editDraft(r); onEdit() })
            }
            VSpace(Space.s6)
        }
    }
}

@Composable
private fun Hero(next: Pair<Rule, Long>?, now: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s4)) {
        SectionKicker("NEXT CLOSING")
        VSpace(6.dp)
        if (next == null) {
            Text("Nothing scheduled", style = NocturneType.H3, color = NocturneColors.TextMuted)
            return
        }
        val (rule, at) = next
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatInstantTime(at), style = NocturneType.H1, color = NocturneColors.Text)
            Spacer(Modifier.width(10.dp))
            Text(
                "${dayWord(at).lowercase()} · in ${formatSpan(at - now)}",
                style = NocturneType.MonoSm,
                color = NocturneColors.Accent400,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(
            rule.name.ifBlank { Runner.defaultName(rule) },
            style = NocturneType.Row,
            color = NocturneColors.TextLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimerCard(rule: Rule, now: Long, vm: MainViewModel, onEdit: () -> Unit) {
    val endsAt = rule.endsAt
    val running = endsAt != null && rule.enabled
    NCard(
        modifier = Modifier.fillMaxWidth(),
        ring = if (running) NocturneColors.Accent700 else null,
        padding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        gap = 10.dp,
    ) {
        Row(Modifier.fillMaxWidth().nClickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.name.ifBlank { Runner.defaultName(rule) }, style = NocturneType.CardTitleLg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (running) "closes at ${formatInstantTime(endsAt!!)}" else formatMinutes(rule.durationMinutes),
                    style = NocturneType.MonoXs,
                    color = NocturneColors.TextMuted,
                )
            }
            IconStack(rule.targets.map { it.packageName })
        }
        if (running) {
            val total = rule.durationMinutes * 60_000L
            val left = (endsAt!! - now).coerceAtLeast(0)
            Text(formatClock(left), style = NocturneType.H2, color = NocturneColors.Accent300)
            NProgressBar(fraction = if (total > 0) 1f - left.toFloat() / total else 1f, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                NButton("+10 min", onClick = { vm.extendTimer(rule, 10) })
                NButton("Cancel", onClick = { vm.cancelTimer(rule) }, style = NButtonStyle.Ghost)
                Spacer(Modifier.weight(1f))
                NButton("Close now", onClick = { vm.cancelTimer(rule); vm.closeNow(rule) }, style = NButtonStyle.Ghost)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                (listOf(rule.durationMinutes) + QUICK_MINUTES).distinct().sorted().take(6).forEach { m ->
                    NTag(
                        formatMinutes(m).replace(" min", "m").replace(" h", "h"),
                        style = if (m == rule.durationMinutes) NTagStyle.Accent else NTagStyle.Outline,
                        modifier = Modifier.nClickable { vm.startTimer(rule, m) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            NHelp("Tap a length to start.")
        }
    }
}

@Composable
private fun ScheduleCard(rule: Rule, now: Long, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    NCard(
        modifier = Modifier.fillMaxWidth().nClickable(onClick = onEdit),
        padding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        gap = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatMinuteOfDay(rule.minuteOfDay),
                    style = NocturneType.H3,
                    color = if (rule.enabled) NocturneColors.Text else NocturneColors.TextMuted,
                )
                Text(
                    listOf(rule.name.ifBlank { null }, daysLabel(rule.daysMask)).filterNotNull().joinToString(" · "),
                    style = NocturneType.Row,
                    color = NocturneColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NSwitch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        DayStrip(rule.minuteOfDay, now, rule.enabled)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconStack(rule.targets.map { it.packageName })
            Spacer(Modifier.weight(1f))
            val tags = buildList {
                if (rule.warnMinutes > 0) add("warns ${rule.warnMinutes}m")
                if (rule.targets.any { it.isScreen }) add("${rule.targets.count { it.isScreen }} activity")
                if (rule.snoozeFrom != null && rule.snoozeTo == null && rule.snoozeFrom > now) add("skipping next")
                if (rule.snoozeTo != null && rule.snoozeTo > now) add("moved to ${formatInstantTime(rule.snoozeTo)}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEach { NTag(it, style = NTagStyle.Neutral) }
            }
        }
    }
}
