package com.jot.app.core
import com.jot.app.data.ActionKind
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ConversationCommandsTest {
    @Test fun multipleActionsStayDistinct() { assertEquals(setOf(ActionKind.CALENDAR, ActionKind.REMINDER), ConversationCommands.actions("calendar and reminder")) }
    @Test fun unknownSpeechDoesNotSelectAnAction() { assertNull(ConversationCommands.actions("my calendar is a mess")) }
    @Test fun negativeConfirmationCannotSave() { assertEquals(ReviewCommand.CANCEL, ConversationCommands.review("don't save")); assertEquals(ReviewCommand.UNKNOWN, ConversationCommands.review("maybe yes later")) }
    @Test fun changeItIsRecognized() { assertEquals(ReviewCommand.CHANGE, ConversationCommands.review("change it")) }
    @Test fun casualYesCounts() { for (reply in listOf("yeah", "yep", "sure", "sounds good", "that's right", "yeah that's right", "okay")) assertEquals(reply, ReviewCommand.CONFIRM, ConversationCommands.review(reply)) }
    @Test fun casualNoIsNotCancel() { for (reply in listOf("no", "nope", "nah")) assertEquals(reply, ReviewCommand.NO, ConversationCommands.review(reply)); assertEquals(ReviewCommand.CANCEL, ConversationCommands.review("never mind")) }
    @Test fun yesWithANegativeIsNotConfirm() { assertEquals(ReviewCommand.UNKNOWN, ConversationCommands.review("yes but not friday")) }
    @Test fun correctionDropsTheLeadingNo() { assertEquals("dinner with Ana", ConversationCommands.correction("no, dinner with Ana")); assertNull(ConversationCommands.correction("no")) }
    @Test fun doneSelectsNoAction() { assertEquals(emptySet<ActionKind>(), ConversationCommands.actions("just the note")) }
    @Test fun springGapIsRejected() { assertTrue(runCatching { ScheduleTimes.resolve(LocalDate.of(2027,3,14),LocalTime.of(2,30),ZoneId.of("America/New_York")) }.isFailure) }
    @Test fun fallOverlapIsRejected() { assertTrue(runCatching { ScheduleTimes.resolve(LocalDate.of(2027,11,7),LocalTime.of(1,30),ZoneId.of("America/New_York")) }.isFailure) }
    @Test fun regularTimeUsesCorrectOffset() { assertEquals(Instant.parse("2027-03-14T07:30:00Z").toEpochMilli(),ScheduleTimes.resolve(LocalDate.of(2027,3,14),LocalTime.of(3,30),ZoneId.of("America/New_York"))) }
}
