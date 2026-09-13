package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ExperimentalLoadEngineTest {
    @Test
    fun boundedLoadStaysBelowModelParameter() {
        val bounded = ThermodynamicLoadEngine.boundedLoad(10000.0)
        assertTrue(bounded > 0.0)
        assertTrue(bounded < ThermodynamicLoadEngine.OMEGA_KRIT)
        assertFalse(ThermodynamicLoadEngine.isOmegaBreach(5799.99))
        assertTrue(ThermodynamicLoadEngine.isOmegaBreach(5800.0))
    }

    @Test
    fun fourChannelSignalProducesFiniteTrajectory() {
        val fs = 128.0
        val count = 12 * 128
        fun wave(alpha: Double, beta: Double, theta: Double) = List(count) { i ->
            val t = i / fs
            20.0 * sin(2.0 * PI * alpha * t) + 7.0 * sin(2.0 * PI * beta * t) + 4.0 * sin(2.0 * PI * theta * t)
        }
        val recording = MeegRecording("synthetic", Modality.EEG, fs, mapOf(
            "AF7" to wave(10.0, 17.0, 6.0),
            "AF8" to wave(10.0, 18.0, 6.0),
            "TP9" to wave(10.0, 24.0, 6.0),
            "TP10" to wave(10.0, 25.0, 6.0)
        ), count, count / fs)
        val result = ThermodynamicLoadEngine.analyze(recording)
        assertNotNull(result)
        assertTrue(result!!.points.isNotEmpty())
        assertTrue(result.points.all { it.wRaw.isFinite() && it.faa.isFinite() })
    }

    @Test
    fun flatChannelRejectsResearchLoad() {
        val fs = 128.0
        val count = 512
        val recording = MeegRecording("flat", Modality.EEG, fs, mapOf(
            "AF7" to List(count) { 0.0 },
            "AF8" to List(count) { 1.0 },
            "TP9" to List(count) { 2.0 },
            "TP10" to List(count) { 3.0 }
        ), count, count / fs)
        assertTrue(ThermodynamicLoadEngine.analyze(recording) == null)
    }
}
