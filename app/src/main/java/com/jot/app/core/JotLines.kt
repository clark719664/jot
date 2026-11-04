package com.jot.app.core

import com.jot.app.data.ActionKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything Jot says out loud. Short, warm, and never a list of commands. */
object JotLines {
    const val ONE_SEC = "One sec."
    const val ALMOST_THERE = "Almost there."
    const val WHAT_SHOULD_IT_SAY = "No problem. What should it say?"
    const val IS_THAT_RIGHT = "Sorry, did I get that right?"
    const val MISSED_IT = "Sorry, I didn't catch that. One more time?"
    const val ANYTHING_ELSE = "Saved. Want a reminder or an alarm with that?"
    const val WHICH_ONE = "Sure. An alarm, your calendar, a reminder, or a message?"
    const val WHAT_DAY = "What day?"
    const val WHAT_TIME = "What time? Morning or evening helps."
    const val NEW_MESSAGE = "Sure. What should the message say?"
    const val ALLOW_EXACT = "I need your okay to set exact reminders. Open Jot, or say approximate time."
    const val WHO_TO = "Who should it go to?"
    const val ALLOW_NOTIFICATIONS = "I need your okay to send reminders. Open Jot to allow notifications."

    fun readback(note: String): String {
        val sentence = if (note.last() in ".?!") note else "$note."
        return "Got it. $sentence Sound right?"
    }

    fun offer(actions: Set<ActionKind>, whenOf: (ActionKind) -> String? = { null }): String {
        val names = ActionKind.entries.filter { it in actions }.map { kind ->
            val name = when (kind) {
                ActionKind.ALARM -> "an alarm"
                ActionKind.CALENDAR -> "a calendar event"
                ActionKind.REMINDER -> "a reminder"
                ActionKind.MESSAGE -> "a message"
            }
            whenOf(kind)?.let { "$name $it" } ?: name
        }
        if (names.isEmpty()) return ""
        val verb = if (actions == setOf(ActionKind.MESSAGE)) "send" else "add"
        return "Want me to $verb " + (if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()) + "?"
    }

    fun repeats(days: List<DayOfWeek>): String? = when {
        days.isEmpty() -> null
        days.size == 7 -> "every day"
        days == DayOfWeek.entries.take(5) -> "on weekdays"
        days == DayOfWeek.entries.drop(5) -> "on weekends"
        else -> "every " + days.joinToString(" and ") { it.name.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase) }
    }

    fun addressed(recipient: String, body: String): String? {
        val text = body.trim().trimEnd('.', '!', '?')
        return listOfNotNull(recipient.takeIf { it.isNotBlank() }?.let { "to $it" }, text.takeIf { it.isNotEmpty() }?.let { "saying $it" })
            .joinToString(" ").ifEmpty { null }
    }

    fun message(recipient: String, body: String) =
        if (recipient.isBlank()) "Here's what I've got: $body Sound good?" else "Here's what I've got for $recipient: $body Sound good?"

    fun whichOne(names: List<String>) = if (names.size < 2) WHO_TO else "Which one, " + names.take(3).joinToString(" or ") + "?"

    fun noContact(name: String) = "I can't find $name in your contacts. Who should it go to?"

    fun finished(summary: String) = if (summary == "Saved" || summary == "Note saved") "Okay, it's saved." else "Done. $summary."

    fun spokenWhen(kind: ActionKind, date: LocalDate?, time: LocalTime?, today: LocalDate): String? {
        if (kind == ActionKind.MESSAGE || time == null) return null
        val clock = time.format(DateTimeFormatter.ofPattern(if (time.minute == 0) "h a" else "h:mm a", Locale.US))
        if (kind == ActionKind.ALARM) return "for $clock"
        val day = when {
            date == null -> null
            date == today -> "today"
            date == today.plusDays(1) -> "tomorrow"
            date.isAfter(today) && date.isBefore(today.plusDays(7)) -> "on " + date.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
            else -> "on " + date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
        }
        return listOfNotNull(day, "at $clock").joinToString(" ")
    }
}
