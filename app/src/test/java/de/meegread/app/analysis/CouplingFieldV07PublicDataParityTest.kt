package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
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

class CouplingFieldV07PublicDataParityTest {
    @Test
    fun pinnedPublicMuseRestingStateMatchesIndependentPythonOracle() {
        val enabled = System.getenv("V07_PUBLIC_VALIDATION") == "true"
        assumeTrue("Dedicated public-data validation workflow only", enabled)

        val csvPath = requiredPath("V07_PUBLIC_EEG_CSV")
        val referenceDir = requiredPath("V07_PUBLIC_REFERENCE_DIR")
        val recording = loadWebMuse(csvPath)
        val result = CouplingFieldV07Engine.analyze(recording)

        assertNotNull(result)
        val actualResult = result ?: error("v0.7 analysis unexpectedly returned null")
        assertEquals("coupling-field-v0.7-adapter-1.0", actualResult.version)
        assertEquals(CouplingFieldV07Engine.OperationalSpace.SENSOR_SPACE_PROXY, actualResult.operationalSpace)
        assertEquals(256.0, actualResult.sampleRateHz, 0.0)
        assertEquals(2048, actualResult.analysisWindowSamples)
        assertEquals(listOf("AF7", "AF8", "TP10", "TP9"), actualResult.sourceChannels)
        assertEquals(listOf(4, 2, 1), actualResult.scales.map { it.nodeCount })
        assertEquals(1.0, actualResult.finiteInputFraction, 0.0)

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

        val expectedPairsByLevel = readCsv(referenceDir.resolve("pairs.csv"))
            .groupBy { it.getValue("level").toInt() }
        actualResult.scales.forEach { scale ->
            val expectedPairs = expectedPairsByLevel[scale.level].orEmpty()
            assertEquals("pair count at scale ${scale.level}", expectedPairs.size, scale.pairs.size)
            expectedPairs.forEachIndexed { index, row ->
                val actual = scale.pairs[index]
                assertEquals("pair nodeA at ${scale.level}/$index", row.getValue("nodeA"), actual.nodeA)
                assertEquals("pair nodeB at ${scale.level}/$index", row.getValue("nodeB"), actual.nodeB)
                assertClose("pair phase at ${scale.level}/$index", row.double("phaseTransportRad"), actual.phaseTransportRad)
                assertClose("pair PLV at ${scale.level}/$index", row.double("phaseLocking"), actual.phaseLocking)
                assertClose("pair NMI at ${scale.level}/$index", row.double("informationOverlap"), actual.informationOverlap)
                assertClose("pair bundle at ${scale.level}/$index", row.double("bundleWeight"), actual.bundleWeight)
            }
        }

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

    private fun loadWebMuse(path: Path): MeegRecording {
        val lines = Files.readAllLines(path)
        require(lines.size > 2048) { "Pinned Muse dataset must contain more than 2048 samples" }
        val header = lines.first().split(',')
        val sourceHeaders = linkedMapOf(
            "TP9" to "TP9 (left ear)",
            "AF7" to "AF7 (left forehead)",
            "AF8" to "AF8 (right forehead)",
            "TP10" to "TP10 (right ear)"
        )
        val indexByName = header.withIndex().associate { it.value to it.index }
        sourceHeaders.values.forEach { source -> require(indexByName.containsKey(source)) { "Missing $source" } }

        val channels = linkedMapOf<String, MutableList<Double>>()
        sourceHeaders.keys.forEach { channels[it] = mutableListOf() }
        lines.drop(1).filter { it.isNotBlank() }.forEachIndexed { rowIndex, line ->
            val fields = line.split(',')
            require(fields.size == header.size) { "Malformed CSV row ${rowIndex + 2}" }
            sourceHeaders.forEach { (compact, source) ->
                channels.getValue(compact) += fields[indexByName.getValue(source)].toDouble()
            }
        }

        val immutable = channels.mapValues { it.value.toList() }
        val sampleCount = immutable.values.minOf { it.size }
        return MeegRecording(
            name = "web-muse-resting-state-pinned",
            modality = Modality.EEG,
            sampleRateHz = 256.0,
            channels = immutable,
            sampleCount = sampleCount,
            durationSeconds = sampleCount / 256.0,
            metadata = mapOf(
                "validation_source" to "itayinbarr/web-muse",
                "analysis_space" to "sensor-space"
            )
        )
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
