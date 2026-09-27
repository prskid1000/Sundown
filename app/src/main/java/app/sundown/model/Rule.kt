package app.sundown.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One thing to close, and when.
 *
 * A schedule and a timer are the same row with a different [kind] rather than
 * two tables, because everything downstream — the alarm, the warning, the
 * closer, the log — treats them identically once an instant has been decided.
 * Only [app.sundown.schedule.RuleEngine] needs to know the difference.
 */
@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: RuleKind = RuleKind.Schedule,

    /** Schedule: minutes after local midnight. */
    val minuteOfDay: Int = 23 * 60,
    /** Schedule: bit 0 = Monday … bit 6 = Sunday, matching `DayOfWeek.value - 1`. */
    val daysMask: Int = ALL_DAYS,

    /** Timer: the length it is started with. */
    val durationMinutes: Int = 30,
    /** Timer: when a running timer fires, epoch ms. Null when not running. */
    val endsAt: Long? = null,

    /** Minutes of notice before closing; 0 closes without warning. */
    val warnMinutes: Int = 0,
    /** Take audio focus first, so a player that is closing stops instead of cutting out. */
    val pauseAudio: Boolean = false,

    val targets: List<Target> = emptyList(),

    /**
     * A single occurrence moved by "+10 min" or cancelled by "Skip".
     *
     * Stored as the occurrence it replaces rather than as a new time on the
     * rule, so the schedule itself is never edited by a notification button:
     * tomorrow's 23:30 is still 23:30 no matter how often tonight's was pushed.
     */
    val snoozeFrom: Long? = null,
    /** Where [snoozeFrom] was moved to; null means that occurrence is skipped. */
    val snoozeTo: Long? = null,

    /**
     * The last occurrence actually closed. An alarm that arrives a little late
     * must not be taken for the next occurrence, and one that fires twice must
     * not close twice.
     */
    val lastFired: Long = 0,
) {
    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
    }
}

enum class RuleKind { Schedule, Timer }

/**
 * An app, or one screen of it.
 *
 * [activity] null means the whole app, which is force-stopped. A named
 * activity is closed with Back only when it is the one on screen — a screen
 * cannot be removed from an app that keeps running without stopping the app.
 */
@Serializable
data class Target(
    val packageName: String,
    val label: String,
    val activity: String? = null,
) {
    val isScreen: Boolean get() = activity != null

    /** `com.google.android.youtube.ShortsActivity` → `ShortsActivity`. */
    val screenName: String? get() = activity?.substringAfterLast('.')
}
