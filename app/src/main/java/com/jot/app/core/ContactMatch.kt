package com.jot.app.core

import java.util.Locale

data class Contact(val name: String, val number: String)

/** Who a sentence is addressed to, and what is left of it without the name. */
data class Addressee(val said: String, val matches: List<Contact>, val rest: String)

object ContactMatch {
    private const val MAX_NAME_WORDS = 3
    private val cue = Regex("""\b(?:text|tell|ask|message|email)\s+""", RegexOption.IGNORE_CASE)
    private val lead = Regex("""^(?:that|saying|say|to say|to)\s+""", RegexOption.IGNORE_CASE)

    fun address(note: String, contacts: List<Contact>): Addressee? {
        val start = cue.find(note) ?: return null
        val words = note.substring(start.range.last + 1).trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        for (count in minOf(MAX_NAME_WORDS, words.size) downTo 1) {
            val said = words.take(count).joinToString(" ").trim(',', '.', ':')
            val matches = find(said, contacts)
            if (matches.isNotEmpty()) return Addressee(said, matches, words.drop(count).joinToString(" "))
        }
        return Addressee(words.first().trim(',', '.', ':'), emptyList(), words.drop(1).joinToString(" "))
    }

    /** The contacts that best fit what was said. Several means Jot has to ask which one. */
    fun find(said: String, contacts: List<Contact>): List<Contact> {
        val spoken = words(said)
        if (spoken.isEmpty()) return emptyList()
        val scored = contacts.map { it to score(spoken, words(it.name)) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }
    }

    fun body(rest: String): String {
        val text = rest.trim(' ', ',', '.', ':').replace(lead, "").replaceFirstChar { it.uppercase() }
        return if (text.isEmpty() || text.last() in ".?!") text else "$text."
    }

    private fun score(spoken: List<String>, name: List<String>) = when {
        spoken == name -> 3
        spoken.all { it in name } -> 2
        spoken.all { word -> name.any { close(word, it) } } -> 1
        else -> 0
    }

    private fun close(said: String, name: String): Boolean {
        if (minOf(said.length, name.length) < 3) return false
        val allowed = if (maxOf(said.length, name.length) < 6) 1 else 2
        return distance(said, name) <= allowed || consonants(said) == consonants(name)
    }

    private fun consonants(word: String) = word.replace("ph", "f").replace('c', 'k').filter { it !in "aeiouhwy" }

    private fun distance(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diagonal = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val next = minOf(row[j] + 1, row[j - 1] + 1, diagonal + if (a[i - 1] == b[j - 1]) 0 else 1)
                diagonal = row[j]
                row[j] = next
            }
        }
        return row[b.length]
    }

    private fun words(text: String) = text.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}0-9' ]"""), " ").split(' ').filter { it.isNotBlank() }
}
