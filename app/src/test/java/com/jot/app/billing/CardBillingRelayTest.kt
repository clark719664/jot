package com.jot.app.billing

import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CardBillingRelayTest {
    private fun invoice(key: String = "pk_test_example", secret: String = "pi_example_secret_example") =
        JsonObject().apply {
            addProperty("publishableKey", key)
            addProperty("paymentIntentClientSecret", secret)
        }

    @Test fun purchaseAcceptsOnlyAnInvoiceIntentFromTheAuthenticatedSubscribeRoute() = runTest {
        val relay = CardBillingRelay { path, token, payload ->
            assertEquals("billing/subscribe", path)
            assertEquals("device", token)
            assertEquals(JsonObject(), payload)
            200 to invoice()
        }
        val result = relay.purchase("device") as PurchaseReply.Invoice
        assertEquals("pk_test_example", result.publishableKey)
        assertEquals("pi_example_secret_example", result.paymentIntentClientSecret)
        assertFalse(result.toString().contains("pi_example"))
    }

    @Test fun malformedKeysHostedLinksAndSetupIntentSecretsNeverReachTheSheet() = runTest {
        for (body in listOf(
            invoice(key = "sk_test_example"),
            invoice(secret = "https://checkout.stripe.com/example"),
            invoice(secret = "seti_example_secret_example"),
            invoice(secret = "pi_example_secret_"),
            invoice().apply { remove("publishableKey") },
            invoice().apply { addProperty("paymentIntentClientSecret", false) },
        )) {
            assertSame(PurchaseReply.Failed, CardBillingRelay { _, _, _ -> 200 to body }.purchase("device"))
        }
    }

    @Test fun onlyConflictTreatsPurchaseAsAwaitingStatus() = runTest {
        for (status in listOf(0, 401, 403, 503)) {
            assertSame(PurchaseReply.Failed, CardBillingRelay { _, _, _ -> status to invoice() }.purchase("device"))
        }
        assertSame(PurchaseReply.Pending, CardBillingRelay { _, _, _ -> 409 to null }.purchase("device"))
    }

    @Test fun renewalSetsTheDesiredStateAndRequiresAnExactBooleanAcknowledgment() = runTest {
        for (renews in listOf(false, true)) {
            val relay = CardBillingRelay { path, token, payload ->
                assertEquals("billing/renewal", path)
                assertEquals("device", token)
                assertEquals(JsonObject().apply { addProperty("renews", renews) }, payload)
                200 to JsonObject().apply { addProperty("renews", renews) }
            }
            assertTrue(relay.setRenewal("device", renews))
            assertFalse(CardBillingRelay { _, _, _ -> 200 to JsonObject().apply {
                addProperty("renews", !renews)
            } }.setRenewal("device", renews))
            assertFalse(CardBillingRelay { _, _, _ -> 200 to JsonObject().apply {
                addProperty("renews", renews.toString())
            } }.setRenewal("device", renews))
            assertFalse(CardBillingRelay { _, _, _ -> 503 to null }.setRenewal("device", renews))
        }
    }
}
