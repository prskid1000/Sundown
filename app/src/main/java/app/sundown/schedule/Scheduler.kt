package app.sundown.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.sundown.Graph
import app.sundown.model.Rule
import java.time.ZoneId

/**
 * One alarm per rule, always for that rule's next event.
 *
 * Exact alarms are preferred because a closing time is a promise. When the
 * permission is refused we still set an inexact one rather than nothing — the
 * closing then happens late, which the Setup screen says, instead of never.
 */
object Scheduler {

    fun canExact(context: Context): Boolean = alarms(context).canScheduleExactAlarms()

    suspend fun rearmAll(context: Context) {
        Graph.db.rules().all().forEach { arm(context, it) }
    }

    /** Save a rule and arm its next event. Returns the saved rule, id filled in. */
    suspend fun save(context: Context, rule: Rule): Rule {
        val newId = Graph.db.rules().upsert(rule)
        // Upsert returns -1 when it updated an existing row.
        val saved = if (rule.id != 0L) rule else rule.copy(id = newId)
        arm(context, saved)
        return saved
    }

    suspend fun delete(context: Context, id: Long) {
        cancel(context, id)
        Notifier.cancelWarning(context, id)
        Graph.db.rules().delete(id)
    }

    fun arm(context: Context, rule: Rule) {
        val event = RuleEngine.nextEvent(rule, System.currentTimeMillis(), ZoneId.systemDefault())
        if (event == null) cancel(context, rule.id) else set(context, rule.id, event)
    }

    fun set(context: Context, ruleId: Long, event: RuleEngine.Event) {
        val pi = pending(context, ruleId, event)
        val am = alarms(context)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.at, pi)
        }
    }

    fun cancel(context: Context, ruleId: Long) {
        alarms(context).cancel(pending(context, ruleId, null))
    }

    private fun pending(context: Context, ruleId: Long, event: RuleEngine.Event?): PendingIntent {
        // The data URI makes each rule's intent distinct to filterEquals, so
        // setting one rule's alarm never replaces another's; extras do not
        // count for that comparison, which is why they can change freely.
        val intent = Intent(context, AlarmReceiver::class.java)
            .setData(Uri.parse("sundown://rule/$ruleId"))
        if (event != null) {
            intent.putExtra(AlarmReceiver.EXTRA_RULE, ruleId)
                .putExtra(AlarmReceiver.EXTRA_TYPE, event.type.name)
                .putExtra(AlarmReceiver.EXTRA_OCCURRENCE, event.occurrence)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)
}
