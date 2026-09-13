package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BrmhCohortSupportEngineTest {
    @Test
    fun exactBrmhChannelsProduceNormalizedSevenClassScores() {
        val fs = 128.0
        val count = 16 * 128
        fun wave(alpha: Double, beta: Double, theta: Double) = List(count) { i ->
            val t = i / fs
            14.0 * sin(2.0 * PI * alpha * t) +
                6.0 * sin(2.0 * PI * beta * t) +
                4.0 * sin(2.0 * PI * theta * t)
        }
        val recording = MeegRecording(
            name = "brmh-compatible",
            modality = Modality.EEG,
            sampleRateHz = fs,
            channels = mapOf(
                "F7" to wave(10.0, 17.0, 6.0),
                "F8" to wave(10.0, 18.0, 6.0),
                "T3" to wave(10.0, 24.0, 6.0),
                "T4" to wave(10.0, 25.0, 6.0)
            ),
            sampleCount = count,
            durationSeconds = count / fs
        )

        val result = BrmhCohortSupportEngine.analyze(recording)
        assertNotNull(result)
        assertEquals(7, result!!.scores.size)
        assertEquals(3, result.top3.size)
        assertEquals(20, result.featureCount)
        assertTrue(result.scores.all { it.score.isFinite() && it.score in 0.0..1.0 })
        assertEquals(1.0, result.scores.sumOf { it.score }, 1e-9)
    }

    @Test
    fun afTpMontageIsNotSilentlyTreatedAsBrmhF7T3Montage() {
        val fs = 128.0
        val count = 512
        val wave = List(count) { i -> sin(2.0 * PI * 10.0 * i / fs) }
        val recording = MeegRecording(
            name = "muse-style",
            modality = Modality.EEG,
            sampleRateHz = fs,
            channels = mapOf("AF7" to wave, "AF8" to wave, "TP9" to wave, "TP10" to wave),
            sampleCount = count,
            durationSeconds = count / fs
        )

        assertTrue(BrmhCohortSupportEngine.analyze(recording) == null)
        assertEquals(listOf("F7", "F8", "T3", "T4"), BrmhCohortSupportEngine.missingChannels(recording))
    }

    @Test
    fun uploadedDatasetMetadataIsFrozen() {
        assertEquals(945, BrmhCohortSupportEngine.DATASET_SIZE)
        assertEquals(7, BrmhCohortSupportEngine.cohortMetadata.size)
        assertEquals(945, BrmhCohortSupportEngine.cohortMetadata.sumOf { it.count })
        assertTrue(BrmhCohortSupportEngine.CV_TOP3_ACCURACY < 0.60)
    }
}
