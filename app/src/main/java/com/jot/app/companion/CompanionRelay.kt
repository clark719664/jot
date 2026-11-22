package com.jot.app.companion

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.jot.app.BuildConfig
import com.jot.app.data.NoteRecord
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal data class RelayReply(val status: Int, val text: String? = null, val code: String? = null) {
    override fun toString() = "RelayReply(status=$status)"
}

internal data class SignInStart(val browserUrl: String, val pollToken: String, val expiresAt: Long) {
    override fun toString() = "SignInStart(expiresAt=$expiresAt)"
}

internal data class SignInPoll(val status: Int, val token: String? = null) {
    override fun toString() = "SignInPoll(status=$status)"
}

data class CompanionAccount(
    val paid: Boolean,
    val freeCallsRemaining: Int?,
    val billingAvailable: Boolean,
    val canManageBilling: Boolean,
    val renews: Boolean = false,
    val periodEnd: Long? = null,
)

internal data class AccountReply(val status: Int, val details: CompanionAccount? = null)

internal interface Relay {
    suspend fun startSignIn(): SignInStart?
    suspend fun pollSignIn(pollToken: String, receipt: String): SignInPoll
    suspend fun account(token: String): AccountReply
    suspend fun revoke(token: String): Boolean
    suspend fun read(token: String, note: String): RelayReply
    suspend fun answer(token: String, question: String, notes: List<NoteRecord>): RelayReply
}

internal class CompanionRelay(
    private val client: Call.Factory = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) : Relay {
    private val origin = URI(BuildConfig.RELAY_ORIGIN)
    private val urlSafeFragment = Regex("[A-Za-z0-9_-]+")
    private val receiptFormat = Regex("[A-Za-z0-9_-]{43}")
    private val noteErrorCodes = setOf("daily_quota", "rate_limit", "concurrency_limit", "monthly_budget")
    private val billingPostPaths = setOf("billing/subscribe", "billing/renewal")

    override suspend fun startSignIn(): SignInStart? {
        val result = exchange("POST", "v1/auth/start", payload = JsonObject())
        val body = result.json?.takeIf { result.status == 200 } ?: return null
        val browserUrl = body.stringField("browserUrl") ?: return null
        val uri = httpsUri(browserUrl) ?: return null
        if (!uri.host.equals(origin.host, ignoreCase = true) || httpsPort(uri) != httpsPort(origin) ||
            uri.rawPath != "/auth" || uri.rawQuery != null || !urlSafeFragment.matches(uri.rawFragment.orEmpty())) return null
        val pollToken = body.stringField("pollToken")?.takeIf(::validToken) ?: return null
        val expiresAt = body.numberField("expiresAt")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return SignInStart(browserUrl, pollToken, expiresAt)
    }

    override suspend fun pollSignIn(pollToken: String, receipt: String): SignInPoll {
        if (!validToken(pollToken) || !receiptFormat.matches(receipt)) return SignInPoll(400)
        val payload = JsonObject().apply {
            addProperty("pollToken", pollToken)
            addProperty("receipt", receipt)
        }
        val result = exchange("POST", "v1/auth/poll", payload = payload)
        val token = result.json?.stringField("token")?.takeIf { result.status == 200 && validToken(it) }
        return SignInPoll(result.status, token)
    }

    override suspend fun account(token: String): AccountReply {
        val result = exchange("GET", "v1/account", token)
        val details = result.json?.takeIf { result.status == 200 }?.accountDetails()
        return AccountReply(result.status, details)
    }

    private fun JsonObject.accountDetails(): CompanionAccount? {
        val paid = when (stringField("plan")) {
            "paid" -> true
            "free" -> false
            else -> return null
        }
        val remaining = numberField("freeCallsRemaining")?.toIntOrNull()?.takeIf { it >= 0 }
        if (!paid && remaining == null) return null
        return CompanionAccount(
            paid, remaining,
            booleanField("billingAvailable") ?: return null,
            booleanField("canManageBilling") ?: return null,
            booleanField("renews") ?: false,
            numberField("periodEnd")?.toLongOrNull()?.takeIf { it > 0 },
        )
    }

    override suspend fun revoke(token: String): Boolean {
        val result = exchange("DELETE", "v1/account/device", token)
        return result.status == 200 && result.json?.booleanField("revoked") == true
    }

    internal suspend fun billingRequest(path: String, token: String, payload: JsonObject): Pair<Int, JsonObject?> {
        if (path !in billingPostPaths) return 400 to null
        val result = exchange("POST", path, token, payload)
        return result.status to result.json
    }

    override suspend fun read(token: String, note: String): RelayReply {
        if (note.isBlank()) return RelayReply(400)
        if (note.toByteArray().size > 16_384) return RelayReply(413)
        val payload = JsonObject().apply {
            addProperty("operation", "read")
            addProperty("note", note)
        }
        return sendNote(token, payload)
    }

    override suspend fun answer(token: String, question: String, notes: List<NoteRecord>): RelayReply {
        if (question.isBlank()) return RelayReply(400)
        val questionBytes = question.toByteArray().size
        if (questionBytes > 2_048) return RelayReply(413)
        var remaining = 16_384 - questionBytes
        val excerpts = JsonArray()
        val format = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneId.systemDefault())
        for (note in notes.take(8)) {
            val date = format.format(Instant.ofEpochMilli(note.updatedAt))
            val text = note.text.take(750).trim()
            if (text.isEmpty() || note.draft) continue
            val size = date.toByteArray().size + text.toByteArray().size
            if (size > remaining) break
            remaining -= size
            excerpts.add(JsonObject().apply { addProperty("date", date); addProperty("text", text) })
        }
        if (excerpts.size() == 0) return RelayReply(400)
        val payload = JsonObject().apply {
            addProperty("operation", "answer")
            addProperty("question", question)
            add("notes", excerpts)
        }
        return sendNote(token, payload)
    }

    private suspend fun sendNote(token: String, payload: JsonObject): RelayReply {
        val result = exchange("POST", "v1/companion", token, payload)
        return if (result.status in 200..299) {
            RelayReply(result.status, result.json?.stringField("text")?.trim()?.takeIf(String::isNotEmpty))
        } else {
            RelayReply(result.status, code = result.json?.stringField("code")?.takeIf { it in noteErrorCodes })
        }
    }

    private fun validToken(value: String) = value.length in 1..512 && value.all { it.code in 33..126 }

    private fun httpsUri(url: String): URI? = runCatching { URI(url) }.getOrNull()
        ?.takeIf { it.scheme == "https" && it.host != null && it.rawUserInfo == null }

    private fun httpsPort(uri: URI) = if (uri.port == -1) 443 else uri.port

    private fun JsonObject.stringField(name: String) = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(String::isNotEmpty)

    private fun JsonObject.numberField(name: String) = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString

    private fun JsonObject.booleanField(name: String) = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private class HttpResult(val status: Int, val json: JsonObject? = null)

    private suspend fun exchange(method: String, path: String, token: String? = null, payload: JsonObject? = null): HttpResult {
        if (token != null && !validToken(token)) return HttpResult(401)
        val body = payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(BuildConfig.RELAY_ORIGIN + path)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(HttpResult(0))
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = response.use {
                        val json = runCatching {
                            val body = it.body ?: return@runCatching null
                            if (body.source().request(32_769L)) return@runCatching null
                            JsonParser.parseString(body.string()).asJsonObject
                        }.getOrNull()
                        HttpResult(it.code, json)
                    }
                    continuation.resume(reply)
                }
            })
        }
    }
}
