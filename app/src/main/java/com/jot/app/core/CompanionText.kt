package com.jot.app.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jot.app.data.ActionKind
import com.jot.app.data.NoteRecord
import java.time.ZonedDateTime
import java.util.Locale

data class Reading(
    val question: Boolean = false,
    val actions: Set<ActionKind> = emptySet(),
    val title: String? = null,
    val message: String? = null,
    val note: String? = null,
)

object CompanionText {
    const val NO_ANSWER = "I don't have a note about that."

    private val alarmCue = Regex("""\b(alarm|wake me|wake up)\b""")
    private val reminderCue = Regex("""\bremind(er)?\b|^(don't forget|do not forget|remember to)\b""")
    private val messageCue = Regex("""\b(text|tell|ask|message|email)\s+(?!me\b)[a-z]+|\blet [a-z]+ know\b""")
    private val leadingMessageCue = Regex("""^(text|tell|ask|message|email)\s+(?!me\b)[a-z]+|^let [a-z]+ know\b""")
    private val eventCue = Regex("""\b(appointment|meeting|dinner|lunch|breakfast|party|class|interview|flight|game|concert|wedding|doctor|dentist|practice|event|call with)\b""")
    private val interrogative = Regex("""^(what|what's|whats|when|when's|where|where's|who|who's|whose|which|how|did i|do i|have i|is my|are my|is there|was there)\b""")
    private val stopWords = setOf("a", "an", "and", "are", "did", "do", "for", "have", "how", "i", "in", "is", "it", "me", "my", "of", "on", "the", "to", "was", "what", "whats", "what's", "when", "where", "which", "who", "why", "with")

    fun parse(json: String): Reading? = runCatching {
        val root = JsonParser.parseString(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1)).asJsonObject
        val items = root.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty()
        val nested = items.filter { it.isJsonObject }.map { it.asJsonObject }
        val actions = items.mapNotNull { item ->
            val name = if (item.isJsonObject) listOf("type", "action", "kind").firstNotNullOfOrNull { item.asJsonObject.get(it)?.asString } else item.asString
            when (name?.lowercase(Locale.ROOT)) {
                "alarm" -> ActionKind.ALARM
                "calendar" -> ActionKind.CALENDAR
                "reminder" -> ActionKind.REMINDER
                "message" -> ActionKind.MESSAGE
                else -> null
            }
        }.orEmpty()
        Reading(
            question = root.get("question")?.asString.equals("true", ignoreCase = true),
            actions = ActionKind.entries.filter { it in actions }.toSet(),
            title = text(root, "title")?.let(::clean),
            message = (text(root, "message") ?: nested.firstNotNullOfOrNull { text(it, "message") ?: text(it, "text") })?.let(::clean),
            note = text(root, "note")?.let(::clean),
        )
    }.getOrNull()

    fun settle(note: String, model: Reading?, now: ZonedDateTime = ZonedDateTime.now()): Reading {
        val text = normalize(note)
        val time = TimeParser.suggest(note, now)
        val timed = time.date != null || time.time != null || time.ambiguousTime
        val alarm = alarmCue.containsMatchIn(text)
        val reminder = reminderCue.containsMatchIn(text)
        val message = messageCue.containsMatchIn(text)
        val event = eventCue.containsMatchIn(text) && timed
        val proposed = model?.actions.orEmpty().filter { kind ->
            when (kind) {
                ActionKind.MESSAGE -> message
                ActionKind.ALARM -> alarm || timed
                ActionKind.CALENDAR -> event || timed
                ActionKind.REMINDER -> reminder || timed
            }
        }.toMutableSet()
        if (alarm) proposed += ActionKind.ALARM
        if (reminder) proposed += ActionKind.REMINDER
        if (event && ActionKind.REMINDER !in proposed && !alarm) proposed += ActionKind.CALENDAR
        if (leadingMessageCue.containsMatchIn(text) && !reminder) { proposed.clear(); proposed += ActionKind.MESSAGE }
        val words = text.split(' ').count { it.isNotBlank() }
        val question = model != null && asks(note) && !reminder && !message && (model.question || words <= 8)
        return Reading(
            question = question,
            actions = if (question) emptySet() else ActionKind.entries.filter { it in proposed }.toSet(),
            title = model?.title?.takeIf { it.isNotBlank() && it.length <= 60 },
            message = model?.message?.takeIf { ActionKind.MESSAGE in proposed && it.isNotBlank() },
        )
    }

    /** Could any model proposal pass the local action checks for these words? */
    fun canOfferAction(note: String): Boolean =
        settle(note, Reading(actions = ActionKind.entries.toSet())).actions.isNotEmpty()

    /** The rules alone can offer a timed alarm or reminder, or a text to someone Jot can name, so nobody waits on the model. */
    fun ready(note: String, contacts: List<Contact> = emptyList(), now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val text = normalize(note)
        if (leadingMessageCue.containsMatchIn(text)) return ContactMatch.address(note, contacts)?.matches?.size == 1
        return (alarmCue.containsMatchIn(text) || reminderCue.containsMatchIn(text)) && TimeParser.suggest(note, now).time != null
    }

    fun startsWithMessage(note: String) = leadingMessageCue.containsMatchIn(normalize(note))

    fun asks(text: String) = interrogative.containsMatchIn(normalize(text)) || text.trim().endsWith("?")

    fun asksAboutNotes(note: String): Boolean {
        val text = normalize(note)
        return asks(note) && !reminderCue.containsMatchIn(text) && !messageCue.containsMatchIn(text)
    }

    /** The model's cleaned-up note, or what was heard when the rewrite strays too far from it. */
    fun fix(heard: String, proposed: String?): String {
        val candidate = proposed?.let(::clean)?.takeIf { it.isNotBlank() } ?: return heard
        val before = words(heard)
        val after = words(candidate).toSet()
        if (before.isEmpty()) return heard
        val kept = before.count { it in after }
        val ratio = after.size.toDouble() / before.size
        return if (ratio in 0.6..1.6 && kept * 2 >= before.size) candidate else heard
    }

    fun relevant(question: String, notes: List<NoteRecord>, limit: Int = 8): List<NoteRecord> {
        val terms = normalize(question).split(' ').filter { it.length > 1 && it !in stopWords }.map(::stem).toSet()
        if (terms.isEmpty()) return emptyList()
        return notes.asSequence().filter { !it.draft && it.text.isNotBlank() }
            .map { note -> note to normalize(note.text).split(' ').map(::stem).count { it in terms } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<NoteRecord, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .take(limit).map { it.first }.toList()
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("[^a-z0-9' :]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun text(json: JsonObject, key: String) = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()
    private fun words(text: String) = normalize(text).split(' ').filter { it.isNotBlank() }
    private fun stem(word: String) = if (word.length <= 4) word else word.removeSuffix("ing").removeSuffix("ed").removeSuffix("s")
    private fun clean(value: String) = value.trim().trim('"').replace(Regex("\\s+"), " ")
}
