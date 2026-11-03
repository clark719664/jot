package com.jot.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftRecoveryTest {
    @Test
    fun newerBlankDraftDoesNotHideUnfinishedText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = JotStore.get(context)
        val olderDraft = NoteRecord(
            UUID.randomUUID().toString(), "Pick up the prescription", 1, Long.MAX_VALUE - 1, draft = true,
        )
        val blankDraft = NoteRecord(
            UUID.randomUUID().toString(), "\t\n \u00a0", 2, Long.MAX_VALUE, draft = true,
        )
        val confirmed = NoteRecord(UUID.randomUUID().toString(), "Saved note", 3, Long.MAX_VALUE)
        val testIds = setOf(olderDraft.id, blankDraft.id, confirmed.id)

        try {
            store.saveNote(olderDraft)
            store.saveNote(blankDraft)
            store.saveNote(confirmed)

            assertEquals(olderDraft, store.draft())
            val visible = store.observeNotes().first().map { it.note }.filter { it.id in testIds }
            assertEquals(listOf(confirmed, olderDraft), visible)
        } finally {
            for (id in testIds) store.deleteNote(id)
        }
    }
}
