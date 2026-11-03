package com.jot.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class TimeSuggestion(
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val ambiguousTime: Boolean = false,
)

object TimeParser {
    private val units = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase(Locale.ROOT) }
    private val unitsPattern = units.joinToString("|")
    private val tensPattern = tens.joinToString("|")
    private val numberWords = "(?:(?:$tensPattern)(?: (?:${units.drop(1).take(9).joinToString("|")}))?|$unitsPattern)"
    private val meridiem = "(?:am|pm|(?:in the )?morning|(?:in the )?afternoon|(?:in the )?evening|at night)"
    private val meridiemSpelling = Regex("""(?<![a-z])([ap])\s*\.?\s*m\b\.?""")
    private val relative = Regex("""\bin (\d+|$numberWords|an?) (minutes?|hours?)\b""")
    private val dateWord = Regex("""\b(?:(next|this|on) )?(today|tomorrow|${weekdays.keys.joinToString("|")})\b""")
    private val namedTime = Regex("""\b(?:at )?(noon|midnight)\b""")
    private val digitClock = Regex("""\b(at )?(\d{1,2})(?::(\d+))?(?: ?($meridiem))?\b(?!:)""")
    private val spokenHour = (units + "twenty(?: one| two| three)?").joinToString("|")
    private val spokenClock = Regex("""\b(at )?($spokenHour)(?: (?:oh |o )?($numberWords|\d{1,2}))?(?: o['’]clock)?(?: ($meridiem))?\b""")

    fun suggest(text: String, now: ZonedDateTime = ZonedDateTime.now()): TimeSuggestion {
        val normalized = meridiemSpelling.replace(text.lowercase(Locale.ROOT)) { "${it.groupValues[1]}m" }
            .replace(Regex("""(?<=[a-z])-(?=[a-z])"""), " ")
            .replace(Regex("""\s+"""), " ")
        relative.find(normalized)?.let { match ->
            val amount = parseNumber(match.groupValues[1])?.takeIf { it > 0 }
            if (amount != null) {
                val target = if (match.groupValues[2].startsWith("hour")) now.plusHours(amount.toLong())
                else now.plusMinutes(amount.toLong())
                return TimeSuggestion(target.toLocalDate(), target.toLocalTime())
            }
        }

        val today = now.toLocalDate()
        val date = dateWord.find(normalized)?.let { match ->
            when (val word = match.groupValues[2]) {
                "today" -> today
                "tomorrow" -> today.plusDays(1)
                else -> {
                    val weekday = weekdays.getValue(word)
                    val adjuster = if (match.groupValues[1] == "next") TemporalAdjusters.next(weekday)
                    else TemporalAdjusters.nextOrSame(weekday)
                    today.with(adjuster)
                }
            }
        }
        val named = namedTime.find(normalized)?.groupValues?.get(1)
        if (named != null) {
            return TimeSuggestion(date, if (named == "noon") LocalTime.NOON else LocalTime.MIDNIGHT)
        }

        val digits = digitClock.findAll(normalized).firstOrNull { match ->
            val (at, _, minutes, period) = match.destructured
            at.isNotEmpty() || minutes.isNotEmpty() || period.isNotEmpty()
        }
        val words = spokenClock.findAll(normalized).firstOrNull { match ->
            val (at, _, _, period) = match.destructured
            at.isNotEmpty() || period.isNotEmpty()
        }
        val clock = listOfNotNull(digits, words).minByOrNull { it.range.first }
            ?: return TimeSuggestion(date)
        val (_, hourText, minuteText, period) = clock.destructured
        val hour = parseNumber(hourText) ?: return TimeSuggestion(date)
        val minute = if (minuteText.isEmpty()) 0 else parseNumber(minuteText)
        if (hour !in 0..23 || minute == null || minute !in 0..59) return TimeSuggestion(date)
        if (period.isEmpty()) {
            if (hour in 1..12 && !hourText.startsWith("0")) {
                return TimeSuggestion(date, ambiguousTime = true)
            }
            return TimeSuggestion(date, LocalTime.of(hour, minute))
        }
        if (hour !in 1..12) return TimeSuggestion(date)
        val afternoonOrLater = period == "pm" || period.endsWith("afternoon") ||
            period.endsWith("evening") || period == "at night"
        return TimeSuggestion(date, LocalTime.of(hour % 12 + if (afternoonOrLater) 12 else 0, minute))
    }

    private fun parseNumber(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        if (text == "a" || text == "an") return 1
        val unit = units.indexOf(text)
        if (unit >= 0) return unit
        val tensIndex = tens.indexOf(text.substringBefore(' '))
        if (tensIndex < 0) return null
        val remainder = text.substringAfter(' ', "")
        val ones = if (remainder.isEmpty()) 0 else units.indexOf(remainder)
        return (20 + tensIndex * 10 + ones).takeIf { ones in 0..9 }
    }
}
