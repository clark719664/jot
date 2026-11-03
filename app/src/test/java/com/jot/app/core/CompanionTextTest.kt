package com.jot.app.core

import com.jot.app.data.ActionKind
import com.jot.app.data.NoteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CompanionTextTest {
    private val now = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneId.of("America/New_York"))
    private fun model(vararg kinds: ActionKind, question: Boolean = false, title: String = "", message: String = "") =
        Reading(question, kinds.toSet(), title, message)

    @Test fun parsesConstrainedJsonAndDropsDuplicates() {
        val reading = CompanionText.parse("""{"question": false, "actions": ["message", "message", "calendar"], "title": " Dinner ", "message": "Hi Jake"}""")!!
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

    @Test fun offersActionsInOrder() {
        assertEquals("Add a calendar event and a reminder?", CompanionText.offer(setOf(ActionKind.REMINDER, ActionKind.CALENDAR)))
        assertEquals("Add an alarm?", CompanionText.offer(setOf(ActionKind.ALARM)))
        assertEquals("", CompanionText.offer(emptySet()))
    }
}
