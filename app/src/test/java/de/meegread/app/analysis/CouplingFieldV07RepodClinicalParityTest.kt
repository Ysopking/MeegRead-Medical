package de.meegread.app.analysis

import de.meegread.app.data.EdfBdfParser
import de.meegread.app.model.ChannelType
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

class CouplingFieldV07RepodClinicalParityTest {
    @Test
    fun preregisteredRepodTailWindowMatchesIndependentPythonOracle() {
        assumeTrue(
            "Dedicated RepOD clinical validation workflow only",
            System.getenv("V07_REPOD_CLINICAL") == "true"
        )
        val subject = System.getenv("V07_REPOD_SUBJECT")?.lowercase()
            ?: error("V07_REPOD_SUBJECT must be set")
        val allowed = (1..14).flatMap { listOf("h%02d".format(it), "s%02d".format(it)) }.toSet()
        require(subject in allowed) { "Subject outside frozen RepOD cohort: $subject" }
        val edfPath = requiredPath("V07_REPOD_EDF")
        val referenceDir = requiredPath("V07_REPOD_REFERENCE_DIR")
        val recording = EdfBdfParser.parse(edfPath.toFile())

        assertEquals(250.0, recording.sampleRateHz, 0.0)
        assertEquals(19, recording.channelCount)
        assertTrue(recording.channels.keys.none { it.contains("Annotations", ignoreCase = true) })
        recording.channels.keys.forEach { name ->
            assertEquals("EEG channel type for $name", ChannelType.EEG, recording.infoFor(name).type)
        }

        val result = CouplingFieldV07Engine.analyze(recording)
        assertNotNull(result)
        val actual = result ?: error("v0.7 analysis unexpectedly returned null")
        assertEquals("coupling-field-v0.7-adapter-1.0", actual.version)
        assertEquals(CouplingFieldV07Engine.OperationalSpace.SENSOR_SPACE_PROXY, actual.operationalSpace)
        assertEquals(250.0, actual.sampleRateHz, 0.0)
        assertEquals(2048, actual.analysisWindowSamples)
        assertEquals(19, actual.sourceChannels.size)
        assertEquals(listOf(19, 10, 5, 3, 2, 1), actual.scales.map { it.nodeCount })
        assertTrue(actual.finiteInputFraction in 0.0..1.0)

        val expectedScales = readCsv(referenceDir.resolve("scales.csv"))
        assertEquals(actual.scales.size, expectedScales.size)
        expectedScales.forEachIndexed { index, row ->
            val scale = actual.scales[index]
            assertEquals(row.getValue("level").toInt(), scale.level)
            assertEquals(row.getValue("nodeCount").toInt(), scale.nodeCount)
            assertClose("scale[$index].kappa", row.double("kappa"), scale.kappa)
            assertClose("scale[$index].directionCoherence", row.double("directionCoherence"), scale.directionCoherence)
            assertClose("scale[$index].gamma", row.double("gamma"), scale.gamma)
            assertClose("scale[$index].flowMagnitudeP", row.double("flowMagnitudeP"), scale.flowMagnitudeP)
            assertClose("scale[$index].couplingLossQ", row.double("couplingLossQ"), scale.couplingLossQ)
            assertClose("scale[$index].chiDyn", row.double("chiDyn"), scale.chiDyn)
            assertClose("scale[$index].stabilityReserve", row.double("stabilityReserve"), scale.stabilityReserve)
            assertClose("scale[$index].meanPhaseLocking", row.double("meanPhaseLocking"), scale.meanPhaseLocking)
        }

        val expectedPairsByLevel = readCsv(referenceDir.resolve("pairs.csv")).groupBy { it.getValue("level").toInt() }
        actual.scales.forEach { scale ->
            val expected = expectedPairsByLevel[scale.level].orEmpty()
            assertEquals("pair count at scale ${scale.level}", expected.size, scale.pairs.size)
            expected.forEachIndexed { index, row ->
                val pair = scale.pairs[index]
                assertEquals("pair nodeA at ${scale.level}/$index", row.getValue("nodeA"), pair.nodeA)
                assertEquals("pair nodeB at ${scale.level}/$index", row.getValue("nodeB"), pair.nodeB)
                assertClose("pair phase at ${scale.level}/$index", row.double("phaseTransportRad"), pair.phaseTransportRad)
                assertClose("pair PLV at ${scale.level}/$index", row.double("phaseLocking"), pair.phaseLocking)
                assertClose("pair NMI at ${scale.level}/$index", row.double("informationOverlap"), pair.informationOverlap)
                assertClose("pair bundle at ${scale.level}/$index", row.double("bundleWeight"), pair.bundleWeight)
            }
        }
        assertEquals(1, actual.scales.last().nodeCount)
        assertTrue(actual.scales.last().pairs.isEmpty())
        assertFalse(actual.sourceChannels.any { it.isBlank() })
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
