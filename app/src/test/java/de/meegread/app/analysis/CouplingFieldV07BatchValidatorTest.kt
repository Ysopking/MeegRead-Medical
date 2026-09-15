package de.meegread.app.analysis

import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CouplingFieldV07BatchValidatorTest {
    @Test
    fun mixedBatchKeepsOrderAndRecordsFailures() {
        val valid = sensorRecording("valid")
        val invalid = sourceRecordingWithoutSelection("invalid-source")

        val result = CouplingFieldV07BatchValidator.run(listOf(valid, invalid))

        assertEquals(2, result.items.size)
        assertEquals(1, result.successCount)
        assertEquals(1, result.failureCount)
        assertEquals("valid", result.items[0].recordingName)
        assertEquals(CouplingFieldV07BatchValidator.Status.SUCCESS, result.items[0].status)
        assertEquals("invalid-source", result.items[1].recordingName)
        assertEquals(CouplingFieldV07BatchValidator.Status.NOT_ANALYZABLE, result.items[1].status)
        assertEquals(64, result.batchResultSha256.length)
    }

    @Test
    fun identicalBatchProducesIdenticalFingerprintAndCsv() {
        val inputs = listOf(sensorRecording("a"), sensorRecording("b", phaseOffset = 0.31))
        val first = CouplingFieldV07BatchValidator.run(inputs)
        val second = CouplingFieldV07BatchValidator.run(inputs)

        assertEquals(first.batchResultSha256, second.batchResultSha256)
        assertEquals(first.toScaleCsv(), second.toScaleCsv())
        assertEquals(first.toManifestJson(), second.toManifestJson())
    }

    @Test
    fun callerOrderIsPartOfBatchFingerprint() {
        val a = sensorRecording("a")
        val b = sensorRecording("b", phaseOffset = 0.31)
        val ab = CouplingFieldV07BatchValidator.run(listOf(a, b))
        val ba = CouplingFieldV07BatchValidator.run(listOf(b, a))

        assertNotEquals(ab.batchResultSha256, ba.batchResultSha256)
    }

    @Test
    fun scaleCsvContainsEveryRecursiveScaleAndAuditHashes() {
        val result = CouplingFieldV07BatchValidator.run(listOf(sensorRecording("scale-test")))
        val csv = result.toScaleCsv()

        assertTrue(csv.contains("configSha256"))
        assertTrue(csv.contains(CouplingFieldV07Audit.algorithmFingerprintSha256))
        assertTrue(csv.lines().count { it.startsWith("0,scale-test,SUCCESS") } >= 3)
        assertTrue(result.toManifestJson().contains("\"successCount\": 1"))
    }

    private fun sensorRecording(name: String, phaseOffset: Double = 0.0): MeegRecording {
        val fs = 128.0
        val samples = 1024
        val channels = linkedMapOf<String, List<Double>>()
        repeat(4) { index ->
            channels["F${index + 1}"] = List(samples) { t ->
                sin(2.0 * PI * (8.0 + index) * t / fs + phaseOffset + index * 0.13)
            }
        }
        return MeegRecording(
            name = name,
            modality = Modality.EEG,
            sampleRateHz = fs,
            channels = channels,
            sampleCount = samples,
            durationSeconds = samples / fs
        )
    }

    private fun sourceRecordingWithoutSelection(name: String): MeegRecording {
        val fs = 128.0
        val samples = 1024
        val channels = linkedMapOf(
            "ParcelA" to List(samples) { t -> sin(2.0 * PI * 8.0 * t / fs) },
            "ParcelB" to List(samples) { t -> sin(2.0 * PI * 10.0 * t / fs) }
        )
        val info = channels.keys.associateWith { ChannelInfo(it, ChannelType.OTHER) }
        return MeegRecording(
            name = name,
            modality = Modality.UNKNOWN,
            sampleRateHz = fs,
            channels = channels,
            sampleCount = samples,
            durationSeconds = samples / fs,
            channelInfo = info,
            metadata = mapOf("analysis_space" to "source-space")
        )
    }
}
