package com.jot.app.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jot.app.data.ActionKind
import com.jot.app.data.NoteRecord
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Reading(
    val question: Boolean = false,
    val actions: Set<ActionKind> = emptySet(),
    val title: String? = null,
    val message: String? = null,
    val note: String? = null,
)

object CompanionText {
    const val READ = """You are Jot, a voice notes app. Read what the user said and decide what to do with it. Spoken notes come from speech recognition, so some words may be misheard and punctuation is missing.
Reply with JSON only: {"note":"","question":false,"actions":[],"title":"","message":""}
note: the user's words as they meant them. A word that makes no sense where it is was misheard, so replace it with the similar-sounding word that fits. Add punctuation and capital letters, and write times like 3 PM. Change nothing else.
question: true only when the user is asking about something they saved before, such as "what's the gate code" or "when is my dentist appointment". A note to save is false.
actions: add only what the note asks for.
"alarm": an alarm or waking up.
"calendar": an appointment, meeting, class, dinner or other event.
"reminder": something to do later, such as remind me, pick up, call or pay.
"message": telling, texting or asking someone something.
Lists, ideas and facts get no actions.
title: 2 to 5 words naming the main event or task, keeping names of people and places, with no times or dates.
message: when "message" is chosen, the text to send to that person, written by the user in first person; otherwise empty."""

    val examples = listOf(
        "tell mia im running a little lake" to """{"note":"Tell Mia I'm running a little late.","question":false,"actions":["message"],"title":"Running late","message":"Hi Mia, I'm running a little late."}""",
        "lunch with priya next tuesday at new" to """{"note":"Lunch with Priya next Tuesday at noon.","question":false,"actions":["calendar"],"title":"Lunch with Priya","message":""}""",
        "game night saturday at eight tell leo to bring chips" to """{"note":"Game night Saturday at 8, tell Leo to bring chips.","question":false,"actions":["calendar","message"],"title":"Game night","message":"Hi Leo, can you bring chips to game night Saturday at 8?"}""",
        "i need to by stamps on the weigh home" to """{"note":"I need to buy stamps on the way home.","question":false,"actions":[],"title":"Buy stamps","message":""}""",
        "whats the gate code" to """{"note":"What's the gate code?","question":true,"actions":[],"title":"Gate code","message":""}""",
    )

    const val ANSWER = """You are Jot. Answer the user's question using only their notes below. Reply in one short spoken sentence. If the notes do not answer it, reply exactly: I don't have a note about that."""
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
        if (leadingMessageCue.containsMatchIn(text) && !reminder) proposed += ActionKind.MESSAGE
        val words = text.split(' ').count { it.isNotBlank() }
        val question = model != null && asks(note) && !reminder && !message && (model.question || words <= 8)
        return Reading(
            question = question,
            actions = if (question) emptySet() else ActionKind.entries.filter { it in proposed }.toSet(),
            title = model?.title?.takeIf { it.isNotBlank() && it.length <= 60 },
            message = model?.message?.takeIf { ActionKind.MESSAGE in proposed && it.isNotBlank() },
        )
    }

    fun asks(text: String) = interrogative.containsMatchIn(normalize(text)) || text.trim().endsWith("?")

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

    fun notesContext(notes: List<NoteRecord>, zone: ZoneId = ZoneId.systemDefault()): String {
        val format = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val lines = notes.joinToString("\n") { "- (${Instant.ofEpochMilli(it.updatedAt).atZone(zone).format(format)}) ${it.text.replace('\n', ' ')}" }
        return "$ANSWER\n\nNotes:\n$lines"
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("[^a-z0-9' :]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun text(json: JsonObject, key: String) = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()
    private fun words(text: String) = normalize(text).split(' ').filter { it.isNotBlank() }
    private fun stem(word: String) = if (word.length <= 4) word else word.removeSuffix("ing").removeSuffix("ed").removeSuffix("s")
    private fun clean(value: String) = value.trim().trim('"').replace(Regex("\\s+"), " ")
}
