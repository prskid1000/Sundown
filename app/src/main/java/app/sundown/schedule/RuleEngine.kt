package app.sundown.schedule

import app.sundown.model.Rule
import app.sundown.model.RuleKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * When does a rule next close something, and does it warn first.
 *
 * Pure: no Android, no clock of its own. Every wall-clock question — midnight,
 * days of the week, a daylight-saving jump — is decided here so it can be
 * tested on the JVM, and nothing else in the app does date arithmetic.
 */
object RuleEngine {

    enum class EventType { Warn, Fire }

    /** [at] is when the alarm should ring; [occurrence] is the closing it belongs to. */
    data class Event(val type: EventType, val at: Long, val occurrence: Long)

    /**
     * The next closing strictly after [after], honouring a snooze or skip, and
     * never one at or before [Rule.lastFired].
     */
    fun nextOccurrence(rule: Rule, after: Long, zone: ZoneId): Long? {
        if (!rule.enabled || rule.targets.isEmpty()) return null
        return when (rule.kind) {
            // A timer that ran out while the phone was off is still owed: it
            // is returned even if already past, and the alarm set for a past
            // instant rings immediately. A schedule is not owed — closing
            // tonight's apps at 09:00 because the phone rebooted is wrong.
            RuleKind.Timer -> rule.endsAt?.takeIf { it > rule.lastFired }
            RuleKind.Schedule -> nextScheduled(rule, after, zone)
        }
    }

    private fun nextScheduled(rule: Rule, after: Long, zone: ZoneId): Long? {
        if (rule.daysMask and Rule.ALL_DAYS == 0) return null
        val floor = maxOf(after, rule.lastFired)

        val moved = rule.snoozeTo?.takeIf { rule.snoozeFrom != null && it > floor }
        val natural = naturalAfter(rule, floor, zone) { it != rule.snoozeFrom }

        return listOfNotNull(moved, natural).minOrNull()
    }

    /** The first occurrence on an enabled day strictly after [floor] that [accept] allows. */
    private fun naturalAfter(rule: Rule, floor: Long, zone: ZoneId, accept: (Long) -> Boolean): Long? {
        val time = LocalTime.of(rule.minuteOfDay / 60, rule.minuteOfDay % 60)
        var day = Instant.ofEpochMilli(floor).atZone(zone).toLocalDate().minusDays(1)
        // Eight days covers a full week from either side of midnight; a
        // fifteenth is only reached when the one weekly day is also skipped.
        repeat(15) {
            day = day.plusDays(1)
            if (!isEnabled(rule, day)) return@repeat
            // atZone resolves a time that a spring-forward gap removed to the
            // instant just after the gap, and an ambiguous autumn time to the
            // earlier offset — so 02:30 on the night the clocks change still
            // closes, once.
            val at = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (at > floor && accept(at)) return at
        }
        return null
    }

    fun isEnabled(rule: Rule, day: LocalDate): Boolean =
        rule.daysMask and (1 shl (day.dayOfWeek.value - 1)) != 0

    /**
     * The next alarm to set for a rule: its warning if that is still ahead,
     * otherwise the closing itself.
     */
    fun nextEvent(rule: Rule, now: Long, zone: ZoneId): Event? {
        val occ = nextOccurrence(rule, now, zone) ?: return null
        val warnAt = occ - rule.warnMinutes * 60_000L
        return if (rule.warnMinutes > 0 && warnAt > now) {
            Event(EventType.Warn, warnAt, occ)
        } else {
            Event(EventType.Fire, occ, occ)
        }
    }

    /**
     * Is [occurrence] still what [rule] means to close? An alarm carries the
     * instant it was set for, and the rule may have been edited, disabled or
     * snoozed since.
     */
    fun stillDue(rule: Rule, occurrence: Long, zone: ZoneId): Boolean =
        nextOccurrence(rule, occurrence - 1, zone) == occurrence

    /** The rule after [occurrence] has been closed. */
    fun afterFiring(rule: Rule, occurrence: Long): Rule = rule.copy(
        lastFired = occurrence,
        snoozeFrom = null,
        snoozeTo = null,
        // A timer runs once; its duration stays so it can be started again.
        endsAt = if (rule.kind == RuleKind.Timer) null else rule.endsAt,
    )

    /** "+N min": push [occurrence] back without touching the schedule. */
    fun snooze(rule: Rule, occurrence: Long, minutes: Int, now: Long): Rule {
        val to = maxOf(occurrence, now) + minutes * 60_000L
        return when (rule.kind) {
            RuleKind.Timer -> rule.copy(endsAt = to)
            // Snoozing an already-snoozed occurrence keeps the original it
            // replaced, or the natural one would come back as well.
            RuleKind.Schedule -> rule.copy(snoozeFrom = rule.snoozeFrom ?: occurrence, snoozeTo = to)
        }
    }

    /** "Skip this time". */
    fun skip(rule: Rule, occurrence: Long): Rule = when (rule.kind) {
        RuleKind.Timer -> rule.copy(endsAt = null)
        RuleKind.Schedule -> rule.copy(snoozeFrom = rule.snoozeFrom ?: occurrence, snoozeTo = null)
    }

    fun startTimer(rule: Rule, now: Long, minutes: Int = rule.durationMinutes): Rule =
        rule.copy(durationMinutes = minutes, endsAt = now + minutes * 60_000L, lastFired = 0)
}
