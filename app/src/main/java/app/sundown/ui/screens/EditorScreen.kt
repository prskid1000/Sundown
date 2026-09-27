package app.sundown.ui.screens

import android.text.format.DateFormat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sundown.model.Rule
import app.sundown.model.RuleKind
import app.sundown.model.Target
import app.sundown.ui.AppIcon
import app.sundown.ui.DAY_LETTERS
import app.sundown.ui.MainViewModel
import app.sundown.ui.VSpace
import app.sundown.ui.daysLabel
import app.sundown.ui.formatMinuteOfDay
import app.sundown.ui.formatMinutes
import app.sundown.ui.components.NButton
import app.sundown.ui.components.NButtonStyle
import app.sundown.ui.components.NCard
import app.sundown.ui.components.NDialog
import app.sundown.ui.components.NDialogActions
import app.sundown.ui.components.NDialogBody
import app.sundown.ui.components.NDialogTitle
import app.sundown.ui.components.NField
import app.sundown.ui.components.NHelp
import app.sundown.ui.components.NIconButton
import app.sundown.ui.components.NInput
import app.sundown.ui.components.NNudgeSlider
import app.sundown.ui.components.NSeg
import app.sundown.ui.components.NSwitch
import app.sundown.ui.components.PhoneScaffold
import app.sundown.ui.components.PushToolbar
import app.sundown.ui.components.SectionKicker
import app.sundown.ui.components.ToolbarAction
import app.sundown.ui.components.nClickable
import app.sundown.ui.theme.NIcons
import app.sundown.ui.theme.NocturneColors
import app.sundown.ui.theme.NocturneType
import app.sundown.ui.theme.Space
import app.sundown.ui.theme.ring

private val WARN_OPTIONS = listOf(0, 1, 2, 5, 10)

@Composable
fun EditorScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onPickApps: () -> Unit,
    onPickScreens: () -> Unit,
) {
    val draft = vm.draft
    if (draft == null) {
        // Restored after process death with no draft: nothing to edit.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val isNew = draft.id == 0L
    val isTimer = draft.kind == RuleKind.Timer

    PhoneScaffold(
        toolbar = {
            PushToolbar(
                title = when {
                    isNew && isTimer -> "New timer"
                    isNew -> "New schedule"
                    isTimer -> "Edit timer"
                    else -> "Edit schedule"
                },
                onBack = onBack,
                trailing = if (!isNew) {
                    { ToolbarAction(NIcons.Trash, "Delete", onClick = { confirmDelete = true }) }
                } else null,
            )
        },
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            NField("Name") {
                NInput(
                    value = draft.name,
                    onValueChange = { v -> vm.updateDraft { it.copy(name = v) } },
                    placeholder = if (isTimer) "e.g. Sleep timer" else "e.g. Bedtime",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isNew) {
                NSeg(
                    options = listOf("At a time", "Timer"),
                    selectedIndex = if (isTimer) 1 else 0,
                    onSelect = { i -> vm.updateDraft { it.copy(kind = if (i == 1) RuleKind.Timer else RuleKind.Schedule) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isTimer) TimerFields(draft, vm) else ScheduleFields(draft, vm, onPickTime = { pickTime = true })

            SectionKicker("CLOSE")
            if (draft.targets.isEmpty()) {
                NHelp("Choose the apps to force-stop, or a single activity to back out of.")
            }
            draft.targets.forEach { t -> TargetRow(t, onRemove = { vm.toggleTarget(t) }) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                NButton("Apps", onClick = onPickApps, leadingIcon = NIcons.Plus)
                NButton("Activity", onClick = onPickScreens, leadingIcon = NIcons.Plus)
            }

            SectionKicker("BEFORE CLOSING")
            NField("Warn me") {
                NSeg(
                    options = WARN_OPTIONS.map { if (it == 0) "Off" else "${it}m" },
                    selectedIndex = WARN_OPTIONS.indexOf(draft.warnMinutes).coerceAtLeast(0),
                    onSelect = { i -> vm.updateDraft { it.copy(warnMinutes = WARN_OPTIONS[i]) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            NHelp("A notification with +10 min, Skip and Close now.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pause audio first", style = NocturneType.CardTitle)
                    NHelp("Stops whatever is playing cleanly before the app is stopped.")
                }
                NSwitch(draft.pauseAudio, onCheckedChange = { v -> vm.updateDraft { it.copy(pauseAudio = v) } })
            }
            VSpace(Space.s2)
        }

        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            val canSave = draft.targets.isNotEmpty() && (isTimer || draft.daysMask != 0)
            NButton(
                text = if (isTimer) "Save and start ${formatMinutes(draft.durationMinutes)}" else "Save",
                onClick = { vm.saveDraft(start = true); onBack() },
                style = NButtonStyle.Primary,
                block = true,
                enabled = canSave,
            )
            if (isTimer) {
                NButton("Save without starting", onClick = { vm.saveDraft(start = false); onBack() }, block = true, enabled = canSave, style = NButtonStyle.Ghost)
            }
            if (draft.targets.isNotEmpty()) {
                NButton("Close these now", onClick = { vm.closeNow(draft) }, block = true, style = NButtonStyle.Ghost, leadingIcon = NIcons.Power)
            }
        }
    }

    if (pickTime) {
        TimeDialog(draft.minuteOfDay, onDismiss = { pickTime = false }) { m ->
            vm.updateDraft { it.copy(minuteOfDay = m) }
            pickTime = false
        }
    }

    if (confirmDelete) {
        NDialog(onDismissRequest = { confirmDelete = false }) {
            NDialogTitle("Delete this ${if (isTimer) "timer" else "schedule"}?")
            NDialogBody("Its alarm is cancelled. The log keeps what it closed before.")
            NDialogActions {
                NButton("Cancel", onClick = { confirmDelete = false }, style = NButtonStyle.Ghost)
                NButton("Delete", onClick = { vm.deleteDraft(); confirmDelete = false; onBack() }, style = NButtonStyle.Primary)
            }
        }
    }
}

@Composable
private fun ScheduleFields(draft: Rule, vm: MainViewModel, onPickTime: () -> Unit) {
    NField("Close at") {
        Box(
            Modifier
                .fillMaxWidth()
                .ring(NocturneColors.Divider)
                .nClickable(onClick = onPickTime)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(NIcons.Clock, null, tint = NocturneColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(formatMinuteOfDay(draft.minuteOfDay), style = NocturneType.H2)
            }
        }
    }
    NField("On") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DAY_LETTERS.forEachIndexed { i, letter ->
                    val on = draft.daysMask and (1 shl i) != 0
                    Box(
                        Modifier
                            .size(38.dp)
                            .background(if (on) NocturneColors.Accent800 else NocturneColors.Neutral900, CircleShape)
                            .ring(if (on) NocturneColors.Accent500 else NocturneColors.Divider, CircleShape)
                            .nClickable { vm.updateDraft { it.copy(daysMask = it.daysMask xor (1 shl i)) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letter, style = NocturneType.Control, color = if (on) NocturneColors.Accent100 else NocturneColors.TextMuted)
                    }
                }
            }
            NHelp(daysLabel(draft.daysMask))
        }
    }
}

@Composable
private fun TimerFields(draft: Rule, vm: MainViewModel) {
    NField("Close after") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatMinutes(draft.durationMinutes), style = NocturneType.H2)
            NNudgeSlider(
                value = draft.durationMinutes.toFloat(),
                onValueChange = { v -> vm.updateDraft { it.copy(durationMinutes = v.toInt().coerceAtLeast(1)) } },
                // Whole minutes from 1: a one-minute timer is how you check a
                // new rule works without waiting half an hour.
                valueRange = 1f..240f,
                step = if (draft.durationMinutes < 10) 1f else 5f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TargetRow(t: Target, onRemove: () -> Unit) {
    NCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(t.packageName, 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.label, style = NocturneType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (t.isScreen) "activity · ${t.screenName}" else "whole app · force stop",
                    style = NocturneType.MonoXs,
                    color = if (t.isScreen) NocturneColors.Accent400 else NocturneColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NIconButton(NIcons.Close, "Remove", onClick = onRemove, style = NButtonStyle.Ghost, size = 32.dp, iconSize = 12.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial / 60, initial % 60, is24Hour = DateFormat.is24HourFormat(context))
    NDialog(onDismissRequest = onDismiss) {
        NDialogTitle("Close at")
        TimePicker(
            state = state,
            modifier = Modifier.padding(top = 8.dp),
            colors = TimePickerDefaults.colors(
                clockDialColor = NocturneColors.Neutral900,
                clockDialSelectedContentColor = NocturneColors.Accent100,
                clockDialUnselectedContentColor = NocturneColors.Text,
                selectorColor = NocturneColors.Accent,
                containerColor = NocturneColors.Surface,
                periodSelectorBorderColor = NocturneColors.Neutral700,
                periodSelectorSelectedContainerColor = NocturneColors.Accent800,
                periodSelectorUnselectedContainerColor = NocturneColors.Neutral900,
                periodSelectorSelectedContentColor = NocturneColors.Accent100,
                periodSelectorUnselectedContentColor = NocturneColors.TextMuted,
                timeSelectorSelectedContainerColor = NocturneColors.Accent800,
                timeSelectorUnselectedContainerColor = NocturneColors.Neutral900,
                timeSelectorSelectedContentColor = NocturneColors.Accent100,
                timeSelectorUnselectedContentColor = NocturneColors.Text,
            ),
        )
        NDialogActions {
            NButton("Cancel", onClick = onDismiss, style = NButtonStyle.Ghost)
            NButton("Set", onClick = { onPick(state.hour * 60 + state.minute) }, style = NButtonStyle.Primary)
        }
    }
}
