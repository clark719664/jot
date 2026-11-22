package com.jot.app.companion

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSignInTest {
    private val pollToken = "P".repeat(43)
    private val receipt = "R".repeat(43)

    private fun store(name: String): DeviceTokenStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return DeviceTokenStore(context, context.getSharedPreferences(name, Context.MODE_PRIVATE))
    }

    private fun relay(poll: suspend (String, String) -> SignInPoll): Relay {
        val offline = OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build()
        return object : Relay by CompanionRelay(offline) {
            override suspend fun startSignIn() =
                SignInStart("https://example.test/auth#ticket", pollToken, System.currentTimeMillis() + 60_000)

            override suspend fun pollSignIn(pollToken: String, receipt: String) = poll(pollToken, receipt)
        }
    }

    @Test fun processRestartResumesOnlyWithTheStoredReceiptAndPollToken() = runBlocking {
        val saved = store("browser-sign-in-restart")
        saved.clear()
        val before = CoroutineScope(coroutineContext + Job())
        val after = CoroutineScope(coroutineContext + Job())
        try {
            val requests = mutableListOf<Pair<String, String>>()
            val server = relay { poll, returned ->
                requests += poll to returned
                SignInPoll(200, "test-device")
            }
            val opened = CompletableDeferred<Unit>()
            val first = BrowserSignIn(before, server, saved, {}, { error("Unexpected redemption") })
            first.start { opened.complete(Unit); true }
            withTimeout(3_000) { opened.await() }
            first.returned(receipt)
            before.cancel()
            assertTrue(requests.isEmpty())

            val accepted = CompletableDeferred<String>()
            val restored = BrowserSignIn(after, server, saved, {}, { accepted.complete(it) })
            assertTrue(restored.pending)
            restored.setForeground(true)
            assertEquals("test-device", withTimeout(3_000) { accepted.await() })
            assertEquals(listOf(pollToken to receipt), requests)
            assertNull(saved.read())
            assertFalse(restored.pending)
        } finally {
            before.cancel()
            after.cancel()
            saved.clear()
        }
    }

    @Test fun aGenuineReturnArrivingDuringAnUnrelatedExchangeCanStillComplete() = runBlocking {
        val saved = store("browser-sign-in-wrong-return")
        saved.clear()
        val worker = CoroutineScope(coroutineContext + Job())
        try {
            val wrong = "W".repeat(43)
            val inFlight = CompletableDeferred<Unit>()
            val respond = CompletableDeferred<Unit>()
            val server = relay { poll, returned ->
                assertEquals(pollToken, poll)
                if (returned == wrong) {
                    inFlight.complete(Unit)
                    respond.await()
                    SignInPoll(410)
                } else {
                    assertEquals(receipt, returned)
                    SignInPoll(200, "test-device")
                }
            }
            val opened = CompletableDeferred<Unit>()
            val accepted = CompletableDeferred<String>()
            val login = BrowserSignIn(worker, server, saved, {}, { accepted.complete(it) })
            login.setForeground(true)
            login.start { opened.complete(Unit); true }
            withTimeout(3_000) { opened.await() }
            login.returned(wrong)
            withTimeout(3_000) { inFlight.await() }
            login.returned(receipt)
            respond.complete(Unit)
            assertEquals("test-device", withTimeout(3_000) { accepted.await() })
            assertNull(saved.read())
        } finally {
            worker.cancel()
            saved.clear()
        }
    }
}
