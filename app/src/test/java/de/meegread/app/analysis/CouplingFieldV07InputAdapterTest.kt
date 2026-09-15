package de.meegread.app.analysis

import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CouplingFieldV07InputAdapterTest {
    @Test
    fun ordinarySensorInputIsExactPassThrough() {
        val recording = sensorRecording()
        val direct = CouplingFieldV07Engine.analyze(recording)
        val adapted = CouplingFieldV07InputAdapter.analyze(recording)
        assertEquals(direct, adapted)
    }

    @Test
    fun explicitSourceParcelsCanBeAnalyzedWithoutMutatingImportedTypes() {
        val recording = sourceRecording(
            metadata = mapOf(
                "analysis_space" to "source-space",
                "source_nodes_all_channels" to "true",
                "source_atlas" to "test-atlas"
            )
        )
        assertTrue(recording.channelInfo.values.all { it.type == ChannelType.OTHER })

        val analysis = CouplingFieldV07InputAdapter.analyzeDetailed(recording)!!

        assertEquals(CouplingFieldV07InputAdapter.InputProfile.PREPROCESSED_SOURCE_NODES, analysis.inputProfile)
        assertEquals(CouplingFieldV07Engine.OperationalSpace.SOURCE_SPACE, analysis.result.operationalSpace)
        assertEquals(listOf(4, 2, 1), analysis.result.scales.take(3).map { it.nodeCount })
        assertEquals(listOf("ParcelA", "ParcelB", "ParcelC", "ParcelD"), analysis.result.sourceChannels)
        assertTrue(analysis.result.warnings.any { it.contains("keine Quellenrekonstruktion") })
        assertTrue(recording.channelInfo.values.all { it.type == ChannelType.OTHER })
    }

    @Test
    fun sourceSpaceWithoutExplicitNodeSelectionIsRejected() {
        val recording = sourceRecording(metadata = mapOf("analysis_space" to "source-space"))
        assertNull(CouplingFieldV07InputAdapter.analyze(recording))
    }

    @Test
    fun explicitSourceNodeListExcludesAuxiliaryChannels() {
        val base = sourceRecording(
            metadata = mapOf(
                "analysis_space" to "source-space",
                "source_nodes" to "ParcelD, ParcelB, ParcelA, ParcelC"
            )
        )
        val auxSamples = List(base.sampleCount) { i -> sin(2.0 * PI * 2.0 * i / base.sampleRateHz) }
        val recording = base.copy(
            channels = base.channels + ("AUX" to auxSamples),
            channelInfo = base.channelInfo + ("AUX" to ChannelInfo("AUX", ChannelType.MISC))
        )

        val result = CouplingFieldV07InputAdapter.analyze(recording)!!

        assertEquals(listOf("ParcelA", "ParcelB", "ParcelC", "ParcelD"), result.sourceChannels)
        assertTrue("AUX" !in result.sourceChannels)
    }

    @Test
    fun ambiguousAllChannelsAndExplicitListIsRejected() {
        val recording = sourceRecording(
            metadata = mapOf(
                "analysis_space" to "source-space",
                "source_nodes_all_channels" to "true",
                "source_nodes" to "ParcelA,ParcelB"
            )
        )
        assertNull(CouplingFieldV07InputAdapter.analyze(recording))
    }

    private fun sensorRecording(): MeegRecording {
        val fs = 128.0
        val samples = 1024
        val channels = linkedMapOf<String, List<Double>>()
        repeat(4) { index ->
            channels["F${index + 1}"] = List(samples) { t ->
                sin(2.0 * PI * (8.0 + index) * t / fs + index * 0.1)
            }
        }
        return MeegRecording(
            name = "sensor-test",
            modality = Modality.EEG,
            sampleRateHz = fs,
            channels = channels,
            sampleCount = samples,
            durationSeconds = samples / fs
        )
    }

    private fun sourceRecording(metadata: Map<String, String>): MeegRecording {
        val fs = 128.0
        val samples = 1024
        val names = listOf("ParcelA", "ParcelB", "ParcelC", "ParcelD")
        val channels = names.mapIndexed { index, name ->
            name to List(samples) { t -> sin(2.0 * PI * (7.0 + index) * t / fs + index * 0.17) }
        }.toMap(LinkedHashMap())
        val info = names.associateWith { ChannelInfo(it, ChannelType.OTHER, "a.u.", fs) }
        return MeegRecording(
            name = "source-parcels",
            modality = Modality.UNKNOWN,
            sampleRateHz = fs,
            channels = channels,
            sampleCount = samples,
            durationSeconds = samples / fs,
            channelInfo = info,
            metadata = metadata
        )
    }
}
