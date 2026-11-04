package com.jot.app.capture

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.jot.app.core.MicGain
import com.jot.app.core.SpeechText
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal fun interface Frames {
    suspend fun next(): FloatArray
}

/** All calls run on the service's single audio worker. */
internal class OfflineAudio(private val assets: AssetManager) {
    private var spotter: KeywordSpotter? = null
    private var recognizer: OfflineRecognizer? = null
    private var detector: Vad? = null
    private var lastUsed = 0L

    /** Waits for Hey Jot, then keeps the same microphone open so nothing said after it is lost. */
    suspend fun listen(onListening: suspend () -> Unit, onWake: suspend () -> Unit, onRecording: suspend () -> Unit): String {
        prepareSpotter()
        currentCoroutineContext().ensureActive()
        return withMic { recorder ->
            onListening()
            hear(mic(recorder), onWake, onRecording)
        }
    }

    /** The microphone opens first: speech during the recognizer's load waits in its buffer. */
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
                if (recognizer != null && SystemClock.elapsedRealtime() - lastUsed > RECOGNIZER_IDLE_MS) releaseRecognizer()
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

    /** Records until the speaker stops. Pauses and hesitations are part of the sentence, so only long silence ends it. */
    private suspend fun transcribe(model: OfflineRecognizer, frames: Frames, head: List<FloatArray>): String {
        val coroutine = currentCoroutineContext()
        val detector = prepareDetector().apply { reset() }
        val audio = ArrayList(head)
        head.forEach(detector::acceptWaveform)
        var spokeAt = if (detector.isSpeechDetected() || !detector.empty()) 0 else -1
        while (detector.empty() && audio.size < MAX_FRAMES) {
            coroutine.ensureActive()
            if (spokeAt < 0 && audio.size >= NO_SPEECH_FRAMES) return ""
            val frame = frames.next().copyOf()
            audio += frame
            detector.acceptWaveform(frame)
            if (spokeAt < 0 && detector.isSpeechDetected()) spokeAt = audio.size
        }
        if (spokeAt < 0) return ""
        val ended = if (detector.empty()) audio.size else (audio.size - TRAILING_FRAMES).coerceAtLeast(1)
        val kept = audio.subList((spokeAt - BEFORE_SPEECH_FRAMES).coerceIn(0, ended - 1), ended)
        val samples = FloatArray(kept.sumOf { it.size })
        var filled = 0
        kept.forEach { it.copyInto(samples, filled); filled += it.size }
        coroutine.ensureActive()
        val stream = model.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            model.decode(stream)
            lastUsed = SystemClock.elapsedRealtime()
            return SpeechText.tidy(model.getResult(stream).text)
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

    private fun prepareRecognizer(): OfflineRecognizer = recognizer ?: OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "voice/asr/encoder_model.ort",
                    mergedDecoder = "voice/asr/decoder_model_merged.ort",
                ),
                tokens = "voice/asr/tokens.txt",
                numThreads = RECOGNIZER_THREADS,
            ),
        ),
    ).also { recognizer = it; lastUsed = SystemClock.elapsedRealtime() }

    private fun prepareDetector(): Vad = detector ?: Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "voice/vad/silero_vad.onnx",
                threshold = SPEECH_THRESHOLD,
                minSilenceDuration = TRAILING_SILENCE_SECONDS,
                minSpeechDuration = MIN_SPEECH_SECONDS,
                windowSize = FRAME_SAMPLES,
                maxSpeechDuration = (MAX_FRAMES * FRAME_SAMPLES / SAMPLE_RATE).toFloat() + 1f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    ).also { detector = it }

    private fun releaseRecognizer() {
        recognizer?.release()
        recognizer = null
    }

    fun close() {
        try {
            spotter?.release()
            detector?.release()
        } finally {
            spotter = null
            detector = null
            releaseRecognizer()
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
        private const val FRAME_SAMPLES = 512
        private const val CARRY_OVER_FRAMES = 94
        private const val SPEECH_THRESHOLD = 0.4f
        private const val MIN_SPEECH_SECONDS = 0.1f
        private const val TRAILING_SILENCE_SECONDS = 1.6f
        private const val FRAMES_PER_SECOND = SAMPLE_RATE / FRAME_SAMPLES
        private const val TRAILING_FRAMES = FRAMES_PER_SECOND
        private const val BEFORE_SPEECH_FRAMES = FRAMES_PER_SECOND
        private const val NO_SPEECH_FRAMES = FRAMES_PER_SECOND * 7
        private const val MAX_FRAMES = FRAMES_PER_SECOND * 35
        private const val RECOGNIZER_THREADS = 4
        private const val RECOGNIZER_IDLE_MS = 120_000L
        private const val WAKE_TAIL_SECONDS = 0.15f
        private const val MIC_BUFFER_SECONDS = 10
        private const val MIC_POLL_MS = 20L
        private const val MIC_TIMEOUT_MS = 2_000L
        private const val WAKE_STREAM_MS = 120_000L
    }
}
