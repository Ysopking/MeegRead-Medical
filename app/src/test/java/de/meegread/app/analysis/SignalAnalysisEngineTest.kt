package de.meegread.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SignalAnalysisEngineTest {
    @Test
    fun dominantFrequencyTracksSyntheticAlpha() {
        val fs = 128.0
        val samples = List(1024) { i -> sin(2.0 * PI * 10.0 * i / fs) }
        val metrics = SignalAnalysisEngine.analyzeChannel("AF7", samples, fs)
        assertTrue(metrics.dominantFrequencyHz != null)
        assertEquals(10.0, metrics.dominantFrequencyHz!!, 0.5)
    }

    @Test
    fun bandPowersAreFinite() {
        val fs = 128.0
        val samples = List(1024) { i -> sin(2.0 * PI * 6.0 * i / fs) + 0.5 * sin(2.0 * PI * 20.0 * i / fs) }
        val powers = SignalAnalysisEngine.bandPowers(samples, fs)
        assertTrue(powers.isNotEmpty())
        assertTrue(powers.all { it.absolutePower.isFinite() && it.relativePower.isFinite() })
    }
}
