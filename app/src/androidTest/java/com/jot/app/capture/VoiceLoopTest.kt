package com.jot.app.capture

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.JotApplication
import com.jot.app.MainActivity
import com.jot.app.companion.CompanionState
import com.jot.app.ui.Stage
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The whole live path: the real service records, an action is offered, and Jot speaks it back. */
@RunWith(AndroidJUnit4::class)
class VoiceLoopTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val controller = (instrument.targetContext.applicationContext as JotApplication).controller
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(what: String, timeout: Long = 90_000, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Timed out waiting for $what; state=${controller.state.value} voice=${VoiceSignals.state.value}", condition())
    }

    @Test fun spokenNoteIsReadBackThroughTheRealService() = readBack { main { controller.newNote(voice = true) } }

    @Test fun heyJotNoteIsReadBackThroughTheRealService() = readBack {
        VoiceService.send(instrument.targetContext, VoiceService.START_WAKE)
        await("listening") { VoiceSignals.state.value.listening }
        VoiceSignals.emit(VoiceEvent.Wake)
        await("a new note") { controller.state.value.stage == Stage.CAPTURE }
    }

    private fun readBack(begin: () -> Unit) {
        val context = instrument.targetContext
        assumeTrue("Pass -e liveCompanion 1 on a connected device, or disconnect for the offline test.",
            controller.companion.state.value != CompanionState.Ready || InstrumentationRegistry.getArguments().getString("liveCompanion") == "1")
        ActivityScenario.launch(MainActivity::class.java).use {
            begin()
            await("recording") { VoiceSignals.state.value.capturing || VoiceSignals.state.value.listening }
            VoiceSignals.emit(VoiceEvent.Utterance("text sam that im running ten minutes late"))
            await("the offer", 120_000) { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking }
            await("Jot to speak") { VoiceSignals.state.value.speaking }
            Log.i("JotVoiceLoop", "speaking: ${controller.state.value.text}")
            assertTrue(controller.state.value.text, controller.state.value.text.contains("late", ignoreCase = true))
            await("Jot to finish and listen") { !VoiceSignals.state.value.speaking && VoiceSignals.state.value.capturing }
            VoiceSignals.emit(VoiceEvent.Utterance("nope"))
            await("Jot to ask again") { controller.state.value.stage == Stage.CAPTURE }
            VoiceSignals.emit(VoiceEvent.Utterance("buy milk"))
            await("the new note") { controller.state.value.stage == Stage.NOTE && !controller.state.value.thinking }
            assertNull(VoiceSignals.state.value.toString(), VoiceSignals.state.value.error)
            assertNull(controller.state.value.toString(), controller.state.value.error)
            main { controller.close() }
            await("home") { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            VoiceService.send(context, VoiceService.STOP)
        }
    }
}
