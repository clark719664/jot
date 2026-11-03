package com.jot.app.capture

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.jot.app.core.MicGain
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal fun interface Frames {
    suspend fun next(): FloatArray
}

/** All calls run on the service's single audio worker. */
internal class OfflineAudio(private val assets: AssetManager) {
    private var spotter: KeywordSpotter? = null
    private var recognizer: OnlineRecognizer? = null

    /** Waits for Hey Jot, then keeps the same microphone open so nothing said after it is lost. */
    suspend fun listen(onListening: suspend () -> Unit, onWake: suspend () -> Unit, onRecording: suspend () -> Unit): String {
        prepareRecognizer()
        prepareSpotter()
        currentCoroutineContext().ensureActive()
        return withMic { recorder ->
            onListening()
            hear(mic(recorder), onWake, onRecording)
        }
    }

    /** The microphone opens first: speech during the recognizer's first load waits in its buffer. */
    suspend fun capture(onRecording: suspend () -> Unit): String {
        currentCoroutineContext().ensureActive()
        return withMic { recorder ->
            onRecording()
            transcribe(prepareRecognizer(), mic(recorder), emptyList())
        }
    }

    internal suspend fun hear(frames: Frames, onWake: suspend () -> Unit, onRecording: suspend () -> Unit): String {
        val recent = awaitWake(prepareSpotter(), frames)
        onWake()
        onRecording()
        return transcribe(prepareRecognizer(), frames, recent)
    }

    /** Returns the audio heard after "Jot", which holds the start of a sentence said in one breath. */
    private suspend fun awaitWake(model: KeywordSpotter, frames: Frames): List<FloatArray> {
        val coroutine = currentCoroutineContext()
        val recent = ArrayDeque<FloatArray>()
        var stream = model.createStream()
        var heard = 0L
        var resetAt = SystemClock.elapsedRealtime() + WAKE_STREAM_MS
        try {
            while (true) {
                coroutine.ensureActive()
                val frame = frames.next()
                recent.addLast(frame.copyOf())
                if (recent.size > CARRY_OVER_FRAMES) recent.removeFirst()
                stream.acceptWaveform(frame, SAMPLE_RATE)
                heard += frame.size
                while (model.isReady(stream)) {
                    coroutine.ensureActive()
                    model.decode(stream)
                    val result = model.getResult(stream)
                    if (result.keyword.isNotBlank()) {
                        val wakeEnd = ((result.timestamps.lastOrNull() ?: 0f) + WAKE_TAIL_SECONDS) * SAMPLE_RATE
                        val oldest = heard - recent.size * FRAME_SAMPLES
                        return recent.drop(((wakeEnd - oldest) / FRAME_SAMPLES).toInt().coerceAtLeast(0))
                    }
                }
                if (SystemClock.elapsedRealtime() >= resetAt) {
                    // A busy room may never produce the silence that resets the native stream.
                    val replacement = model.createStream()
                    stream.release()
                    stream = replacement
                    heard = 0L
                    resetAt = SystemClock.elapsedRealtime() + WAKE_STREAM_MS
                }
            }
        } finally {
            stream.release()
        }
    }

    private suspend fun transcribe(model: OnlineRecognizer, frames: Frames, head: List<FloatArray>): String {
        val coroutine = currentCoroutineContext()
        val stream = model.createStream()
        try {
            // Measured: this recognizer drops the first words of a stream without about 2 s of lead-in.
            stream.acceptWaveform(FloatArray(SAMPLE_RATE * LEAD_IN_SECONDS), SAMPLE_RATE)
            head.forEach { stream.acceptWaveform(it, SAMPLE_RATE) }
            val deadline = SystemClock.elapsedRealtime() + CAPTURE_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                coroutine.ensureActive()
                while (model.isReady(stream)) {
                    coroutine.ensureActive()
                    model.decode(stream)
                }
                if (model.isEndpoint(stream)) break
                stream.acceptWaveform(frames.next(), SAMPLE_RATE)
            }
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 3), SAMPLE_RATE)
            stream.inputFinished()
            while (model.isReady(stream)) {
                coroutine.ensureActive()
                model.decode(stream)
            }
            return model.getResult(stream).text.trim().lowercase(Locale.US)
        } finally {
            stream.release()
        }
    }

    private fun mic(recorder: AudioRecord): Frames {
        val pcm = ShortArray(FRAME_SAMPLES)
        val waveform = FloatArray(FRAME_SAMPLES)
        val gain = MicGain()
        return Frames { read(recorder, pcm, waveform, gain) }
    }

    private fun prepareSpotter(): KeywordSpotter = spotter ?: KeywordSpotter(
        assets,
        KeywordSpotterConfig(
            modelConfig = model("kws"),
            keywordsFile = "voice/kws/keywords.txt",
            keywordsThreshold = WAKE_THRESHOLD,
            keywordsScore = WAKE_SCORE,
        ),
    ).also { spotter = it }

    private fun prepareRecognizer(): OnlineRecognizer = recognizer ?: OnlineRecognizer(
        assets,
        OnlineRecognizerConfig(
            modelConfig = model("asr"),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 7f + LEAD_IN_SECONDS, 0f),
                rule2 = EndpointRule(true, 1.25f, 0f),
                rule3 = EndpointRule(false, 0f, CAPTURE_MS / 1_000f),
            ),
        ),
    ).also { recognizer = it }

    fun close() {
        try {
            spotter?.release()
        } finally {
            spotter = null
            recognizer?.release()
            recognizer = null
        }
    }

    private fun model(directory: String): OnlineModelConfig {
        val path = "voice/$directory"
        return OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$path/encoder.onnx",
                decoder = "$path/decoder.onnx",
                joiner = "$path/joiner.onnx",
            ),
            tokens = "$path/tokens.txt",
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun <T> withMic(block: suspend (AudioRecord) -> T): T {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, format)
        check(minimum > 0) { "Microphone does not support 16 kHz mono input." }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            channel,
            format,
            maxOf(minimum, SAMPLE_RATE * 2 * MIC_BUFFER_SECONDS),
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Could not start the microphone." }
            return block(recorder)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private suspend fun read(recorder: AudioRecord, pcm: ShortArray, waveform: FloatArray, gain: MicGain): FloatArray {
        val deadline = SystemClock.elapsedRealtime() + MIC_TIMEOUT_MS
        var filled = 0
        while (filled < pcm.size) {
            currentCoroutineContext().ensureActive()
            val count = recorder.read(pcm, filled, pcm.size - filled, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0 && SystemClock.elapsedRealtime() < deadline) {
                "Microphone stopped providing audio."
            }
            filled += count
            if (filled < pcm.size) delay(MIC_POLL_MS)
        }
        for (i in pcm.indices) waveform[i] = pcm[i] / 32_768f
        gain.apply(waveform)
        return waveform
    }

    internal companion object {
        const val WAKE_THRESHOLD = 0.1f
        const val WAKE_SCORE = 2f
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 640
        private const val LEAD_IN_SECONDS = 2
        private const val CARRY_OVER_FRAMES = 75
        private const val WAKE_TAIL_SECONDS = 0.15f
        private const val MIC_BUFFER_SECONDS = 10
        private const val MIC_POLL_MS = 20L
        private const val MIC_TIMEOUT_MS = 2_000L
        private const val CAPTURE_MS = 35_000L
        private const val WAKE_STREAM_MS = 120_000L
    }
}
