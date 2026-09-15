package de.meegread.app.analysis

import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
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

class CouplingFieldV07Ds003944KappaParityTest {
    @Test
    fun preregisteredDs003944ParityWindowMatchesIndependentPythonOracle() {
        assumeTrue(
            "Dedicated ds003944 kappa confirmatory workflow only",
            System.getenv("V07_DS003944_CONFIRMATORY") == "true"
        )
        val subject = System.getenv("V07_DS003944_SUBJECT") ?: error("V07_DS003944_SUBJECT must be set")
        require(subject.startsWith("sub-")) { "Unexpected participant id: $subject" }
        require(!subject.endsWith("A")) { "A-suffix participant excluded by preregistration: $subject" }
        val referenceDir = requiredPath("V07_DS003944_REFERENCE_DIR")
        val recording = readInput(referenceDir.resolve("parity_window/input.csv"), subject)

        val result = CouplingFieldV07Engine.analyze(recording)
        assertNotNull(result)
        val actual = result ?: error("v0.7 analysis unexpectedly returned null")
        assertEquals("coupling-field-v0.7-adapter-1.0", actual.version)
        assertEquals(CouplingFieldV07Engine.OperationalSpace.SENSOR_SPACE_PROXY, actual.operationalSpace)
        assertEquals(1000.0, actual.sampleRateHz, 0.0)
        assertEquals(2048, actual.analysisWindowSamples)
        assertEquals(19, actual.sourceChannels.size)
        assertEquals(listOf(19, 10, 5, 3, 2, 1), actual.scales.map { it.nodeCount })
        assertTrue(actual.finiteInputFraction in 0.0..1.0)

        val expectedScales = readCsv(referenceDir.resolve("parity_window/scales.csv"))
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

        val expectedPairs = readCsv(referenceDir.resolve("parity_window/pairs.csv")).groupBy { it.getValue("level").toInt() }
        actual.scales.forEach { scale ->
            val expected = expectedPairs[scale.level].orEmpty()
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

    private fun readInput(path: Path, subject: String): MeegRecording {
        val lines = Files.readAllLines(path).filter { it.isNotBlank() }
        require(lines.size == 2049) { "Expected header + 2048 samples in $path, got ${lines.size}" }
        val header = lines.first().split(',')
        require(header.first() == "sample") { "Unexpected input header: $header" }
        val names = header.drop(1)
        val expected = listOf("Fp1","Fp2","F7","F3","Fz","F4","F8","T3","C3","Cz","C4","T4","T5","P3","Pz","P4","T6","O1","O2")
        require(names == expected) { "Frozen channel order mismatch: $names" }
        val buffers = names.associateWith { ArrayList<Double>(2048) }.toMap(LinkedHashMap())
        lines.drop(1).forEachIndexed { rowIndex, line ->
            val parts = line.split(',')
            require(parts.size == names.size + 1) { "Malformed parity input row ${rowIndex + 2}" }
            require(parts[0].toInt() == rowIndex) { "Unexpected sample index at row ${rowIndex + 2}" }
            names.forEachIndexed { col, name -> buffers.getValue(name).add(parts[col + 1].toDouble()) }
        }
        val channels = LinkedHashMap<String, List<Double>>()
        names.forEach { channels[it] = buffers.getValue(it) }
        val info = names.associateWith { ChannelInfo(it, ChannelType.EEG, "a.u.", 1000.0) }
        return MeegRecording(
            name = "$subject-ds003944-parity",
            modality = Modality.EEG,
            sampleRateHz = 1000.0,
            channels = channels,
            sampleCount = 2048,
            durationSeconds = 2.048,
            channelInfo = info
        )
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
