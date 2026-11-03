package com.jot.app.core
import com.jot.app.data.ActionKind

enum class ReviewCommand { CONFIRM, NO, CHANGE, CANCEL, UNKNOWN }
object ConversationCommands {
    private val yesWords = setOf("yes", "yeah", "yep", "yup", "ya", "sure", "ok", "okay", "correct", "right", "perfect", "good", "great", "exactly", "confirm", "absolutely", "definitely")
    private val yesPhrases = setOf("save", "save it", "keep it", "that's right", "thats right", "that's it", "thats it", "sounds good", "sounds right", "sounds great", "looks good", "go ahead", "do it", "that works", "you got it")
    private val noWords = setOf("no", "nope", "nah")
    private val changePhrases = setOf("change", "change it", "edit", "try again", "redo", "fix it", "wrong", "that's wrong", "thats wrong", "not quite", "not right", "that's not right", "thats not right", "no change it")
    private val cancelPhrases = setOf("cancel", "discard", "don't save", "do not save", "stop", "never mind", "nevermind", "forget it", "forget about it", "delete it")

    private fun clean(text: String) = text.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun review(text: String): ReviewCommand {
        val value = clean(text)
        val words = value.split(' ')
        return when {
            value in cancelPhrases -> ReviewCommand.CANCEL
            value in changePhrases -> ReviewCommand.CHANGE
            value in noWords || (words.first() in noWords && words.size <= 2) -> ReviewCommand.NO
            value in yesPhrases -> ReviewCommand.CONFIRM
            words.first() in yesWords && words.size <= 4 && words.none { it in noWords || it == "not" } -> ReviewCommand.CONFIRM
            else -> ReviewCommand.UNKNOWN
        }
    }

    /** A spoken correction such as "no, dinner with Ana", without the leading "no". */
    fun correction(text: String): String? {
        val words = text.trim().split(Regex("\\s+"))
        val leading = clean(words.first()) in noWords
        val rest = (if (leading) words.drop(1) else words).joinToString(" ").trim(' ', ',', '.')
        return rest.takeIf { rest.split(' ').size >= 3 }
    }

    fun actions(text: String): Set<ActionKind>? {
        val value = clean(text).removePrefix("add it to ").removePrefix("add to ").removePrefix("a ")
        if (value in listOf("done", "nothing", "just the note", "note only", "no", "nope", "nah", "that's all", "thats all", "all good", "i'm good", "im good")) return emptySet()
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
