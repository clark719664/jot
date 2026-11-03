package com.jot.app.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class BundledSpeechTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun config(dir: String) = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig("voice/$dir/encoder.onnx", "voice/$dir/decoder.onnx", "voice/$dir/joiner.onnx"),
        tokens = "voice/$dir/tokens.txt",
    )
    private fun samples(file: String): FloatArray {
        val bytes = instrumentation.context.assets.open(file).use { it.readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = data.getInt(offset + 4)
            if (type == "data") return FloatArray(length / 2) { i -> data.getShort(offset + 8 + i * 2) / 32768f }
            offset += 8 + length + length % 2
        }
        error("PCM fixture has no data chunk")
    }
    private fun heard(file: String): String = kotlinx.coroutines.runBlocking {
        val pcm = samples(file)
        var offset = 0
        val audio = OfflineAudio(instrumentation.targetContext.assets)
        try {
            audio.hear({
                FloatArray(640) { pcm.getOrElse(offset + it) { 0f } }.also { offset += 640 }
            }, {}, {})
        } finally { audio.close() }
    }

    @Test fun noteAfterHeyJotKeepsItsFirstWords() {
        val text = heard("wake-then-note.wav")
        assertTrue(text, text.contains("need to set an alarm"))
        assertTrue(text, text.contains("school"))
    }

    @Test fun noteSaidInOneBreathWithHeyJotIsKept() {
        val text = heard("wake-one-breath.wav")
        assertTrue(text, text.contains("an alarm for six fifty") && !text.contains("jot"))
    }

    @Test fun bundledKeywordModelRecognizesWakePhraseAndRejectsUnrelatedSpeech() {
        val model = KeywordSpotter(instrumentation.targetContext.assets, KeywordSpotterConfig(modelConfig = config("kws"), keywordsFile = "voice/kws/keywords.txt", keywordsThreshold = OfflineAudio.WAKE_THRESHOLD, keywordsScore = OfflineAudio.WAKE_SCORE))
        fun detect(pcm: FloatArray): String {
            val stream = model.createStream()
            try {
                for (offset in pcm.indices step 640) {
                    stream.acceptWaveform(pcm.copyOfRange(offset, minOf(offset + 640, pcm.size)), 16000)
                    while (model.isReady(stream)) { model.decode(stream); model.getResult(stream).keyword.takeIf { it.isNotBlank() }?.let { return it } }
                }
                return ""
            } finally { stream.release() }
        }
        fun quiet(pcm: FloatArray): FloatArray {
            val gain = com.jot.app.core.MicGain()
            val out = FloatArray(pcm.size)
            for (offset in pcm.indices step 640) {
                val frame = FloatArray(640) { pcm.getOrElse(offset + it) { 0f } * 0.05f }
                gain.apply(frame)
                frame.copyInto(out, offset, 0, minOf(640, pcm.size - offset))
            }
            return out
        }
        try {
            assertTrue("Hey Jot must activate the bundled model", detect(samples("hey-jot.wav")).isNotBlank())
            assertTrue("Hey Jot must activate at talking volume", detect(quiet(samples("hey-jot.wav"))).isNotBlank())
            assertEquals("", detect(samples("dictation.wav")))
            assertEquals("", detect(quiet(samples("dictation.wav"))))
        }
        finally { model.release() }
    }
    @Test fun bundledSpeechModelTranscribesARecordedUtterance() {
        val model = OnlineRecognizer(instrumentation.targetContext.assets, OnlineRecognizerConfig(modelConfig = config("asr")))
        val stream = model.createStream()
        try {
            stream.acceptWaveform(samples("dictation.wav"), 16000)
            stream.acceptWaveform(FloatArray(16000), 16000)
            stream.inputFinished()
            while (model.isReady(stream)) model.decode(stream)
            val text = model.getResult(stream).text.uppercase()
            assertTrue(text, text.contains("YELLOW LAMPS"))
            assertTrue(text, text.contains("HERE AND THERE"))
        } finally { stream.release(); model.release() }
    }
}
