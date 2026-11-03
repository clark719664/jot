package com.jot.app.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Jot's own voice: Supertonic 3, generated on the phone. */
internal class NaturalVoice(private val context: Context, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var engine: OfflineTts? = null
    private var idle: Job? = null

    fun warm() {
        idle?.cancel()
        scope.launch(Dispatchers.Default) { lock.withLock { runCatching { engine() } }; rest() }
    }

    /** Plays [text] and returns when it finishes. False means the voice failed and a fallback should speak. */
    suspend fun speak(text: String): Boolean = withContext(Dispatchers.Default) {
        val coroutine = currentCoroutineContext()
        idle?.cancel()
        lock.withLock {
            val tts = runCatching { engine() }.onFailure { Log.w(TAG, "Voice could not load.", it) }.getOrNull() ?: return@withContext false
            val track = track(tts.sampleRate())
            try {
                track.play()
                var written = 0L
                val started = SystemClock.elapsedRealtime()
                tts.generateWithConfigAndCallback(text, generation()) { samples ->
                    if (!coroutine.isActive) return@generateWithConfigAndCallback 0
                    written += track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING).coerceAtLeast(0)
                    1
                }
                coroutine.ensureActive()
                Log.i(TAG, "Generated ${written * 1000 / tts.sampleRate()} ms of speech in ${SystemClock.elapsedRealtime() - started} ms.")
                track.stop()
                val deadline = SystemClock.elapsedRealtime() + written * 1000 / tts.sampleRate() + DRAIN_MS
                while (track.playbackHeadPosition < written && SystemClock.elapsedRealtime() < deadline) {
                    coroutine.ensureActive()
                    delay(DRAIN_POLL_MS)
                }
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Voice playback failed.", e); false
            } finally {
                runCatching { track.pause(); track.flush() }
                track.release()
                rest()
            }
        }
    }

    internal suspend fun render(text: String, speaker: Int = SPEAKER): FloatArray = withContext(Dispatchers.Default) {
        lock.withLock { engine().generateWithConfig(text, generation(speaker)).samples }
    }

    private fun generation(speaker: Int = SPEAKER) = GenerationConfig(sid = speaker, speed = SPEED, numSteps = STEPS, extra = mapOf("lang" to "en"))

    fun close() {
        idle?.cancel()
        engine?.release()
        engine = null
    }

    private fun rest() {
        idle?.cancel()
        idle = scope.launch(Dispatchers.Default) {
            delay(IDLE_MS)
            lock.withLock { engine?.release(); engine = null }
        }
    }

    private fun engine(): OfflineTts = engine ?: run {
        val started = SystemClock.elapsedRealtime()
        OfflineTts(
            context.assets,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = "$ASSETS/duration_predictor.int8.onnx",
                        textEncoder = "$ASSETS/text_encoder.int8.onnx",
                        vectorEstimator = "$ASSETS/vector_estimator.int8.onnx",
                        vocoder = "$ASSETS/vocoder.int8.onnx",
                        ttsJson = "$ASSETS/tts.json",
                        unicodeIndexer = "$ASSETS/unicode_indexer.bin",
                        voiceStyle = "$ASSETS/voice.bin",
                    ),
                    numThreads = THREADS,
                ),
            ),
        ).also {
            engine = it
            Log.i(TAG, "Voice loaded in ${SystemClock.elapsedRealtime() - started} ms.")
        }
    }

    private fun track(sampleRate: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(sampleRate))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    internal companion object {
        const val ASSETS = "voice/tts"
        const val SPEAKER = 1
        const val SPEED = 1.05f
        const val STEPS = 5
        const val THREADS = 4
        private const val IDLE_MS = 120_000L
        private const val DRAIN_MS = 2_000L
        private const val DRAIN_POLL_MS = 20L
        private const val TAG = "JotVoice"
    }
}
