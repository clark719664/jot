package com.jot.app.core

import com.jot.app.data.ActionKind
import com.jot.app.data.NoteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CompanionTextTest {
    private val now = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneId.of("America/New_York"))
    private fun model(vararg kinds: ActionKind, question: Boolean = false, title: String = "", message: String = "") =
        Reading(question, kinds.toSet(), title, message)

    @Test fun parsesConstrainedJsonAndDropsDuplicates() {
        val reading = CompanionText.parse("""{"note": "Dinner, ask Jake.", "question": false, "actions": ["message", "message", "calendar"], "title": " Dinner ", "message": "Hi Jake"}""")!!
        assertEquals("Dinner, ask Jake.", reading.note)
        assertEquals(setOf(ActionKind.CALENDAR, ActionKind.MESSAGE), reading.actions)
        assertEquals("Dinner", reading.title)
        assertEquals("Hi Jake", reading.message)
    }

    @Test fun acceptsNestedActionsInACodeFence() {
        val raw = """```json
{"question": false, "actions": [{"type": "message", "message": "I'm running late"}], "title": "Text Sam", "message": ""}
```"""
        val reading = CompanionText.parse(raw)!!
        assertEquals(setOf(ActionKind.MESSAGE), reading.actions)
        assertEquals("I'm running late", reading.message)
    }

    @Test fun rejectsBrokenJson() = assertNull(CompanionText.parse("calendar please"))

    @Test fun timedActionsNeedATimeOrACue() {
        val reading = CompanionText.settle("Buy milk eggs and bread", model(ActionKind.REMINDER, ActionKind.CALENDAR), now)
        assertEquals(emptySet<ActionKind>(), reading.actions)
    }

    @Test fun messagesNeedSomeoneToTell() {
        val reading = CompanionText.settle("The wifi password is maple 42", model(ActionKind.MESSAGE, message = "Hi, the wifi password is maple 42."), now)
        assertEquals(emptySet<ActionKind>(), reading.actions)
        assertNull(reading.message)
    }

    @Test fun keepsModelSuggestionsThatTheNoteSupports() {
        val reading = CompanionText.settle("Pick up the kids at 3:15 pm", model(ActionKind.REMINDER, title = "Pick up kids"), now)
        assertEquals(setOf(ActionKind.REMINDER), reading.actions)
        assertEquals("Pick up kids", reading.title)
    }

    @Test fun addsCalendarForTimedEventsTheModelMissed() {
        val reading = CompanionText.settle("Team meeting Monday 10 am in the big conference room", model(), now)
        assertEquals(setOf(ActionKind.CALENDAR), reading.actions)
    }

    @Test fun combinesEventAndMessage() {
        val reading = CompanionText.settle(
            "Mom's birthday dinner Saturday at 7 pm, ask Jake to bring the cake",
            model(ActionKind.MESSAGE, title = "Mom's birthday dinner", message = "Jake, can you bring the cake?"), now,
        )
        assertEquals(setOf(ActionKind.CALENDAR, ActionKind.MESSAGE), reading.actions)
        assertEquals("Jake, can you bring the cake?", reading.message)
    }

    @Test fun rulesWorkWithoutTheCompanion() {
        assertEquals(setOf(ActionKind.ALARM), CompanionText.settle("Wake me up at 6:30 am tomorrow", null, now).actions)
        assertEquals(setOf(ActionKind.REMINDER), CompanionText.settle("Remind me to call the plumber in two hours", null, now).actions)
        assertEquals(setOf(ActionKind.MESSAGE), CompanionText.settle("Text Sam that I'm running late", null, now).actions)
        assertFalse(CompanionText.settle("Where did I park", null, now).question)
    }

    @Test fun questionsNeedAQuestionShape() {
        assertTrue(CompanionText.settle("Where did I park", model(), now).question)
        assertTrue(CompanionText.settle("What's the wifi password", model(question = true), now).question)
        assertFalse(CompanionText.settle("Parked on level three row C", model(question = true), now).question)
        assertFalse(CompanionText.settle("Ask Jake if he wants pizza tonight", model(ActionKind.MESSAGE, question = true), now).question)
        assertFalse(CompanionText.settle("What to get Mom for her birthday maybe a scarf or a nice candle", model(), now).question)
    }

    @Test fun findsRelevantNotes() {
        val notes = listOf(
            NoteRecord("1", "The wifi password is maple 42", 1, 3),
            NoteRecord("2", "Parked on level three row C", 1, 2),
            NoteRecord("3", "Buy milk", 1, 1),
            NoteRecord("4", "wifi draft", 1, 4, draft = true),
        )
        assertEquals(listOf("1"), CompanionText.relevant("What's the wifi password", notes).map { it.id })
        assertEquals(listOf("2"), CompanionText.relevant("Where did I park", notes).map { it.id })
        assertEquals(emptyList<String>(), CompanionText.relevant("what is it", notes).map { it.id })
    }

    @Test fun keepsCleanupThatStaysCloseToWhatWasHeard() {
        assertEquals(
            "Text Sam that I'm running ten minutes late.",
            CompanionText.fix("text sam that im running ten minutes lake", "Text Sam that I'm running ten minutes late."),
        )
    }

    @Test fun rejectsCleanupThatRewritesTheNote() {
        assertEquals("buy milk eggs and bread", CompanionText.fix("buy milk eggs and bread", "Remember to pick up groceries from the store after work today."))
        assertEquals("buy milk", CompanionText.fix("buy milk", ""))
        assertEquals("buy milk", CompanionText.fix("buy milk", null))
    }

    @Test fun typedQuestionMarkCountsAsAsking() {
        assertTrue(CompanionText.asks("wifi password?"))
        assertFalse(CompanionText.asks("wifi password is maple 42"))
    }

    @Test fun speaksWhenEachActionHappens() {
        val today = LocalDate.of(2026, 11, 3)
        assertEquals("for 6:30 AM", JotLines.spokenWhen(ActionKind.ALARM, today, LocalTime.of(6, 30), today))
        assertEquals("on Friday at 7 PM", JotLines.spokenWhen(ActionKind.CALENDAR, today.plusDays(3), LocalTime.of(19, 0), today))
        assertEquals("tomorrow at 9 AM", JotLines.spokenWhen(ActionKind.REMINDER, today.plusDays(1), LocalTime.of(9, 0), today))
        assertEquals("on November 20 at 9 AM", JotLines.spokenWhen(ActionKind.REMINDER, today.plusDays(17), LocalTime.of(9, 0), today))
        assertNull(JotLines.spokenWhen(ActionKind.MESSAGE, today, LocalTime.NOON, today))
        assertEquals("Want me to add an alarm for 6:30 AM and a message?", JotLines.offer(setOf(ActionKind.MESSAGE, ActionKind.ALARM)) { if (it == ActionKind.ALARM) "for 6:30 AM" else null })
    }

    @Test fun readsBackLikeAFriend() {
        assertEquals("Got it. Dinner at 7 PM. Sound right?", JotLines.readback("Dinner at 7 PM."))
        assertEquals("Got it. dinner at seven. Sound right?", JotLines.readback("dinner at seven"))
        assertEquals("Done. Alarm set.", JotLines.finished("Alarm set"))
        assertEquals("Okay, it's saved.", JotLines.finished("Note saved"))
    }

    @Test fun alarmsAndRemindersWithATimeNeedNoModel() {
        assertTrue(CompanionText.ready("Go ahead and set an alarm for me for every weekday, for six fifty a M so I get up"))
        assertTrue(CompanionText.ready("Remind me to call the dentist tomorrow at 3 p.m."))
        assertFalse(CompanionText.ready("Set an alarm"))
        assertFalse(CompanionText.ready("Dinner with Sam at 7 PM"))
    }

    @Test fun aTextToSomeoneJotKnowsNeedsNoModel() {
        val book = listOf(Contact("Mia Rossi", "5551"), Contact("Sam Lee", "5552"), Contact("Sam Carter", "5553"))
        assertTrue(CompanionText.ready("Text Mia that I'm late.", book))
        assertFalse(CompanionText.ready("Text Sam that I'm late.", book))
        assertFalse(CompanionText.ready("Text Priya that I'm late.", book))
    }

    @Test fun aLeadingTextIsOnlyAText() = assertEquals(
        setOf(ActionKind.MESSAGE),
        CompanionText.settle("Text Sam that dinner is at 7 PM.", Reading(actions = setOf(ActionKind.CALENDAR, ActionKind.MESSAGE))).actions,
    )

    @Test fun asksWhichContact() {
        assertEquals("Who should it go to?", JotLines.whichOne(emptyList()))
        assertEquals("Which one, Sam Lee or Sam Carter?", JotLines.whichOne(listOf("Sam Lee", "Sam Carter")))
    }

    @Test fun offersActionsInOrder() {
        assertEquals("Want me to add a calendar event and a reminder?", JotLines.offer(setOf(ActionKind.REMINDER, ActionKind.CALENDAR)))
        assertEquals("Want me to add an alarm?", JotLines.offer(setOf(ActionKind.ALARM)))
        assertEquals("Want me to send a message to Sam saying running late?", JotLines.offer(setOf(ActionKind.MESSAGE)) { JotLines.addressed("Sam", "Running late.".replaceFirstChar(Char::lowercase)) })
        assertEquals("", JotLines.offer(emptySet()))
    }
}
