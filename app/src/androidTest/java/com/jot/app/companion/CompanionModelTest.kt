package com.jot.app.companion

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jot.app.data.ActionKind
import com.jot.app.data.NoteRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("relay-model-test", Context.MODE_PRIVATE)
    private val pending = context.getSharedPreferences("relay-model-test-pending", Context.MODE_PRIVATE)
    private val pollToken = "p".repeat(42) + "A"
    private val receipt = "r".repeat(42) + "A"
    private val deviceToken = "d".repeat(42) + "A"
    private val browserUrl = "https://jot.example.test/signup"
    private val plan = CompanionAccount(paid = false, freeCallsRemaining = 5, billingAvailable = false, canManageBilling = false)

    private inner class FakeRelay : Relay {
        val polls = mutableListOf<Pair<String, String>>()
        val notes = mutableListOf<String>()
        val revoked = CompletableDeferred<String>()
        var readNote: suspend () -> RelayReply = { RelayReply(200, """{"actions":["reminder"]}""") }

        override suspend fun startSignIn() = SignInStart(browserUrl, pollToken, System.currentTimeMillis() + 300_000)
        override suspend fun pollSignIn(pollToken: String, receipt: String): SignInPoll {
            polls.add(pollToken to receipt)
            return SignInPoll(200, deviceToken)
        }
        override suspend fun account(token: String): AccountReply {
            assertEquals(deviceToken, token)
            return AccountReply(200, plan)
        }
        override suspend fun revoke(token: String): Boolean = revoked.complete(token)
        override suspend fun read(token: String, note: String): RelayReply {
            assertEquals(deviceToken, token)
            notes.add(note)
            return readNote()
        }
        override suspend fun answer(token: String, question: String, notes: List<NoteRecord>): RelayReply =
            error("This flow should not request an answer")
    }

    @Before @After fun clearTestPreferences() {
        prefs.edit().clear().commit()
        pending.edit().clear().commit()
    }

    private fun model(scope: CoroutineScope, relay: FakeRelay) = CompanionModel(context, scope, relay, prefs, pending)

    private suspend fun openBrowser(model: CompanionModel) {
        val opened = CompletableDeferred<String>()
        model.startSignIn { url -> opened.complete(url) }
        assertEquals(browserUrl, withTimeout(10_000) { opened.await() })
        assertEquals(CompanionState.SigningIn, model.state.value)
    }

    private suspend fun finishSignIn(model: CompanionModel) {
        model.completeSignIn(receipt)
        model.setForeground(true)
        withTimeout(10_000) { model.state.first { it == CompanionState.Ready } }
    }

    @Test fun browserSignInPersistsAnEncryptedCredentialUntilRevoked() = runBlocking {
        val relay = FakeRelay()
        val original = model(this, relay)
        assertEquals(CompanionState.Off, original.state.value)
        assertNull(original.read("private"))
        openBrowser(original)
        assertNull(original.read("still private"))
        assertTrue(relay.notes.isEmpty())
        assertTrue(relay.polls.isEmpty())
        finishSignIn(original)
        assertEquals(listOf(pollToken to receipt), relay.polls)
        assertEquals(plan, withTimeout(10_000) { original.account.first { it != null } })
        assertFalse(prefs.all.isEmpty())
        assertTrue(prefs.all.values.none { it.toString().contains(deviceToken) })
        assertTrue(pending.all.isEmpty())

        val reopened = model(this, relay)
        assertEquals(CompanionState.Ready, reopened.state.value)
        val note = "Remind me to call the plumber"
        assertEquals(setOf(ActionKind.REMINDER), reopened.read(note)?.actions)
        relay.readNote = { RelayReply(401) }
        assertNull(reopened.read(note))
        assertEquals(CompanionState.InvalidToken, reopened.state.value)
        assertNull(reopened.read("keep local"))
        assertEquals(listOf(note, note), relay.notes)
        assertTrue(prefs.all.isEmpty())
        assertEquals(CompanionState.Off, model(this, relay).state.value)
    }

    @Test fun cancellationDiscardsTheBrowserReturn() = runBlocking {
        val relay = FakeRelay()
        val model = model(this, relay)
        openBrowser(model)
        model.cancelSignIn()
        model.completeSignIn(receipt)
        model.setForeground(true)
        yield()
        assertEquals(CompanionState.Off, model.state.value)
        assertTrue(pending.all.isEmpty())
        assertTrue(prefs.all.isEmpty())
        assertTrue(relay.polls.isEmpty())
    }

    @Test fun aPendingBrowserSignInResumesAfterProcessRecreation() = runBlocking {
        val relay = FakeRelay()
        val oldScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            openBrowser(model(oldScope, relay))
            assertFalse(pending.all.isEmpty())
            assertTrue(pending.all.values.none { it.toString().contains(pollToken) })
        } finally {
            oldScope.cancel()
        }
        val restored = model(this, relay)
        assertEquals(CompanionState.SigningIn, restored.state.value)
        finishSignIn(restored)
        assertEquals(listOf(pollToken to receipt), relay.polls)
        assertTrue(pending.all.isEmpty())
    }

    @Test fun disconnectCancelsAnOutstandingReadAndRevokesTheDevice() = runBlocking {
        val relay = FakeRelay()
        val model = model(this, relay)
        openBrowser(model)
        finishSignIn(model)
        val started = CompletableDeferred<Unit>()
        relay.readNote = { started.complete(Unit); awaitCancellation() }
        val request = async { model.read("in flight") }
        withTimeout(10_000) { started.await() }
        model.disconnect()
        assertNull(withTimeout(10_000) { request.await() })
        assertEquals(deviceToken, withTimeout(10_000) { relay.revoked.await() })
        assertEquals(CompanionState.Off, model.state.value)
        assertTrue(prefs.all.isEmpty())
    }
}
