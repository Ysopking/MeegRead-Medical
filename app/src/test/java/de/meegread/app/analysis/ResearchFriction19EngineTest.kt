package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchFriction19EngineTest {
    @Test
    fun frozenHealthyMedianNormalizationMapsRawMedianToOne() {
        val alpha = DoubleArray(19) { 1.0 }
        val beta = DoubleArray(19) { 0.0 }
        val highBeta = DoubleArray(19) { 0.0 }
        val gamma = DoubleArray(19) { ResearchFriction19Engine.HC_RAW_MEDIAN }
        val allCells = DoubleArray(114) { 1.0 }

        val result = ResearchFriction19Engine.evaluateFeatures(
            alpha = alpha,
            beta = beta,
            highBeta = highBeta,
            gamma = gamma,
            allSixBandCells = allCells,
            alphaCoherencePercent = 0.0,
            normalize = true
        )

        assertEquals(0.0, result.dispersionD, 1e-12)
        assertEquals(1.0, result.normalizedRatio, 2e-6)
        assertEquals(ResearchFriction19Engine.Regime.BASELINE, result.regime)
    }

    @Test
    fun dispersionUsesAllSixByNineteenCells() {
        val alpha = DoubleArray(19) { 2.0 }
        val beta = DoubleArray(19) { 1.0 }
        val highBeta = DoubleArray(19) { 1.0 }
        val gamma = DoubleArray(19) { 1.0 }
        val cells = DoubleArray(114) { index -> if (index < 57) 1.0 else 3.0 }

        val result = ResearchFriction19Engine.evaluateFeatures(
            alpha, beta, highBeta, gamma, cells, 50.0, normalize = false
        )

        assertTrue(result.dispersionD > 0.0)
        assertTrue(result.capacityZ > 0.0)
        assertTrue(result.loadY > 0.0)
    }

    @Test
    fun exactNineteenChannelMontageIsRequired() {
        val samples = List(128) { it.toDouble() }
        val channels = ResearchFriction19Engine.ELECTRODES.associateWith { samples }
        val recording = MeegRecording(
            name = "19ch",
            modality = Modality.EEG,
            sampleRateHz = 128.0,
            channels = channels,
            sampleCount = 128,
            durationSeconds = 1.0
        )
        assertTrue(ResearchFriction19Engine.missingChannels(recording).isEmpty())

        val reduced = recording.copy(channels = channels - "F7" + ("AF7" to samples))
        assertEquals(listOf("F7"), ResearchFriction19Engine.missingChannels(reduced))
    }
}
