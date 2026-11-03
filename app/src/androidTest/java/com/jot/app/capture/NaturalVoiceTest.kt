package com.jot.app.capture

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class NaturalVoiceTest {
    private val lines = listOf(
        "readback" to "Got it. Dinner with Ana Friday at 7 PM, ask Jake to bring the cake. Sound right?",
        "offer" to "Want me to add a calendar event on Friday at 7 PM and a message?",
        "done" to "Done. Alarm set, reminder set.",
        "sorry" to "Sorry, I didn't catch that. One more time?",
        "answer" to "The wifi password is maple 42.",
        "names" to "Priya and Xavier are meeting Siobhan at 6:30 AM on November 12.",
    )

    @Test fun rendersEveryKindOfLine() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val voice = NaturalVoice(context, scope)
        val folder = File(context.getExternalFilesDir(null), "voice-samples").apply { mkdirs() }
        try {
            for ((name, line) in lines) {
                val started = System.nanoTime()
                val samples = voice.render(line)
                val spent = (System.nanoTime() - started) / 1_000_000
                val seconds = samples.size / RATE.toFloat()
                Log.i("JotVoiceTest", "$name: %.1f s of speech in $spent ms".format(seconds))
                assertTrue(name, seconds > 0.5f)
                File(folder, "$name.wav").writeBytes(wav(samples))
            }
        } finally {
            voice.close(); scope.cancel()
        }
    }

    private fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        out.putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data)
        for (sample in samples) out.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return out.array()
    }

    private companion object { const val RATE = 44_100 }
}
