package com.jot.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lifts normal speech to the level the speech models expect. The VOICE_RECOGNITION source has no
 * automatic gain, so talking at arm's length arrives far quieter than the models were trained on.
 * Gain drops at once on a loud frame and recovers slowly, so nothing clips.
 */
class MicGain(private val maxGain: Float = 20f) {
    private var envelope = 0f
    private var gain = maxGain

    fun apply(frame: FloatArray) {
        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample))
        envelope = max(peak, envelope * RELEASE)
        val wanted = min(maxGain, max(1f, TARGET / max(envelope, QUIET_FLOOR)))
        val start = if (wanted < gain) wanted else gain
        for (i in frame.indices) {
            val applied = start + (wanted - start) * i / frame.size
            frame[i] = (frame[i] * applied).coerceIn(-1f, 1f)
        }
        gain = wanted
    }

    private companion object {
        const val TARGET = 0.4f
        const val RELEASE = 0.97f
        const val QUIET_FLOOR = 0.0001f
    }
}
