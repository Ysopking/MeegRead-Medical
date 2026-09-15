package de.meegread.app.analysis

import de.meegread.app.data.EdfBdfParser
import de.meegread.app.model.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.max

class CouplingFieldV07EegmmidbParityTest {
    @Test
    fun preregisteredEegmmidbS001R02MatchesIndependentPythonOracleAcrossDeepScaleLadder() {
        assumeTrue(
            "Dedicated EEGMMIDB 64-channel validation workflow only",
            System.getenv("V07_EEGMMIDB_VALIDATION") == "true"
        )

        val edfPath = requiredPath("V07_EEGMMIDB_EDF")
        val referenceDir = requiredPath("V07_EEGMMIDB_REFERENCE_DIR")
        val recording = EdfBdfParser.parse(edfPath.toFile())

        assertEquals(160.0, recording.sampleRateHz, 0.0)
        assertEquals(64, recording.channelCount)
        assertTrue(recording.channels.keys.none { it.contains("Annotations", ignoreCase = true) })
        recording.channels.keys.forEach { name ->
            assertEquals("EEG channel type for $name", ChannelType.EEG, recording.infoFor(name).type)
        }

        val result = CouplingFieldV07Engine.analyze(recording)
        assertNotNull(result)
        val actualResult = result ?: error("v0.7 analysis unexpectedly returned null")

        assertEquals("coupling-field-v0.7-adapter-1.0", actualResult.version)
        assertEquals(CouplingFieldV07Engine.OperationalSpace.SENSOR_SPACE_PROXY, actualResult.operationalSpace)
        assertEquals(160.0, actualResult.sampleRateHz, 0.0)
        assertEquals(2048, actualResult.analysisWindowSamples)
        assertEquals(64, actualResult.sourceChannels.size)
        assertEquals(listOf(64, 32, 16, 8, 4, 2, 1), actualResult.scales.map { it.nodeCount })
        assertTrue(actualResult.finiteInputFraction in 0.0..1.0)

        val expectedScales = readCsv(referenceDir.resolve("scales.csv"))
        assertEquals(actualResult.scales.size, expectedScales.size)
        expectedScales.forEachIndexed { index, row ->
            val actual = actualResult.scales[index]
            assertEquals(row.getValue("level").toInt(), actual.level)
            assertEquals(row.getValue("nodeCount").toInt(), actual.nodeCount)
            assertClose("scale[$index].kappa", row.double("kappa"), actual.kappa)
            assertClose("scale[$index].directionCoherence", row.double("directionCoherence"), actual.directionCoherence)
            assertClose("scale[$index].gamma", row.double("gamma"), actual.gamma)
            assertClose("scale[$index].flowMagnitudeP", row.double("flowMagnitudeP"), actual.flowMagnitudeP)
            assertClose("scale[$index].couplingLossQ", row.double("couplingLossQ"), actual.couplingLossQ)
            assertClose("scale[$index].chiDyn", row.double("chiDyn"), actual.chiDyn)
            assertClose("scale[$index].stabilityReserve", row.double("stabilityReserve"), actual.stabilityReserve)
            assertClose("scale[$index].meanPhaseLocking", row.double("meanPhaseLocking"), actual.meanPhaseLocking)
        }

        val expectedPairs = readCsv(referenceDir.resolve("pairs.csv"))
        val expectedPairsByLevel = expectedPairs.groupBy { it.getValue("level").toInt() }
        val expectedBaseLabels = expectedPairsByLevel.getValue(0)
            .flatMap { listOf(it.getValue("nodeA"), it.getValue("nodeB")) }
            .distinct()
            .sorted()
        assertEquals(expectedBaseLabels, actualResult.sourceChannels)

        actualResult.scales.forEach { scale ->
            val expectedAtScale = expectedPairsByLevel[scale.level].orEmpty()
            assertEquals("pair count at scale ${scale.level}", expectedAtScale.size, scale.pairs.size)
            expectedAtScale.forEachIndexed { index, row ->
                val actual = scale.pairs[index]
                assertEquals("pair nodeA at ${scale.level}/$index", row.getValue("nodeA"), actual.nodeA)
                assertEquals("pair nodeB at ${scale.level}/$index", row.getValue("nodeB"), actual.nodeB)
                assertClose("pair phase at ${scale.level}/$index", row.double("phaseTransportRad"), actual.phaseTransportRad)
                assertClose("pair PLV at ${scale.level}/$index", row.double("phaseLocking"), actual.phaseLocking)
                assertClose("pair NMI at ${scale.level}/$index", row.double("informationOverlap"), actual.informationOverlap)
                assertClose("pair bundle at ${scale.level}/$index", row.double("bundleWeight"), actual.bundleWeight)
            }
        }

        val terminal = actualResult.scales.last()
        assertEquals(1, terminal.nodeCount)
        assertTrue(terminal.pairs.isEmpty())
        assertFalse(actualResult.sourceChannels.any { it.isBlank() })
        assertTrue(actualResult.warnings.any { it.contains("Sensorraum") })
        assertTrue(actualResult.warnings.any { it.contains("keine Diagnose") })
    }

    private fun requiredPath(name: String): Path {
        val value = System.getenv(name)
        require(!value.isNullOrBlank()) { "$name must be set by the dedicated validation workflow" }
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
