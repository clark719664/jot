package com.jot.app.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeParserTest {
    @Test fun readsRepeatingDays() {
        assertEquals(java.time.DayOfWeek.entries, TimeParser.repeatDays("alarm for 6:50 am every day to get the kids up"))
        assertEquals(java.time.DayOfWeek.entries, TimeParser.repeatDays("wake me up daily at 7"))
        assertEquals(java.time.DayOfWeek.entries.take(5), TimeParser.repeatDays("alarm at 6 on weekdays"))
        assertEquals(listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY), TimeParser.repeatDays("every Monday and Friday at 8 am"))
        assertEquals(emptyList<java.time.DayOfWeek>(), TimeParser.repeatDays("alarm for Monday at 8 am"))
        assertEquals("every day", JotLines.repeats(java.time.DayOfWeek.entries))
        assertEquals("every Monday and Friday", JotLines.repeats(listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY)))
    }

    private val now = ZonedDateTime.parse("2026-11-02T23:50:00-05:00[America/New_York]")

    @Test fun tomorrowAtNineLeavesTimeAmbiguous() {
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 3), ambiguousTime = true),
            TimeParser.suggest("tomorrow at nine", now),
        )
    }

    @Test fun dottedMeridiemAppliesToDigitTime() {
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 2), LocalTime.of(21, 30)),
            TimeParser.suggest("today at 9:30 p.m.", now),
        )
    }

    @Test fun attachedMeridiemAppliesToDigitTime() {
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 6), LocalTime.of(16, 0)),
            TimeParser.suggest("Pick flowers for Mina next Friday at 4pm", now),
        )
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 3), LocalTime.of(9, 0)),
            TimeParser.suggest("Tomorrow at 9am.", now),
        )
        assertEquals(
            TimeSuggestion(time = LocalTime.of(21, 15)),
            TimeParser.suggest("Call at 9:15p.m., after dinner.", now),
        )
    }

    @Test fun spokenRelativeTimeRollsOverMidnight() {
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 3), LocalTime.of(0, 15)),
            TimeParser.suggest("in twenty five minutes", now),
        )
    }

    @Test fun weekdayWithSpokenTime() {
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 6), LocalTime.of(7, 30)),
            TimeParser.suggest("on Friday at seven thirty in the morning", now),
        )
    }

    @Test fun recognizesNoonAndMidnight() {
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 3), LocalTime.NOON),
            TimeParser.suggest("tomorrow at noon", now),
        )
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 6), LocalTime.MIDNIGHT),
            TimeParser.suggest("Friday at midnight", now),
        )
    }

    @Test fun unrelatedNumberDoesNotSetTime() {
        assertEquals(TimeSuggestion(), TimeParser.suggest("pick up package 25", now))
    }

    @Test fun invalidClockMinutesDoNotBecomeWholeHours() {
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 11, 3)),
            TimeParser.suggest("tomorrow at 8:90 pm", now),
        )
    }

    @Test fun bareMinuteFormStaysAmbiguous() {
        assertEquals(
            TimeSuggestion(ambiguousTime = true),
            TimeParser.suggest("call at 9:15", now),
        )
    }

    @Test fun explicit24HourTimeIsUnambiguous() {
        assertEquals(
            TimeSuggestion(time = LocalTime.of(18, 45)),
            TimeParser.suggest("call at 18:45", now),
        )
    }

    @Test fun relativeHoursCrossDstGapAsElapsedTime() {
        val beforeGap = ZonedDateTime.parse("2026-03-08T01:30:00-05:00[America/New_York]")
        assertEquals(
            TimeSuggestion(LocalDate.of(2026, 3, 8), LocalTime.of(3, 30)),
            TimeParser.suggest("in one hour", beforeGap),
        )
    }
}
