package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CouplingFieldV07AuditTest {
    @Test
    fun algorithmFingerprintIsFrozen() {
        assertEquals(
            "489b4dcd4dbeb167bc7309b2483bbf7e48e19fb8356bcc9831f97d7ce4acaa8a",
            CouplingFieldV07Audit.algorithmFingerprintSha256
        )
    }

    @Test
    fun snapshotExportsConfigHashScalesAndPairs() {
        val recording = recording()
        val snapshot = CouplingFieldV07Audit.build(recording)
        assertNotNull(snapshot)
        snapshot!!

        assertEquals(CouplingFieldV07Engine.VERSION, snapshot.engineVersion)
        assertEquals(64, snapshot.configSha256.length)
        assertTrue(snapshot.configSha256.all { it in "0123456789abcdef" })
        assertEquals(listOf(4, 2, 1), snapshot.result.scales.take(3).map { it.nodeCount })

        val json = snapshot.toJson()
        assertTrue(json.contains("\"algorithmFingerprintSha256\""))
        assertTrue(json.contains("\"configSha256\""))
        assertTrue(json.contains("\"scales\""))
        assertTrue(json.contains("\"pairs\""))
        assertTrue(json.contains("\"operationalSpace\": \"SENSOR_SPACE_PROXY\""))
    }

    @Test
    fun configHashChangesWhenRunConfigurationChanges() {
        val recording = recording()
        val defaultRun = CouplingFieldV07Audit.build(recording)!!
        val shorterRun = CouplingFieldV07Audit.build(recording, requestedWindowSamples = 512)!!
        assertTrue(defaultRun.configSha256 != shorterRun.configSha256)
        assertEquals(defaultRun.algorithmFingerprintSha256, shorterRun.algorithmFingerprintSha256)
    }

    private fun recording(): MeegRecording {
        val fs = 128.0
        val samples = 2048
        val channels = linkedMapOf<String, List<Double>>()
        repeat(4) { index ->
            channels["F${index + 1}"] = List(samples) { t ->
                sin(2.0 * PI * (8.0 + index) * t / fs + 0.2 * index)
            }
        }
        return MeegRecording(
            name = "audit-synthetic",
            modality = Modality.EEG,
            sampleRateHz = fs,
            channels = channels,
            sampleCount = samples,
            durationSeconds = samples / fs
        )
    }
}
