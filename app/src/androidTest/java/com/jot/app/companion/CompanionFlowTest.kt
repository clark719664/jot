package com.jot.app.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.JotApplication
import com.jot.app.capture.VoiceEvent
import com.jot.app.capture.VoiceSignals
import com.jot.app.data.ActionKind
import com.jot.app.data.JotStore
import com.jot.app.data.NoteRecord
import com.jot.app.ui.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CompanionFlowTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val controller = (context.applicationContext as JotApplication).controller
    private val store = JotStore.get(context)
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun awaitState(timeout: Long = 60_000, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Timed out; state=${controller.state.value}", condition())
    }

    @Before fun companionReady() {
        val file = File(context.getExternalFilesDir(null), "companion/gemma-4-E2B-it.litertlm")
        assumeTrue("Push the companion model to ${file.path} to run this test.", file.length() == CompanionModel.SIZE)
        main { controller.setCompanion(true) }
        awaitState(300_000) { controller.companion.state.value == CompanionState.Ready }
    }

    @Test fun typedEventIsSuggestedAndTitled() = runBlocking {
        main { controller.newNote() }
        awaitState { controller.state.value.stage == Stage.NOTE && !controller.state.value.busy }
        val noteId = controller.state.value.note!!.id
        try {
            main { controller.updateText("Dentist next Tuesday at 3 pm"); controller.confirm() }
            awaitState { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking }
            assertTrue(controller.state.value.toString(), ActionKind.CALENDAR in controller.state.value.selected)
            main { controller.state.value.selected.filter { it != ActionKind.CALENDAR }.forEach(controller::toggleAction); controller.continueActions() }
            awaitState { controller.state.value.stage == Stage.DETAILS && !controller.state.value.busy }
            val form = controller.state.value.form!!
            assertTrue(form.body, form.body.startsWith("Dentist") && form.body != "Dentist next Tuesday at 3 pm")
            assertTrue(form.toString(), form.date != null && form.time?.hour == 15)
            main { controller.close() }
            awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
        } finally {
            store.deleteNote(noteId)
        }
    }

    @Test fun spokenQuestionIsAnsweredFromNotes() = runBlocking {
        val now = System.currentTimeMillis()
        val fact = NoteRecord("companion-flow-wifi", "The wifi password is maple 42", now, now)
        store.saveNote(fact)
        try {
            main { controller.newNote(voice = true, captureAlreadyRunning = true) }
            awaitState { controller.state.value.stage == Stage.CAPTURE && !controller.state.value.busy }
            val draftId = controller.state.value.note!!.id
            VoiceSignals.emit(VoiceEvent.Utterance("What's the wifi password"))
            awaitState { controller.state.value.stage == Stage.ANSWER && !controller.state.value.thinking }
            val state = controller.state.value
            assertTrue(state.toString(), state.answer.orEmpty().contains("maple", ignoreCase = true))
            assertEquals(listOf(fact.id), state.sources.map { it.note.id })
            assertNull(store.note(draftId))
            main { controller.close() }
            awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
        } finally {
            store.deleteNote(fact.id)
        }
    }
}
