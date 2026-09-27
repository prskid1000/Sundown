package app.sundown.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sundown.Graph
import app.sundown.closer.Closer
import app.sundown.model.Rule
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    Graph.scope.launch {
        try { block() } finally { pending.finish() }
    }
}

/** A rule's alarm: either its warning or its closing. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_RULE, -1)
        val type = intent.getStringExtra(EXTRA_TYPE)?.let { RuleEngine.EventType.valueOf(it) } ?: return
        val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE, 0)
        async { Runner.onAlarm(context, id, type, occurrence) }
    }

    companion object {
        const val EXTRA_RULE = "rule"
        const val EXTRA_TYPE = "type"
        const val EXTRA_OCCURRENCE = "occurrence"
    }
}

/** The buttons on a warning notification. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_RULE, -1)
        val occurrence = intent.getLongExtra(AlarmReceiver.EXTRA_OCCURRENCE, 0)
        val action = intent.action ?: return
        async {
            val rule = Graph.db.rules().get(id) ?: return@async
            Notifier.cancelWarning(context, id)
            when (action) {
                ACTION_SNOOZE -> Scheduler.save(context, RuleEngine.snooze(rule, occurrence, SNOOZE_MINUTES, System.currentTimeMillis()))
                ACTION_SKIP -> Scheduler.save(context, RuleEngine.skip(rule, occurrence))
                ACTION_CLOSE_NOW -> Runner.fire(context, rule, occurrence)
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "app.sundown.SNOOZE"
        const val ACTION_SKIP = "app.sundown.SKIP"
        const val ACTION_CLOSE_NOW = "app.sundown.CLOSE_NOW"
        const val SNOOZE_MINUTES = 10
    }
}

class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        async { Scheduler.rearmAll(context) }
    }
}

/** What an alarm does once it has rung. */
object Runner {

    suspend fun onAlarm(context: Context, id: Long, type: RuleEngine.EventType, occurrence: Long) {
        val rule = Graph.db.rules().get(id) ?: return
        // The alarm carries the instant it was set for; the rule may have been
        // edited, disabled or snoozed since. Re-arm from what the rule says now.
        if (!RuleEngine.stillDue(rule, occurrence, ZoneId.systemDefault())) {
            Scheduler.arm(context, rule)
            return
        }
        when (type) {
            RuleEngine.EventType.Warn -> {
                Notifier.warn(context, rule, occurrence)
                // Set the closing explicitly rather than asking the engine: an
                // inexact alarm can ring a moment early, and "next event" would
                // then be this same warning again.
                Scheduler.set(context, rule.id, RuleEngine.Event(RuleEngine.EventType.Fire, occurrence, occurrence))
            }
            RuleEngine.EventType.Fire -> fire(context, rule, occurrence)
        }
    }

    /** Close a rule's targets for [occurrence], then arm its next one. */
    suspend fun fire(context: Context, rule: Rule, occurrence: Long) {
        Notifier.cancelWarning(context, rule.id)
        Scheduler.save(context, RuleEngine.afterFiring(rule, occurrence))
        Closer.submit(context, rule.name.ifBlank { defaultName(rule) }, rule.targets, rule.pauseAudio)
    }

    /** "Close now" from the app: the schedule is not touched. */
    fun closeNow(context: Context, rule: Rule) {
        Closer.submit(context, rule.name.ifBlank { defaultName(rule) }, rule.targets, rule.pauseAudio)
    }

    fun defaultName(rule: Rule): String = rule.targets.joinToString { it.screenName ?: it.label }.ifBlank { "Rule" }
}
