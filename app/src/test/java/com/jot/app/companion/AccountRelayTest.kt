package com.jot.app.companion

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jot.app.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRelayTest {
    private val ticket = "a".repeat(43)
    private val pollToken = "b".repeat(43)
    private val deviceToken = "c".repeat(43)
    private val receipt = "d".repeat(43)
    private val signInUrl = "${BuildConfig.RELAY_ORIGIN}auth#$ticket"

    private class Exchange(code: Int, json: String) {
        lateinit var request: Request
        val contacted: Boolean get() = this::request.isInitialized
        val relay = CompanionRelay(OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(json.toResponseBody()).build()
        }.build())
    }

    private fun startReply(url: String) = JsonObject().apply {
        addProperty("browserUrl", url)
        addProperty("pollToken", pollToken)
        addProperty("expiresAt", System.currentTimeMillis() + 300_000)
    }.toString()

    private fun Request.json(): JsonObject {
        val buffer = Buffer()
        checkNotNull(body).writeTo(buffer)
        return JsonParser.parseString(buffer.readUtf8()).asJsonObject
    }

    @Test fun startsSignInAndPollsWithoutPuttingCredentialsInUrls() = runBlocking {
        val start = Exchange(200, startReply(signInUrl))
        val flow = checkNotNull(start.relay.startSignIn())
        assertEquals(signInUrl, flow.browserUrl)
        assertEquals(pollToken, flow.pollToken)
        assertTrue(flow.expiresAt > 0)
        assertFalse(flow.toString().contains(ticket))
        assertFalse(flow.toString().contains(pollToken))
        assertEquals("POST", start.request.method)
        assertEquals("/v1/auth/start", start.request.url.encodedPath)
        assertNull(start.request.header("Authorization"))
        assertTrue(start.request.json().keySet().isEmpty())

        val pending = Exchange(202, """{"pending":true}""")
        val waiting = pending.relay.pollSignIn(pollToken, receipt)
        assertEquals(202, waiting.status)
        assertNull(waiting.token)
        assertEquals("POST", pending.request.method)
        assertEquals("/v1/auth/poll", pending.request.url.encodedPath)
        assertNull(pending.request.url.query)
        assertNull(pending.request.header("Authorization"))
        val payload = pending.request.json()
        assertEquals(setOf("pollToken", "receipt"), payload.keySet())
        assertEquals(pollToken, payload["pollToken"].asString)
        assertEquals(receipt, payload["receipt"].asString)

        val complete = Exchange(200, """{"token":"$deviceToken","id":"fixture"}""").relay.pollSignIn(pollToken, receipt)
        assertEquals(200, complete.status)
        assertEquals(deviceToken, complete.token)
        assertFalse(complete.toString().contains(deviceToken))
        val expired = Exchange(410, "{}").relay.pollSignIn(pollToken, receipt)
        assertEquals(410, expired.status)
        assertNull(expired.token)
        val noReceipt = Exchange(200, """{"token":"$deviceToken","id":"fixture"}""")
        assertNull(noReceipt.relay.pollSignIn(pollToken, "").token)
        assertFalse(noReceipt.contacted)
    }

    @Test fun rejectsSignInLinksOutsideTheExactAccountPage() = runBlocking {
        val invalid = listOf(
            "https://unrelated.example/auth#$ticket",
            "${BuildConfig.RELAY_ORIGIN}other#$ticket",
            "${BuildConfig.RELAY_ORIGIN}auth?ticket=$ticket#$ticket",
            "${BuildConfig.RELAY_ORIGIN}auth",
            signInUrl.replace("https://", "https://user@"),
            signInUrl.replace("https://", "http://"),
        )
        for (url in invalid) assertNull(Exchange(200, startReply(url)).relay.startSignIn())
    }

    @Test fun fetchesAccountAndRevokesOnlyTheAuthenticatedDevice() = runBlocking {
        val usage = Exchange(200, """{"plan":"free","freeCallsRemaining":4,"billingAvailable":true,"canManageBilling":false,"deviceId":"fixture"}""")
        val usageReply = usage.relay.account(deviceToken)
        assertEquals(200, usageReply.status)
        val account = checkNotNull(usageReply.details)
        assertFalse(account.paid)
        assertEquals(4, account.freeCallsRemaining)
        assertTrue(account.billingAvailable)
        assertFalse(account.canManageBilling)
        assertEquals("GET", usage.request.method)
        assertEquals("/v1/account", usage.request.url.encodedPath)
        assertEquals("Bearer $deviceToken", usage.request.header("Authorization"))
        val subscriber = checkNotNull(Exchange(200, """{"plan":"paid","freeCallsRemaining":null,"billingAvailable":true,"canManageBilling":true,"renews":true,"periodEnd":1900000000,"deviceId":"fixture"}""").relay.account(deviceToken).details)
        assertTrue(subscriber.paid)
        assertNull(subscriber.freeCallsRemaining)
        assertTrue(subscriber.canManageBilling)
        assertTrue(subscriber.renews)
        assertEquals(1_900_000_000L, checkNotNull(subscriber.periodEnd))
        assertEquals(AccountReply(200), Exchange(200, """{"plan":"unknown"}""").relay.account(deviceToken))
        assertEquals(AccountReply(401), Exchange(401, "{}").relay.account(deviceToken))

        val revoke = Exchange(200, """{"revoked":true}""")
        assertTrue(revoke.relay.revoke(deviceToken))
        assertEquals("DELETE", revoke.request.method)
        assertEquals("/v1/account/device", revoke.request.url.encodedPath)
        assertEquals("Bearer $deviceToken", revoke.request.header("Authorization"))
    }

    @Test fun nativeBillingUsesItsAuthenticatedRoutes() = runBlocking {
        val payload = JsonObject().apply { addProperty("renews", false) }
        val renewal = Exchange(200, """{"renews":false,"periodEnd":1900000000}""")
        val (status, result) = renewal.relay.billingRequest("billing/renewal", deviceToken, payload)
        assertEquals(200, status)
        assertFalse(checkNotNull(result)["renews"].asBoolean)
        assertEquals("/billing/renewal", renewal.request.url.encodedPath)
        assertEquals("Bearer $deviceToken", renewal.request.header("Authorization"))
        assertEquals(payload, renewal.request.json())
        val signup = Exchange(503, "{}")
        assertEquals(503, signup.relay.billingRequest("billing/subscribe", deviceToken, JsonObject()).first)
        assertEquals("POST", signup.request.method)
        val blocked = Exchange(200, "{}")
        assertEquals(400, blocked.relay.billingRequest("v1/account", deviceToken, payload).first)
        assertFalse(blocked.contacted)
    }

}
