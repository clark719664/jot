package com.jot.app.capture

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Uses only an installed on-device English Android voice. */
internal class OfflineSpeech(private val context: Context) {
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var nextUtterance = 0L

    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return true
        val tts = getEngine() ?: return false
        val done = CompletableDeferred<Boolean>()
        val id = "jot-${++nextUtterance}"
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)
            @Deprecated("Required for older TTS engines")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)

            private fun finish(utteranceId: String?, success: Boolean) {
                if (utteranceId == id) done.complete(success)
            }
        }
        try {
            if (tts.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS ||
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS
            ) return false
            return withTimeoutOrNull(SPEECH_TIMEOUT_MS) { done.await() } ?: false
        } finally {
            tts.setOnUtteranceProgressListener(null)
            if (!done.isCompleted) tts.stop()
        }
    }

    private suspend fun getEngine(): TextToSpeech? {
        if (engine == null) {
            val init = CompletableDeferred<Boolean>()
            ready = init
            engine = TextToSpeech(context) { init.complete(it == TextToSpeech.SUCCESS) }
        }
        val init = ready ?: return null
        val tts = engine ?: return null
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { init.await() } != true) {
            close()
            return null
        }
        val offlineVoice = tts.voices.orEmpty()
            .filter {
                !it.isNetworkConnectionRequired &&
                    it.locale.language == Locale.ENGLISH.language &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            .maxWithOrNull(compareBy({ it.locale == Locale.US }, { it.quality }, { -it.latency }))
            ?: return null
        if (tts.setVoice(offlineVoice) != TextToSpeech.SUCCESS) return null
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        return tts
    }

    fun stop() {
        engine?.stop()
    }

    fun close() {
        stop()
        engine?.shutdown()
        engine = null
        ready = null
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 7_000L
        const val SPEECH_TIMEOUT_MS = 90_000L
    }
}
