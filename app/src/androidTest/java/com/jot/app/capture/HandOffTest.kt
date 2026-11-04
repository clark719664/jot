package com.jot.app.capture

import android.app.Instrumentation
import android.content.ContentProviderOperation
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.JotApplication
import com.jot.app.MainActivity
import com.jot.app.companion.CompanionState
import com.jot.app.data.ActionKind
import com.jot.app.ui.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spoken requests go straight to the action, and Jot lands in the app that holds the result. */
@RunWith(AndroidJUnit4::class)
class HandOffTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val controller = (context.applicationContext as JotApplication).controller
    private val canSend get() = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val added = mutableListOf<Long>()
    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(what: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Timed out waiting for $what; state=${controller.state.value} voice=${VoiceSignals.state.value}", condition())
    }
    private fun watch(action: String, scheme: String? = null, type: String? = null) = Instrumentation.ActivityMonitor(
        IntentFilter(action).apply { scheme?.let(::addDataScheme); type?.let(::addDataType) }, Instrumentation.ActivityResult(0, null), true,
    ).also { instrument.addMonitor(it); monitors += it }

    private fun addContact(name: String, number: String) {
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, number).withValue(Phone.TYPE, Phone.TYPE_MOBILE).build(),
        )
        added += context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri!!.lastPathSegment!!.toLong()
    }

    @Before fun people() {
        instrument.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_CONTACTS, android.Manifest.permission.READ_CONTACTS)
        addContact("Sam Lee", "5550101")
        addContact("Sam Carter", "5550102")
        addContact("Mia Rossi", "5550103")
        instrument.uiAutomation.dropShellPermissionIdentity()
    }

    @After fun cleanUp() {
        monitors.forEach(instrument::removeMonitor)
        main { controller.setOpenWhenDone(true) }
        instrument.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_CONTACTS)
        added.forEach { context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts._ID} = ?", arrayOf(it.toString())) }
        instrument.uiAutomation.dropShellPermissionIdentity()
        main { controller.close() }
        await("home") { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
        VoiceService.send(context, VoiceService.STOP)
        await("the voice to stop") { VoiceSignals.state.value.let { !it.capturing && !it.speaking } }
        Thread.sleep(2_000)
    }

    private fun say(sentence: String, block: () -> Unit) = ActivityScenario.launch(MainActivity::class.java).use {
        main { controller.newNote(voice = true) }
        await("recording") { VoiceSignals.state.value.capturing }
        VoiceSignals.emit(VoiceEvent.Utterance(sentence))
        block()
    }
    private fun offered(timeout: Long = 90_000) { await("the offer", timeout) { controller.state.value.stage == Stage.ACTIONS && !controller.state.value.thinking } }
    private fun answer(words: String) {
        await("Jot to speak") { VoiceSignals.state.value.speaking }
        await("Jot to finish and listen") { !VoiceSignals.state.value.speaking && VoiceSignals.state.value.capturing }
        VoiceSignals.emit(VoiceEvent.Utterance(words))
    }

    @Test fun anAlarmIsOfferedWithoutWaitingAndOpensInClock() {
        val set = watch(AlarmClock.ACTION_SET_ALARM)
        val shown = watch(AlarmClock.ACTION_SHOW_ALARMS)
        say("Go ahead and set an alarm for me for every weekday, for six fifty a M so I get up in the morning to take the kids to the bus stop.") {
            offered(timeout = 3_000)
            assertEquals(setOf(ActionKind.ALARM), controller.state.value.selected)
            assertEquals(java.time.LocalTime.of(6, 50), controller.state.value.plan[ActionKind.ALARM]?.time)
            answer("Yes.")
            await("Clock") { shown.hits == 1 }
            assertEquals(1, set.hits)
            await("home") { controller.state.value.stage == Stage.HOME }
            assertEquals("Alarm set", controller.state.value.message)
        }
    }

    @Test fun uncheckedStaysInJotAndIsRemembered() {
        val set = watch(AlarmClock.ACTION_SET_ALARM)
        val shown = watch(AlarmClock.ACTION_SHOW_ALARMS)
        say("Set an alarm for 7 AM.") {
            offered(timeout = 3_000)
            compose.onNodeWithText("Open when done").performClick()
            await("the choice") { !controller.state.value.openWhenDone }
            answer("Yes.")
            await("the alarm") { set.hits == 1 }
            await("home") { controller.state.value.stage == Stage.HOME }
            Thread.sleep(1_000)
            assertEquals(0, shown.hits)
        }
        say("Set an alarm for 8 AM.") {
            offered(timeout = 3_000)
            assertEquals(false, controller.state.value.openWhenDone)
        }
    }

    @Test fun closingASpokenOfferLeavesNoNote() {
        say("Set an alarm for 9 AM.") {
            offered(timeout = 3_000)
            main { controller.close() }
            await("home") { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            await("no note") { controller.notes.value.none { it.note.text.contains("9 AM") } }
        }
    }

    @Test fun closingWhileThinkingLeavesNothingToSayLater() {
        assumeTrue(controller.companion.state.value == CompanionState.Ready)
        say("I need to pick up milk and eggs.") {
            await("the reading") { controller.state.value.thinking }
            main { controller.close() }
            await("home") { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            val end = android.os.SystemClock.elapsedRealtime() + 14_000
            while (android.os.SystemClock.elapsedRealtime() < end) {
                assertEquals(false, VoiceSignals.state.value.speaking)
                Thread.sleep(50)
            }
        }
    }

    @Test fun aMessageGoesToTheContactAfterYes() {
        assumeTrue(canSend)
        val thread = watch(Intent.ACTION_SENDTO, "smsto")
        say("Text Mia that I'm running ten minutes late.") {
            offered(timeout = 3_000)
            val form = controller.state.value.plan.getValue(ActionKind.MESSAGE)
            Log.i("JotHandOff", "offer to ${form.recipient}: ${form.body}")
            assertEquals("Mia Rossi", form.recipient)
            assertEquals("I'm running ten minutes late.", form.body)
            answer("Yes.")
            await("the conversation") { thread.hits == 1 }
            await("home") { controller.state.value.stage == Stage.HOME }
            assertEquals("Message sent", controller.state.value.message)
        }
    }

    @Test fun jotAsksWhichContactWhenTwoFit() {
        assumeTrue(canSend)
        val thread = watch(Intent.ACTION_SENDTO, "smsto")
        say("Text Sam that dinner is at seven.") {
            offered()
            assertEquals(setOf(ActionKind.MESSAGE), controller.state.value.selected)
            assertEquals("", controller.state.value.plan.getValue(ActionKind.MESSAGE).recipient)
            assertEquals("Dinner is at seven.", controller.state.value.plan.getValue(ActionKind.MESSAGE).body)
            answer("Yes.")
            await("the question") { controller.state.value.stage == Stage.DETAILS }
            answer("Sam Carter.")
            await("the choice") { controller.state.value.form?.recipient == "Sam Carter" }
            answer("Yes.")
            await("the conversation") { thread.hits == 1 }
        }
    }

    @Test fun aNameWithNoWordsAsksForThem() {
        assumeTrue(canSend)
        val thread = watch(Intent.ACTION_SENDTO, "smsto")
        say("Text Mia.") {
            offered(timeout = 3_000)
            assertEquals("", controller.state.value.plan.getValue(ActionKind.MESSAGE).body)
            answer("Yes.")
            await("the question") { controller.state.value.stage == Stage.DETAILS }
            answer("See you at noon.")
            await("the words") { controller.state.value.form?.body == "See you at noon." }
            answer("Yes.")
            await("the conversation") { thread.hits == 1 }
        }
    }

    @Test fun aMessageOpensReadyToSendWhenSendingIsNotAllowed() {
        assumeFalse(canSend)
        val compose = watch(Intent.ACTION_SENDTO, "smsto")
        say("Text Mia that I'm running ten minutes late.") {
            offered()
            answer("Yes.")
            await("Messages") { compose.hits == 1 }
            assertEquals("Message ready", controller.state.value.message)
        }
    }

    @Test fun noMeansHearItAgainAndCancelLeavesNothing() {
        say("Set an alarm for 7 AM.") {
            offered(timeout = 3_000)
            answer("No.")
            await("Jot to ask again") { controller.state.value.stage == Stage.CAPTURE }
            VoiceSignals.emit(VoiceEvent.Utterance("Set an alarm for 8 AM."))
            offered(timeout = 3_000)
            assertEquals(java.time.LocalTime.of(8, 0), controller.state.value.plan[ActionKind.ALARM]?.time)
            VoiceSignals.emit(VoiceEvent.Utterance("Cancel."))
            await("home") { controller.state.value.stage == Stage.HOME && !controller.state.value.busy }
            await("no note") { controller.notes.value.none { it.note.text.contains("alarm") } }
        }
    }

    @Test fun aNoteWithoutAnActionStillReadsBack() {
        say("I need to pick up milk and eggs.") {
            await("the note", 90_000) { controller.state.value.stage == Stage.NOTE && !controller.state.value.thinking }
            await("Jot to speak") { VoiceSignals.state.value.speaking }
        }
    }
}
