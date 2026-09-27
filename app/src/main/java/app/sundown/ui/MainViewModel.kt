package app.sundown.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sundown.Graph
import app.sundown.data.Apps
import app.sundown.data.InstalledApp
import app.sundown.data.LogEntry
import app.sundown.data.SeenScreen
import app.sundown.model.Rule
import app.sundown.model.RuleKind
import app.sundown.model.Target
import app.sundown.schedule.RuleEngine
import app.sundown.schedule.Runner
import app.sundown.schedule.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One view model for the whole activity. The editor and its two pickers share
 * [draft], which is why this is scoped to the activity and not to a screen.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val rules: StateFlow<List<Rule>> =
        Graph.db.rules().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val log: StateFlow<List<LogEntry>> =
        Graph.db.log().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val seen: StateFlow<List<SeenScreen>> =
        Graph.db.seen().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps

    fun loadApps() {
        if (_apps.value != null) return
        viewModelScope.launch { _apps.value = Apps.launchable(ctx) }
    }

    // — the editor draft —

    var draft by mutableStateOf<Rule?>(null)
        private set

    fun newDraft(kind: RuleKind) {
        draft = Rule(kind = kind, warnMinutes = if (kind == RuleKind.Schedule) 5 else 0, pauseAudio = kind == RuleKind.Timer)
    }

    fun editDraft(rule: Rule) { draft = rule }
    fun updateDraft(f: (Rule) -> Rule) { draft = draft?.let(f) }

    fun toggleTarget(t: Target) = updateDraft { r ->
        val exists = r.targets.any { it.packageName == t.packageName && it.activity == t.activity }
        r.copy(
            targets = if (exists) r.targets.filterNot { it.packageName == t.packageName && it.activity == t.activity }
            else r.targets + t,
        )
    }

    /** Save the draft; a timer saved from the editor is also started. */
    fun saveDraft(start: Boolean) {
        val d = draft ?: return
        val toSave = if (start && d.kind == RuleKind.Timer) RuleEngine.startTimer(d, System.currentTimeMillis()) else d
        viewModelScope.launch { Scheduler.save(ctx, toSave) }
    }

    fun deleteDraft() {
        val d = draft ?: return
        if (d.id != 0L) viewModelScope.launch { Scheduler.delete(ctx, d.id) }
    }

    // — list actions —

    fun setEnabled(rule: Rule, enabled: Boolean) = save(rule.copy(enabled = enabled))
    fun startTimer(rule: Rule, minutes: Int) = save(RuleEngine.startTimer(rule, System.currentTimeMillis(), minutes))
    fun cancelTimer(rule: Rule) = save(rule.copy(endsAt = null))
    fun extendTimer(rule: Rule, minutes: Int) =
        save(rule.endsAt?.let { RuleEngine.snooze(rule, it, minutes, System.currentTimeMillis()) } ?: rule)

    fun closeNow(rule: Rule) = Runner.closeNow(ctx, rule)

    fun clearLog() = viewModelScope.launch { Graph.db.log().clear() }

    private fun save(rule: Rule) {
        viewModelScope.launch { Scheduler.save(ctx, rule) }
    }
}
