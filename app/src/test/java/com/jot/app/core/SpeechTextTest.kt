package com.jot.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test fun dropsHesitations() {
        assertEquals("I need to pick up milk and eggs on the way home from work.", SpeechText.tidy("I need to pick up, uh, milk and eggs, um, on the way home from work."))
        assertEquals("The story was good.", SpeechText.tidy("Uh, the story was good."))
        assertEquals("Remind me to call the dentist tomorrow at 3 p.m.", SpeechText.tidy("Remind me to, uh, call the dentist tomorrow at, um, 3 p.m."))
    }

    @Test fun aPauseIsNotTheEndOfASentence() = assertEquals(
        "Go ahead and set an alarm for me for every weekday for six fifty a M so I get up.",
        SpeechText.tidy("Go ahead and set an alarm for me for every. Ah, weekday, um, for six fifty a M. Ah, so I get up."),
    )

    @Test fun nothingButHesitationIsNothing() {
        assertEquals("", SpeechText.tidy("Um."))
        assertEquals("", SpeechText.tidy("Uh, um."))
    }

    @Test fun leavesRealWordsAlone() {
        assertEquals("Uh-huh, ask Herman.", SpeechText.tidy("Uh-huh, ask Herman."))
        assertEquals("Hummus and umbrellas.", SpeechText.tidy("Hummus and umbrellas."))
    }
}
