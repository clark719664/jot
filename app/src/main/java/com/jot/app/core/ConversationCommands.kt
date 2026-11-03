package com.jot.app.core
import com.jot.app.data.ActionKind

enum class ReviewCommand { CONFIRM, CHANGE, CANCEL, UNKNOWN }
object ConversationCommands {
    private fun clean(text: String) = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
    fun review(text: String): ReviewCommand = when(clean(text)) {
        "yes", "confirm", "save", "save it", "keep it", "yes please", "correct" -> ReviewCommand.CONFIRM
        "change", "change it", "edit", "try again", "no change it" -> ReviewCommand.CHANGE
        "cancel", "discard", "don't save", "do not save", "no", "stop" -> ReviewCommand.CANCEL
        else -> ReviewCommand.UNKNOWN
    }
    fun actions(text: String): Set<ActionKind>? {
        val value = clean(text).removePrefix("add it to ").removePrefix("add to ").removePrefix("a ")
        if (value in listOf("done", "nothing", "just the note", "note only", "no", "that's all")) return emptySet()
        val parts = value.replace("alarm clock", "alarm").replace("text message", "message").replace("a reminder", "reminder").split(Regex("\\s+(?:and|also|plus)\\s+"))
        val result = linkedSetOf<ActionKind>()
        for (part in parts) result += when (part.trim().removePrefix("my ").removePrefix("the ").removePrefix("a ")) {
            "alarm", "alarms", "clock" -> ActionKind.ALARM
            "calendar", "calendars", "event" -> ActionKind.CALENDAR
            "reminder", "reminders" -> ActionKind.REMINDER
            "message", "messages", "text", "draft" -> ActionKind.MESSAGE
            else -> return null
        }
        return result
    }
}
