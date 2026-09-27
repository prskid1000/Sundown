package app.sundown

import app.sundown.model.Rule
import app.sundown.model.RuleKind
import app.sundown.model.Target
import app.sundown.schedule.RuleEngine
import app.sundown.schedule.RuleEngine.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RuleEngineTest {
    private val zone = ZoneId.of("Asia/Dubai")
    private val london = ZoneId.of("Europe/London")
    private val yt = listOf(Target("com.google.android.youtube", "YouTube"))

    private fun at(s: String, z: ZoneId = zone) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
    private fun schedule(time: String, mask: Int = Rule.ALL_DAYS, warn: Int = 0) = Rule(
        id = 1,
        minuteOfDay = time.substring(0, 2).toInt() * 60 + time.substring(3, 5).toInt(),
        daysMask = mask,
        warnMinutes = warn,
        targets = yt,
    )

    @Test fun laterToday() {
        // 2026-09-28 is a Monday.
        assertEquals(at("2026-09-28T23:30"), RuleEngine.nextOccurrence(schedule("23:30"), at("2026-09-28T20:00"), zone))
    }

    @Test fun alreadyPassedGoesToTomorrow() {
        assertEquals(at("2026-09-29T23:30"), RuleEngine.nextOccurrence(schedule("23:30"), at("2026-09-28T23:45"), zone))
    }

    @Test fun exactlyNowIsNotNext() {
        assertEquals(at("2026-09-29T23:30"), RuleEngine.nextOccurrence(schedule("23:30"), at("2026-09-28T23:30"), zone))
    }

    @Test fun weekdaysSkipTheWeekend() {
        // Friday night after closing → Monday.
        assertEquals(
            at("2026-10-05T07:00"),
            RuleEngine.nextOccurrence(schedule("07:00", Rule.WEEKDAYS), at("2026-10-02T08:00"), zone),
        )
    }

    @Test fun noDaysNeverFires() {
        assertNull(RuleEngine.nextOccurrence(schedule("07:00", 0), at("2026-10-02T08:00"), zone))
    }

    @Test fun disabledOrEmptyNeverFires() {
        assertNull(RuleEngine.nextOccurrence(schedule("23:30").copy(enabled = false), at("2026-09-28T20:00"), zone))
        assertNull(RuleEngine.nextOccurrence(schedule("23:30").copy(targets = emptyList()), at("2026-09-28T20:00"), zone))
    }

    @Test fun warningComesFirstThenClosing() {
        val r = schedule("23:30", warn = 5)
        assertEquals(RuleEngine.Event(EventType.Warn, at("2026-09-28T23:25"), at("2026-09-28T23:30")), RuleEngine.nextEvent(r, at("2026-09-28T20:00"), zone))
        assertEquals(RuleEngine.Event(EventType.Fire, at("2026-09-28T23:30"), at("2026-09-28T23:30")), RuleEngine.nextEvent(r, at("2026-09-28T23:27"), zone))
    }

    @Test fun lateAlarmIsStillDueAndDoesNotRepeat() {
        val r = schedule("23:30")
        val occ = at("2026-09-28T23:30")
        assertTrue(RuleEngine.stillDue(r, occ, zone))
        val fired = RuleEngine.afterFiring(r, occ)
        assertFalse(RuleEngine.stillDue(fired, occ, zone))
        // Even asked from before, the fired occurrence is not offered again.
        assertEquals(at("2026-09-29T23:30"), RuleEngine.nextOccurrence(fired, at("2026-09-28T23:00"), zone))
    }

    @Test fun snoozeMovesOnlyThatOccurrence() {
        val occ = at("2026-09-28T23:30")
        val r = RuleEngine.snooze(schedule("23:30"), occ, 10, at("2026-09-28T23:26"))
        assertEquals(at("2026-09-28T23:40"), RuleEngine.nextOccurrence(r, at("2026-09-28T23:27"), zone))
        assertTrue(RuleEngine.stillDue(r, at("2026-09-28T23:40"), zone))
        assertFalse(RuleEngine.stillDue(r, occ, zone))
        val fired = RuleEngine.afterFiring(r, at("2026-09-28T23:40"))
        assertEquals(at("2026-09-29T23:30"), RuleEngine.nextOccurrence(fired, at("2026-09-28T23:41"), zone))
    }

    @Test fun snoozingTwiceKeepsTheOriginalSuppressed() {
        val occ = at("2026-09-28T23:30")
        val once = RuleEngine.snooze(schedule("23:30"), occ, 10, at("2026-09-28T23:26"))
        val twice = RuleEngine.snooze(once, at("2026-09-28T23:40"), 10, at("2026-09-28T23:36"))
        assertEquals(occ, twice.snoozeFrom)
        assertEquals(at("2026-09-28T23:50"), RuleEngine.nextOccurrence(twice, at("2026-09-28T23:37"), zone))
    }

    @Test fun skipGoesToTheNextDay() {
        val occ = at("2026-09-28T23:30")
        val r = RuleEngine.skip(schedule("23:30"), occ)
        assertEquals(at("2026-09-29T23:30"), RuleEngine.nextOccurrence(r, at("2026-09-28T23:26"), zone))
    }

    @Test fun skipOnAWeeklyRuleGoesAWeek() {
        val mondays = 0b0000001
        val occ = at("2026-09-28T21:00")
        val r = RuleEngine.skip(schedule("21:00", mondays), occ)
        assertEquals(at("2026-10-05T21:00"), RuleEngine.nextOccurrence(r, at("2026-09-28T20:00"), zone))
    }

    @Test fun springForwardGapStillCloses() {
        // Europe/London skips 01:00–02:00 on 2027-03-28.
        val r = schedule("01:30")
        val next = RuleEngine.nextOccurrence(r, at("2027-03-28T00:00", london), london)
        assertEquals(at("2027-03-28T02:30", london), next)
    }

    @Test fun autumnOverlapClosesOnce() {
        // 01:30 happens twice on 2026-10-25 in London.
        val r = schedule("01:30")
        val first = RuleEngine.nextOccurrence(r, at("2026-10-25T00:00", london), london)!!
        val fired = RuleEngine.afterFiring(r, first)
        val second = RuleEngine.nextOccurrence(fired, first, london)!!
        assertEquals(at("2026-10-26T01:30", london), second)
    }

    @Test fun timerFiresOnceEvenIfOverdue() {
        val start = at("2026-09-28T22:00")
        val t = RuleEngine.startTimer(Rule(id = 2, kind = RuleKind.Timer, targets = yt), start, 45)
        assertEquals(at("2026-09-28T22:45"), t.endsAt)
        // The phone was off past the end: still owed.
        assertEquals(at("2026-09-28T22:45"), RuleEngine.nextOccurrence(t, at("2026-09-28T23:10"), zone))
        val fired = RuleEngine.afterFiring(t, t.endsAt!!)
        assertNull(fired.endsAt)
        assertNull(RuleEngine.nextOccurrence(fired, at("2026-09-28T23:10"), zone))
        assertEquals(45, fired.durationMinutes)
    }

    @Test fun timerExtendAndCancel() {
        val start = at("2026-09-28T22:00")
        val t = RuleEngine.startTimer(Rule(id = 2, kind = RuleKind.Timer, targets = yt), start, 30)
        val ext = RuleEngine.snooze(t, t.endsAt!!, 10, at("2026-09-28T22:20"))
        assertEquals(at("2026-09-28T22:40"), ext.endsAt)
        assertNull(RuleEngine.skip(ext, ext.endsAt!!).endsAt)
    }
}
