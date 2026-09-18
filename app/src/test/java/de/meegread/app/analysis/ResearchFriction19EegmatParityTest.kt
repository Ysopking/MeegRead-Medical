package de.meegread.app.analysis

import de.meegread.app.data.EdfBdfParser
import de.meegread.app.model.MeegRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.max

class ResearchFriction19EegmatParityTest {
    @Test
    fun preregisteredEegmatPairMatchesIndependentPythonOracle() {
        assumeTrue(
            "Dedicated WF01 EEGMAT validation workflow only",
            System.getenv("WF01_EEGMAT_CONFIRMATORY") == "true"
        )
        val subject = System.getenv("WF01_EEGMAT_SUBJECT") ?: error("WF01_EEGMAT_SUBJECT must be set")
        require(subject in (0..35).map { "Subject%02d".format(it) }) {
            "Subject outside frozen WF01 set: $subject"
        }
        val restPath = requiredPath("WF01_EEGMAT_REST_EDF")
        val taskPath = requiredPath("WF01_EEGMAT_TASK_EDF")
        val referenceDir = requiredPath("WF01_EEGMAT_REFERENCE_DIR")

        val rest = matchedSixtySeconds(EdfBdfParser.parse(restPath.toFile()))
        val task = matchedSixtySeconds(EdfBdfParser.parse(taskPath.toFile()))
        assertEquals(500.0, rest.sampleRateHz, 0.0)
        assertEquals(500.0, task.sampleRateHz, 0.0)
        assertEquals(30000, rest.sampleCount)
        assertEquals(30000, task.sampleCount)

        val actualRest = ResearchFriction19Engine.analyze(rest)
        val actualTask = ResearchFriction19Engine.analyze(task)
        assertNotNull(actualRest)
        assertNotNull(actualTask)

        val expected = readCsv(referenceDir.resolve("reference.csv")).associateBy { it.getValue("condition") }
        compare("rest", expected.getValue("rest"), actualRest ?: error("rest result null"))
        compare("arithmetic", expected.getValue("arithmetic"), actualTask ?: error("task result null"))
    }

    private fun matchedSixtySeconds(recording: MeegRecording): MeegRecording {
        require(abs(recording.sampleRateHz - 500.0) <= 1e-12) {
            "Unexpected EEGMAT sample rate: ${recording.sampleRateHz}"
        }
        val count = 30000
        require(recording.channels.values.all { it.size >= count }) {
            "EEGMAT recording cannot supply frozen 60-second window"
        }
        return recording.copy(
            channels = recording.channels.mapValues { (_, values) -> values.take(count) },
            sampleCount = count,
            durationSeconds = count / recording.sampleRateHz
        )
    }

    private fun compare(label: String, expected: Map<String, String>, actual: ResearchFriction19Engine.Result) {
        assertEquals("friction19-research-v1.0", ResearchFriction19Engine.VERSION)
        assertEquals(19, actual.localRatios.size)
        assertEquals(19, actual.sourceChannels.size)
        assertClose("$label.capacityZ", expected.double("capacityZ"), actual.capacityZ)
        assertClose("$label.loadY", expected.double("loadY"), actual.loadY)
        assertClose("$label.dispersionD", expected.double("dispersionD"), actual.dispersionD)
        assertClose("$label.rawRatio", expected.double("rawRatio"), actual.rawRatio)
        assertClose("$label.normalizedRatio", expected.double("normalizedRatio"), actual.normalizedRatio)
        assertClose(
            "$label.alphaCoherencePercent",
            expected.double("alphaCoherencePercent"),
            actual.alphaCoherencePercent
        )
        ResearchFriction19Engine.ELECTRODES.forEach { electrode ->
            assertTrue("Missing local ratio $electrode", electrode in actual.localRatios)
            assertClose(
                "$label.local.$electrode",
                expected.double("local_$electrode"),
                actual.localRatios.getValue(electrode)
            )
        }
    }

    private fun requiredPath(name: String): Path {
        val value = System.getenv(name)
        require(!value.isNullOrBlank()) { "$name must be set" }
        val path = Paths.get(value)
        require(Files.exists(path)) { "$name does not exist: $path" }
        return path
    }

    private fun readCsv(path: Path): List<Map<String, String>> {
        val lines = Files.readAllLines(path).filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "Empty reference CSV: $path" }
        val header = lines.first().split(',')
        return lines.drop(1).mapIndexed { index, line ->
            val values = line.split(',')
            require(values.size == header.size) { "Malformed reference row ${index + 2} in $path" }
            header.zip(values).toMap()
        }
    }

    private fun Map<String, String>.double(name: String): Double = getValue(name).toDouble()

    private fun assertClose(label: String, expected: Double, actual: Double) {
        assertTrue("$label expected finite: $expected", expected.isFinite())
        assertTrue("$label actual finite: $actual", actual.isFinite())
        val tolerance = max(1e-9, 1e-8 * max(1.0, abs(expected)))
        assertEquals(label, expected, actual, tolerance)
    }
}
