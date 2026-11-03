package com.jot.app

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.capture.VoiceEvent
import com.jot.app.capture.VoiceSignals
import com.jot.app.data.ActionKind
import com.jot.app.data.JotStore
import com.jot.app.data.NoteRecord
import com.jot.app.ui.Stage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Captures each screen into the app's external `screens` folder. Run with `-e screens 1`. */
@RunWith(AndroidJUnit4::class)
class Screenshots {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val controller = (context.applicationContext as JotApplication).controller
    private val store = JotStore.get(context)
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun awaitState(condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 60_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Timed out; state=${controller.state.value}", condition())
    }
    private fun shot(name: String) {
        Thread.sleep(900)
        instrument.waitForIdleSync()
        val folder = File(context.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { instrument.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun typed(text: String) {
        main { controller.newNote() }
        awaitState { controller.state.value.stage == Stage.NOTE && !controller.state.value.busy }
        main { controller.updateText(text) }
    }

    @Test fun capture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screens") != null)
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val seeded = listOf(
            "Pasta, garlic, two lemons",
            "The wifi password is maple 42",
            "Parked on level three, row C",
            "Ideas for Mom's birthday: a scarf or the blue candle",
        ).mapIndexed { index, text -> NoteRecord("screens-$index", text, now - index * day, now - index * day) }
        seeded.forEach { store.saveNote(it) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                shot("1-home")
                typed("Dinner with Ana Friday at 7 pm, ask Jake to bring the cake")
                shot("2-note")
                main { controller.confirm() }
                awaitState { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking }
                shot("3-actions")
                main { controller.close() }
                awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }

                typed("Text Sam that I'm running ten minutes late")
                main { controller.confirm() }
                awaitState { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking }
                main { if (ActionKind.MESSAGE !in controller.state.value.selected) controller.toggleAction(ActionKind.MESSAGE) }
                shot("5-message")
                main { controller.close() }
                awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }

                main { controller.newNote(voice = true, captureAlreadyRunning = true) }
                awaitState { controller.state.value.stage == Stage.CAPTURE && !controller.state.value.busy }
                VoiceSignals.emit(VoiceEvent.Utterance("whats the wifi password"))
                awaitState { controller.state.value.stage == Stage.ANSWER && !controller.state.value.thinking }
                shot("6-answer")
                main { controller.close() }
                awaitState { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            }
        } finally {
            store.observeNotesSnapshot().filter { it.text.startsWith("Dinner with Ana") || it.text.startsWith("Text Sam") }.forEach { store.deleteNote(it.id) }
            seeded.forEach { store.deleteNote(it.id) }
        }
    }

    private suspend fun JotStore.observeNotesSnapshot() = observeNotes().first().map { it.note }
}
