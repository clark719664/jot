package com.jot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MicGainTest {
    private fun tone(level: Float, phase: Int = 0) = FloatArray(640) { level * sin(2 * PI * 220 * (it + phase) / 16000).toFloat() }
    private fun FloatArray.peak() = maxOf { abs(it) }

    @Test fun liftsNormalSpeechToAUsableLevel() {
        val gain = MicGain()
        val frame = tone(0.03f)
        repeat(4) { gain.apply(tone(0.03f)) }
        gain.apply(frame)
        assertEquals(0.4f, frame.peak(), 0.05f)
    }

    @Test fun leavesLoudSpeechAlone() {
        val gain = MicGain()
        val frame = tone(0.6f)
        gain.apply(frame)
        assertEquals(0.6f, frame.peak(), 0.01f)
    }

    @Test fun neverClipsWhenSpeechStartsAfterSilence() {
        val gain = MicGain()
        repeat(50) { gain.apply(FloatArray(640)) }
        val frame = tone(0.5f)
        gain.apply(frame)
        assertTrue(frame.peak().toString(), frame.peak() <= 0.51f)
    }

    @Test fun capsGainOnNearSilence() {
        val gain = MicGain(maxGain = 20f)
        val frame = tone(0.0005f)
        repeat(4) { gain.apply(tone(0.0005f)) }
        gain.apply(frame)
        assertEquals(0.01f, frame.peak(), 0.001f)
    }
}
