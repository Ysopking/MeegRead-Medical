package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CouplingFieldV07EngineTest {
    @Test
    fun identicalOscillationsProduceStrongCouplingAndBoundedMetrics() {
        val recording = sineRecording(channelCount = 4, sameFrequency = true)
        val result = CouplingFieldV07Engine.analyze(recording)

        assertNotNull(result)
        val base = result!!.baseScale!!
        assertEquals(4, base.nodeCount)
        assertTrue(base.meanPhaseLocking > 0.90)
        assertTrue(base.kappa in 0.0..1.0)
        assertTrue(base.directionCoherence in 0.0..1.0)
        assertTrue(base.gamma in 0.0..1.0)
        assertTrue(base.couplingLossQ >= 0.0)
        assertTrue(base.chiDyn >= 0.0)
    }

    @Test
    fun recursiveCoarseGrainingReducesFourNodesToOne() {
        val result = CouplingFieldV07Engine.analyze(sineRecording(channelCount = 4, sameFrequency = false))!!
        val counts = result.scales.map { it.nodeCount }

        assertEquals(listOf(4, 2, 1), counts.take(3))
        assertTrue(counts.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun rawSensorRecordingIsExplicitlyMarkedAsProxy() {
        val result = CouplingFieldV07Engine.analyze(sineRecording(channelCount = 2, sameFrequency = true))!!
        assertEquals(CouplingFieldV07Engine.OperationalSpace.SENSOR_SPACE_PROXY, result.operationalSpace)
        assertTrue(result.warnings.any { it.contains("Sensorraum") })
    }

    @Test
    fun explicitSourceSpaceMetadataIsPreserved() {
        val recording = sineRecording(channelCount = 2, sameFrequency = true).copy(
            metadata = mapOf("analysis_space" to "source-space")
        )
        val result = CouplingFieldV07Engine.analyze(recording)!!
        assertEquals(CouplingFieldV07Engine.OperationalSpace.SOURCE_SPACE, result.operationalSpace)
    }

    @Test
    fun normalizedMutualInformationRecognizesIdentity() {
        val a = DoubleArray(256) { i -> sin(2.0 * PI * i / 32.0) }
        val identity = CouplingFieldV07Engine.normalizedMutualInformation(a, a.copyOf())
        assertTrue(identity > 0.95)
    }

    private fun sineRecording(channelCount: Int, sameFrequency: Boolean): MeegRecording {
        val fs = 128.0
        val samples = 1024
        val channels = linkedMapOf<String, List<Double>>()
        repeat(channelCount) { index ->
            val frequency = if (sameFrequency) 10.0 else 7.0 + index * 2.0
            val phase = if (sameFrequency) 0.0 else 0.15 * index
            channels["F${index + 1}"] = List(samples) { t ->
                sin(2.0 * PI * frequency * t / fs + phase)
            }
        }
        return MeegRecording(
            name = "synthetic-v07",
            modality = Modality.EEG,
            sampleRateHz = fs,
            channels = channels,
            sampleCount = samples,
            durationSeconds = samples / fs
        )
    }
}
