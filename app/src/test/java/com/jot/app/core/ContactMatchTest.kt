package com.jot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactMatchTest {
    private val sam = Contact("Sam Lee", "5551")
    private val carter = Contact("Sam Carter", "5552")
    private val mom = Contact("Mom", "5553")
    private val sean = Contact("Sean Murphy", "5554")
    private val book = listOf(sam, carter, mom, sean)

    @Test fun firstNameAloneFindsEveryoneWithIt() = assertEquals(listOf(sam, carter), ContactMatch.find("Sam", book))

    @Test fun fullNameFindsOnePerson() = assertEquals(listOf(carter), ContactMatch.find("sam carter", book))

    @Test fun misheardNamesStillMatch() {
        assertEquals(listOf(mom), ContactMatch.find("mum", book))
        assertEquals(listOf(sean), ContactMatch.find("Shawn", book))
    }

    @Test fun strangersMatchNobody() = assertEquals(emptyList<Contact>(), ContactMatch.find("Priya", book))

    @Test fun takesTheLongestNameOutOfTheSentence() {
        val found = ContactMatch.address("Text Sam Lee that I'm running late.", book)!!
        assertEquals("Sam Lee", found.said)
        assertEquals(listOf(sam), found.matches)
        assertEquals("I'm running late.", ContactMatch.body(found.rest))
    }

    @Test fun findsTheAddresseeInsideASentence() {
        val found = ContactMatch.address("Game night Saturday at 8, tell mom to bring chips", book)!!
        assertEquals(listOf(mom), found.matches)
        assertEquals("Bring chips.", ContactMatch.body(found.rest))
    }

    @Test fun anUnknownNameIsStillTakenOut() {
        val found = ContactMatch.address("tell Priya the keys are under the mat", book)!!
        assertEquals("Priya", found.said)
        assertEquals(emptyList<Contact>(), found.matches)
        assertEquals("The keys are under the mat.", ContactMatch.body(found.rest))
    }

    @Test fun aNoteWithNobodyInItHasNoAddressee() = assertNull(ContactMatch.address("buy milk", book))
}
