package com.jot.app.core

/** The recognizer writes hesitations out as words and ends a sentence at each pause. A note has neither. */
object SpeechText {
    private val hesitation = Regex("""[,.]?\s*\b(?:u+m+|u+h+|e+r+m+|e+r+|a+h+|h+m+|m+h*m+)\b[,.]?(?=\s|$)""", RegexOption.IGNORE_CASE)

    fun tidy(text: String) = hesitation.replace(text, " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', ',')
        .replaceFirstChar { it.uppercase() }
}
