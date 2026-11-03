package com.jot.app.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.core.CompanionText
import com.jot.app.data.ActionKind
import com.jot.app.data.NoteRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CompanionModelTest {
    @Test
    fun readsNotesAndAnswersQuestionsOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "companion/gemma-4-E2B-it.litertlm")
        assumeTrue("Push the companion model to ${file.path} to run this test.", file.length() == CompanionModel.SIZE)
        context.getSharedPreferences("companion", 0).edit().putBoolean("on", true).commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = CompanionModel(context, scope).apply { timeoutMs = 240_000 }
            withTimeout(300_000) { model.state.first { it == CompanionState.Ready } }

            val note = "Text Sam that I'm running ten minutes late"
            val reading = CompanionText.settle(note, model.read(note))
            assertEquals(reading.toString(), setOf(ActionKind.MESSAGE), reading.actions)
            assertTrue(reading.toString(), reading.message.orEmpty().contains("late", ignoreCase = true))

            val event = CompanionText.settle("Dentist next Tuesday at 3 pm", model.read("Dentist next Tuesday at 3 pm"))
            assertTrue(event.actions.toString(), ActionKind.CALENDAR in event.actions)

            val notes = listOf(NoteRecord("1", "The wifi password is maple 42", 0, 0), NoteRecord("2", "Parked on level three row C", 0, 0))
            val answer = model.answer("What's the wifi password", notes).orEmpty()
            assertTrue(answer, answer.contains("maple", ignoreCase = true))
        } finally {
            scope.cancel()
        }
    }
}
