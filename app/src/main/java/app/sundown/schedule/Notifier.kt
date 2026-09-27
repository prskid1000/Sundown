package app.sundown.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.sundown.Graph
import app.sundown.MainActivity
import app.sundown.R
import app.sundown.data.LogEntry
import app.sundown.data.Outcome
import app.sundown.model.Rule
import java.text.DateFormat
import java.util.Date

object Notifier {
    private const val CH_WARN = "warn"
    private const val CH_RESULT = "result"
    private const val CH_PROBLEM = "problem"

    private const val ID_RESULT = 1
    private const val ID_PROBLEM = 2
    /** Warnings are keyed by rule so each can be replaced or withdrawn on its own. */
    private fun warnId(ruleId: Long) = 1000 + ruleId.toInt()

    fun createChannels(context: Context) {
        nm(context).createNotificationChannels(
            listOf(
                NotificationChannel(CH_WARN, "Closing soon", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A heads-up before apps are closed, with +10 min and Skip."
                },
                NotificationChannel(CH_RESULT, "What was closed", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A quiet summary after each run."
                },
                NotificationChannel(CH_PROBLEM, "Couldn't close", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a closing could not be done, and what would fix it."
                },
            ),
        )
    }

    fun warn(context: Context, rule: Rule, occurrence: Long) {
        val names = rule.targets.joinToString { it.screenName ?: it.label }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(occurrence))
        val n = Notification.Builder(context, CH_WARN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Closing at $time")
            .setContentText(names)
            .setStyle(Notification.BigTextStyle().bigText(names))
            .setWhen(occurrence)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(occurrence - System.currentTimeMillis() + 60_000)
            .setContentIntent(openApp(context, null))
            .addAction(action(context, "+${ActionReceiver.SNOOZE_MINUTES} min", ActionReceiver.ACTION_SNOOZE, rule.id, occurrence))
            .addAction(action(context, "Skip this time", ActionReceiver.ACTION_SKIP, rule.id, occurrence))
            .addAction(action(context, "Close now", ActionReceiver.ACTION_CLOSE_NOW, rule.id, occurrence))
            .build()
        runCatching { nm(context).notify(warnId(rule.id), n) }
    }

    fun cancelWarning(context: Context, ruleId: Long) = nm(context).cancel(warnId(ruleId))

    /** After a run. Anything that did not fully work is said, not folded into "done". */
    fun result(context: Context, runName: String, entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        val bad = entries.filter { !it.outcome.succeeded && it.outcome != Outcome.Deferred }
        val deferred = entries.filter { it.outcome == Outcome.Deferred }
        if (bad.isEmpty() && !Graph.prefs.resultNotice.value && deferred.isEmpty()) return

        val closed = entries.filter { it.outcome == Outcome.ForceStopped || it.outcome == Outcome.ScreenClosed }
        val title = when {
            deferred.isNotEmpty() -> "Will finish closing when you unlock"
            bad.isNotEmpty() -> "Closed ${closed.size} of ${entries.size}"
            closed.isEmpty() -> "Nothing was open"
            else -> "Closed ${closed.joinToString { it.activity?.substringAfterLast('.') ?: it.label }}"
        }
        val body = entries.joinToString("\n") { "${it.label}: ${describe(it)}" }
        val n = Notification.Builder(context, if (bad.isEmpty()) CH_RESULT else CH_PROBLEM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(runName)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, "log"))
            .setAutoCancel(true)
            .build()
        runCatching { nm(context).notify(ID_RESULT, n) }
    }

    fun accessibilityOff(context: Context, names: String) {
        val fix = PendingIntent.getActivity(
            context, 7,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CH_PROBLEM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Couldn't fully close $names")
            .setContentText("Sundown's accessibility service is off. Only background processes were stopped.")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Sundown's accessibility service is off, so it could not press Force stop. " +
                        "Only background processes were stopped, and an app on screen or playing audio is still running. " +
                        "Turn on Sundown closer in Accessibility settings.",
                ),
            )
            .setContentIntent(fix)
            .setAutoCancel(true)
            .build()
        runCatching { nm(context).notify(ID_PROBLEM, n) }
    }

    fun describe(e: LogEntry): String = when (e.outcome) {
        Outcome.ForceStopped -> "force stopped"
        Outcome.NotRunning -> "wasn't running"
        Outcome.ScreenClosed -> "screen closed"
        Outcome.ScreenNotOpen -> "screen wasn't open"
        Outcome.BackgroundKilled -> "background only"
        Outcome.Deferred -> "waiting for unlock"
        Outcome.Failed -> "failed"
    } + if (e.detail.isNotBlank()) " — ${e.detail}" else ""

    private fun action(context: Context, title: String, action: String, ruleId: Long, occurrence: Long): Notification.Action {
        val intent = Intent(context, ActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("sundown://rule/$ruleId"))
            .putExtra(AlarmReceiver.EXTRA_RULE, ruleId)
            .putExtra(AlarmReceiver.EXTRA_OCCURRENCE, occurrence)
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun openApp(context: Context, tab: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab)
        return PendingIntent.getActivity(context, tab.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)
}
