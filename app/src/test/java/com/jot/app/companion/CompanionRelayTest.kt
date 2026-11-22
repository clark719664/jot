package com.jot.app.companion

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jot.app.data.NoteRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRelayTest {
    private fun relay(code: Int, body: String, capture: (Request) -> Unit = {}): CompanionRelay {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            capture(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Test").body(body.toResponseBody()).build()
        }.build()
        return CompanionRelay(client)
    }

    private fun Request.payload(): JsonObject {
        assertEquals("https", url.scheme)
        assertEquals("/v1/companion", url.encodedPath)
        assertNull(url.query)
        assertEquals("POST", method)
        assertEquals("Bearer fixture", header("Authorization"))
        val buffer = Buffer()
        checkNotNull(body).writeTo(buffer)
        return JsonParser.parseString(buffer.readUtf8()).asJsonObject
    }

    private fun record(text: String, draft: Boolean = false): NoteRecord {
        val date = LocalDate.of(2026, 2, 12).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return NoteRecord("private-id", text, date, date, draft)
    }

    @Test fun readingSendsOnlyTheOperationAndNote() = runBlocking {
        val observed = mutableListOf<Request>()
        val service = relay(200, """{"text":"a reply"}""") { observed.add(it) }
        val note = "Quoted \"line one\"\nline two"
        assertEquals(RelayReply(200, "a reply"), service.read("fixture", note))
        val payload = observed.single().payload()
        assertEquals(setOf("operation", "note"), payload.keySet())
        assertEquals("read", payload["operation"].asString)
        assertEquals(note, payload["note"].asString)
    }

    @Test fun readLimitCountsUtf8BytesAndSendsNoPartialNote() = runBlocking {
        val observed = mutableListOf<Request>()
        val service = relay(200, """{"text":"ok"}""") { observed.add(it) }
        val boundary = "é".repeat(8_192)
        assertEquals(RelayReply(413), service.read("fixture", boundary + "x"))
        assertEquals(RelayReply(400), service.read("fixture", "  "))
        assertTrue(observed.isEmpty())
        assertEquals(RelayReply(200, "ok"), service.read("fixture", boundary))
        assertEquals(boundary, observed.single().payload()["note"].asString)
    }

    @Test fun answerUsesOnlySavedExcerptsDatesAndTheQuestion() = runBlocking {
        val observed = mutableListOf<Request>()
        val service = relay(200, """{"text":"your answer"}""") { observed.add(it) }
        val question = "Which street?"
        val notes = listOf(record("unfinished", draft = true), record("Main Street"), record("x".repeat(800)))
        assertEquals(RelayReply(200, "your answer"), service.answer("fixture", question, notes))
        val payload = observed.single().payload()
        assertEquals(setOf("operation", "question", "notes"), payload.keySet())
        assertEquals("answer", payload["operation"].asString)
        assertEquals(question, payload["question"].asString)
        val excerpts = payload["notes"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("Main Street", "x".repeat(750)), excerpts.map { it["text"].asString })
        excerpts.forEach {
            assertEquals(setOf("date", "text"), it.keySet())
            assertEquals("Feb 12", it["date"].asString)
        }
    }

    @Test fun questionsWithoutSavedContextOrAboveTheByteLimitStayLocal() = runBlocking {
        val observed = mutableListOf<Request>()
        val service = relay(200, "{}") { observed.add(it) }
        assertEquals(RelayReply(400), service.answer("fixture", "Question?", emptyList()))
        assertEquals(RelayReply(400), service.answer("fixture", "Question?", listOf(record("private", draft = true))))
        assertEquals(RelayReply(400), service.answer("fixture", "", listOf(record("saved"))))
        assertEquals(RelayReply(413), service.answer("fixture", "é".repeat(1_025), listOf(record("saved"))))
        assertTrue(observed.isEmpty())
    }

    @Test fun answerRespectsTheNoteCountAndTotalUtf8Budget() = runBlocking {
        val observed = mutableListOf<Request>()
        val service = relay(200, """{"text":"ok"}""") { observed.add(it) }
        service.answer("fixture", "Which?", (1..12).map { record("note $it") })
        val shortExcerpts = observed.single().payload()["notes"].asJsonArray
        assertEquals((1..8).map { "note $it" }, shortExcerpts.map { it.asJsonObject["text"].asString })

        val question = "é".repeat(1_024)
        service.answer("fixture", question, List(8) { record("水".repeat(750)) })
        val bigExcerpts = observed.last().payload()["notes"].asJsonArray
        assertTrue(bigExcerpts.size() in 1..7)
        val excerptBytes = bigExcerpts.sumOf {
            val excerpt = it.asJsonObject
            excerpt["text"].asString.toByteArray().size + excerpt["date"].asString.toByteArray().size
        }
        assertTrue(question.toByteArray().size + excerptBytes <= 16_384)
    }

    @Test fun rejectsErrorAndMalformedOrOversizedOutput() = runBlocking {
        assertEquals(RelayReply(401), relay(401, """{"text":"ignore"}""").read("fixture", "hello"))
        assertEquals(RelayReply(200), relay(200, """{"text":42}""").read("fixture", "hello"))
        assertEquals(RelayReply(200), relay(200, "x".repeat(32_769)).read("fixture", "hello"))
    }
}
