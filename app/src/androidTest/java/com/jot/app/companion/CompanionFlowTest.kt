package com.jot.app.companion

import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.JotApplication
import com.jot.app.capture.VoiceEvent
import com.jot.app.capture.VoiceSignals
import com.jot.app.data.ActionKind
import com.jot.app.data.ActionStatus
import com.jot.app.data.JotStore
import com.jot.app.data.NoteRecord
import com.jot.app.platform.AndroidActions
import com.jot.app.ui.Stage
import kotlinx.coroutines.flow.first
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
    private fun typed(text: String): String {
        main { controller.newNote() }
        awaitState { controller.state.value.stage == Stage.NOTE && !controller.state.value.busy }
        val id = controller.state.value.note!!.id
        main { controller.updateText(text); controller.confirm() }
        return id
    }
    private fun only(kind: ActionKind) = main {
        ActionKind.entries.filter { (it == kind) != (it in controller.state.value.selected) }.forEach(controller::toggleAction)
        controller.continueActions()
    }

    @Before fun companionReady() {
        val file = File(context.getExternalFilesDir(null), "companion/gemma-4-E2B-it.litertlm")
        assumeTrue("Push the companion model to ${file.path} to run this test.", file.length() == CompanionModel.SIZE)
        main { controller.setCompanion(true) }
        awaitState(300_000) { controller.companion.state.value == CompanionState.Ready }
    }

    @Test fun typedEventIsFilledInAndAddedToTheCalendar() = runBlocking {
        val resolver = context.contentResolver
        val account = "Jot test"
        val calendars = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val calendar = resolver.insert(calendars, ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, account)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, account)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.IS_PRIMARY, 1)
        })!!.lastPathSegment!!.toLong()
        val noteId = typed("Dentist next Tuesday at 3 pm")
        try {
            awaitState { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking }
            val state = controller.state.value
            assertTrue(state.toString(), ActionKind.CALENDAR in state.selected)
            val planned = state.plan.getValue(ActionKind.CALENDAR)
            assertTrue(planned.toString(), planned.body.startsWith("Dentist") && planned.body != "Dentist next Tuesday at 3 pm" && planned.time?.hour == 15)
            only(ActionKind.CALENDAR)
            awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            val title = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE), "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendar.toString()), null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            assertEquals(planned.body, title)
            assertEquals(ActionStatus.DONE, store.actions(noteId).single().status)
            assertTrue("A finished action is not a note", store.observeNotes().first().none { it.note.id == noteId })
            assertTrue("The companion still remembers it", store.remembered().any { it.note.id == noteId })
        } finally {
            store.deleteNote(noteId)
            resolver.delete(calendars, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendar.toString()))
        }
    }

    @Test fun typedReminderIsScheduledOnConfirm() = runBlocking {
        instrument.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        val noteId = typed("Remind me to call the plumber in two hours")
        try {
            awaitState { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking }
            assertTrue(ActionKind.REMINDER in controller.state.value.selected)
            only(ActionKind.REMINDER)
            awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            assertEquals(ActionStatus.SCHEDULED, store.actions(noteId).single().status)
            assertTrue("A waiting reminder stays visible", store.observeNotes().first().any { it.note.id == noteId })
        } finally {
            store.actions(noteId).forEach { AndroidActions(context).cancel(it) }
            store.deleteNote(noteId)
        }
    }

    @Test fun spokenNoteIsCleanedBeforeItIsReadBack() {
        main { controller.newNote(voice = true, captureAlreadyRunning = true) }
        awaitState { controller.state.value.stage == Stage.CAPTURE && !controller.state.value.busy }
        VoiceSignals.emit(VoiceEvent.Utterance("text sam that im running ten minutes lake"))
        awaitState { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking }
        val text = controller.state.value.text
        assertTrue(text, text.contains("Sam") && text.contains("late") && !text.contains("lake"))
        main { controller.close() }
        awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
    }

    @Test fun typedQuestionIsAnswered() = runBlocking {
        val now = System.currentTimeMillis()
        val fact = NoteRecord("companion-flow-typed-wifi", "The wifi password is maple 42", now, now)
        store.saveNote(fact)
        try {
            val draftId = typed("What's the wifi password?")
            awaitState { controller.state.value.stage == Stage.ANSWER && !controller.state.value.thinking }
            assertTrue(controller.state.value.answer.orEmpty(), controller.state.value.answer.orEmpty().contains("maple", ignoreCase = true))
            assertNull(store.note(draftId))
            main { controller.close() }
            awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
        } finally {
            store.deleteNote(fact.id)
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
            VoiceSignals.emit(VoiceEvent.Utterance("whats the wifi password"))
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
