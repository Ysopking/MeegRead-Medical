package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import de.meegread.app.model.ThermodynamicLoadAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.max

class ThermodynamicLoadCogWearParityTest {
    @Test
    fun preregisteredCogWearPairsMatchIndependentPythonOracle() {
        assumeTrue(
            "Dedicated WF02 CogWear load workflow only",
            System.getenv("WF02_COGWEAR_LOAD") == "true"
        )
        val participant = System.getenv("WF02_COGWEAR_PARTICIPANT")
            ?: error("WF02_COGWEAR_PARTICIPANT must be set")
        require(participant.toIntOrNull() in 0..10) { "Participant outside frozen WF02 cohort: $participant" }

        val baselinePath = requiredPath("WF02_COGWEAR_BASELINE")
        val cognitivePath = requiredPath("WF02_COGWEAR_COGNITIVE")
        val referenceDir = requiredPath("WF02_COGWEAR_REFERENCE_DIR")

        val baseline = ThermodynamicLoadEngine.analyze(loadMatchedCogWear(baselinePath, "$participant-baseline"))
        val cognitive = ThermodynamicLoadEngine.analyze(loadMatchedCogWear(cognitivePath, "$participant-cognitive_load"))
        assertNotNull(baseline)
        assertNotNull(cognitive)

        compare(
            "baseline",
            readCsv(referenceDir.resolve("baseline_points.csv")),
            baseline ?: error("baseline analysis null")
        )
        compare(
            "cognitive_load",
            readCsv(referenceDir.resolve("cognitive_load_points.csv")),
            cognitive ?: error("cognitive-load analysis null")
        )
    }

    private fun loadMatchedCogWear(path: Path, label: String): MeegRecording {
        val columns = linkedMapOf(
            "TP9" to 21,
            "AF7" to 22,
            "AF8" to 23,
            "TP10" to 24
        )
        val channels = columns.keys.associateWith { mutableListOf<Double>() }.toMutableMap()
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            val header = reader.readLine() ?: error("CogWear CSV is empty")
            require(parseCsvLine(header).size >= 25) { "CogWear header must contain at least 25 columns" }
            var rowNumber = 1
            while (true) {
                val line = reader.readLine() ?: break
                rowNumber++
                if (line.isBlank()) continue
                val fields = parseCsvLine(line)
                require(fields.size >= 25) { "Non-empty CogWear row $rowNumber has only ${fields.size} columns" }
                columns.forEach { (channel, columnIndex) ->
                    channels.getValue(channel).add(fields[columnIndex].trim().toDoubleOrNull() ?: Double.NaN)
                }
            }
        }
        val matchedSamples = 46_080
        require(channels.values.all { it.size >= matchedSamples }) {
            "CogWear condition cannot supply frozen 180-second window"
        }
        val immutable = channels.mapValues { (_, values) -> values.take(matchedSamples) }
        return MeegRecording(
            name = "physionet-cogwear-wf02-$label",
            modality = Modality.EEG,
            sampleRateHz = 256.0,
            channels = immutable,
            sampleCount = matchedSamples,
            durationSeconds = 180.0,
            metadata = mapOf(
                "validation_source" to "PhysioNet CogWear 1.0.0",
                "analysis_space" to "sensor-space"
            )
        )
    }

    private fun compare(label: String, expectedRows: List<Map<String, String>>, actual: ThermodynamicLoadAnalysis) {
        assertEquals("mmsi-load-research-v1.2", ThermodynamicLoadEngine.ALGORITHM_VERSION)
        assertEquals(5800.0, actual.omegaCrit, 0.0)
        assertEquals(177, actual.points.size)
        assertEquals(expectedRows.size, actual.points.size)
        assertEquals(mapOf("AF7" to "AF7", "AF8" to "AF8", "TP9" to "TP9", "TP10" to "TP10"), actual.sourceChannels)

        expectedRows.forEachIndexed { index, row ->
            val point = actual.points[index]
            assertClose("$label[$index].timeSeconds", row.double("timeSeconds"), point.timeSeconds)
            assertClose("$label[$index].gradE", row.double("gradE"), point.gradE)
            assertClose("$label[$index].faa", row.double("faa"), point.faa)
            assertClose("$label[$index].hSigma", row.double("hSigma"), point.hSigma)
            assertClose("$label[$index].eFlow", row.double("eFlow"), point.eFlow)
            assertClose("$label[$index].pressureProxy", row.double("pressureProxy"), point.pressureProxy)
            assertClose("$label[$index].frictionRate", row.double("frictionRate"), point.frictionRate)
            assertClose("$label[$index].wRaw", row.double("wRaw"), point.wRaw)
            assertClose("$label[$index].wBounded", row.double("wBounded"), point.wBounded)
            assertClose("$label[$index].omegaRatio", row.double("omegaRatio"), point.omegaRatio)
            assertClose("$label[$index].flowGate", row.double("flowGate"), point.flowGate)
            assertClose("$label[$index].loadDriveRate", row.double("loadDriveRate"), point.loadDriveRate)
            assertClose("$label[$index].flowReliefRate", row.double("flowReliefRate"), point.flowReliefRate)
            assertClose("$label[$index].recoveryRate", row.double("recoveryRate"), point.recoveryRate)
            assertClose("$label[$index].netLoadRate", row.double("netLoadRate"), point.netLoadRate)
        }

        val expectedRaw = expectedRows.map { it.double("wRaw") }
        val expectedFaa = expectedRows.map { it.double("faa") }
        val expectedBreachIndex = expectedRaw.indexOfFirst { it >= 5800.0 }
        assertEquals(expectedBreachIndex >= 0, actual.omegaBreach)
        if (expectedBreachIndex < 0) {
            assertTrue(actual.firstBreachTimeSeconds == null)
        } else {
            assertNotNull(actual.firstBreachTimeSeconds)
            assertClose(
                "$label.firstBreachTimeSeconds",
                expectedRows[expectedBreachIndex].double("timeSeconds"),
                actual.firstBreachTimeSeconds ?: error("missing first breach")
            )
        }
        assertClose("$label.finalRawLoad", expectedRaw.last(), actual.finalRawLoad)
        assertClose("$label.finalBoundedLoad", expectedRows.last().double("wBounded"), actual.finalBoundedLoad)
        assertClose("$label.maxRawLoad", expectedRaw.maxOrNull() ?: error("missing max"), actual.maxRawLoad)
        assertClose("$label.meanFaa", expectedFaa.average(), actual.meanFaa)
        assertFalse(actual.points.any { !it.wRaw.isFinite() || !it.netLoadRate.isFinite() })
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
        val header = parseCsvLine(lines.first())
        return lines.drop(1).mapIndexed { index, line ->
            val values = parseCsvLine(line)
            require(values.size == header.size) { "Malformed reference row ${index + 2} in $path" }
            header.zip(values).toMap()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                c == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    private fun Map<String, String>.double(name: String): Double = getValue(name).toDouble()

    private fun assertClose(label: String, expected: Double, actual: Double) {
        assertTrue("$label expected finite: $expected", expected.isFinite())
        assertTrue("$label actual finite: $actual", actual.isFinite())
        val tolerance = max(1e-9, 1e-8 * max(1.0, abs(expected)))
        assertEquals(label, expected, actual, tolerance)
    }
}
