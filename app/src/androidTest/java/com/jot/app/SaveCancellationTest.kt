package com.jot.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.data.*
import com.jot.app.platform.AndroidActions
import com.jot.app.ui.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class SaveCancellationTest {
    @Test fun closingDuringReminderSaveCannotRevertScheduledState() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val controller = (context.applicationContext as JotApplication).controller
        val store = JotStore.get(context)
        fun main(block: () -> Unit) = instrument.runOnMainSync(block)
        fun awaitState(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 15000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20)
            assertTrue("Timed out; state=${controller.state.value}", condition())
        }
        main { controller.newNote() }
        awaitState { controller.state.value.stage == Stage.NOTE && !controller.state.value.busy }
        val noteId = controller.state.value.note!!.id
        try {
            main { controller.updateText("Verification reminder"); controller.confirm() }
            awaitState { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.busy && !controller.state.value.thinking }
            main {
                if (ActionKind.REMINDER !in controller.state.value.selected) controller.toggleAction(ActionKind.REMINDER)
                controller.continueActions()
            }
            awaitState { controller.state.value.stage == Stage.DETAILS && !controller.state.value.busy }
            val whenDue = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0)
            main {
                controller.updateForm(controller.state.value.form!!.copy(date = whenDue.toLocalDate(), time = whenDue.toLocalTime()))
                controller.saveAction(approximate = true)
                controller.close()
            }
            awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            val saved = store.actions(noteId).single()
            assertEquals(ActionStatus.SCHEDULED, saved.status)
            assertNotNull(store.note(noteId))
        } finally {
            store.actions(noteId).forEach { AndroidActions(context).cancel(it) }
            store.deleteNote(noteId)
        }
    }
}
