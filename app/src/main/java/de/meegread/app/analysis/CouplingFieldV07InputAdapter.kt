package de.meegread.app.analysis

import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegRecording

/**
 * Input boundary around the frozen v0.7 scientific engine.
 *
 * Sensor/MEG recordings pass through unchanged. Preprocessed source-space node time series are
 * accepted only after the file explicitly declares source space AND explicitly declares which
 * channels are source nodes. The adapter creates an internal compatibility view with EEG channel
 * types because the frozen engine intentionally accepts only EEG/MEG channel types. The imported
 * recording itself is never retyped or mutated.
 *
 * This class performs no inverse solution, source reconstruction, atlas projection or filtering.
 */
object CouplingFieldV07InputAdapter {
    const val VERSION = "v07-input-adapter-1.0"

    enum class InputProfile {
        DIRECT_SENSOR_OR_MEG,
        PREPROCESSED_SOURCE_NODES
    }

    data class PreparedInput(
        val recording: MeegRecording,
        val profile: InputProfile,
        val selectedSourceNodes: List<String>
    )

    data class Analysis(
        val result: CouplingFieldV07Engine.Result,
        val inputProfile: InputProfile,
        val selectedSourceNodes: List<String>
    )

    fun prepare(recording: MeegRecording): PreparedInput? {
        if (!declaresSourceSpace(recording)) {
            return PreparedInput(recording, InputProfile.DIRECT_SENSOR_OR_MEG, emptyList())
        }

        val selectAll = recording.metadata["source_nodes_all_channels"]
            ?.trim()
            ?.equals("true", ignoreCase = true) == true
        val explicit = recording.metadata["source_nodes"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()

        // Ambiguous source-node contracts are rejected rather than guessed.
        if (selectAll == explicit.isNotEmpty()) return null

        val selected = if (selectAll) {
            recording.channels.keys.sorted()
        } else {
            if (explicit.any { it !in recording.channels }) return null
            explicit.sorted()
        }
        if (selected.size < 2) return null

        val selectedChannels = linkedMapOf<String, List<Double>>()
        val selectedInfo = linkedMapOf<String, ChannelInfo>()
        selected.forEach { name ->
            val samples = recording.channels[name] ?: return null
            selectedChannels[name] = samples
            val original = recording.channelInfo[name]
            selectedInfo[name] = if (original == null) {
                ChannelInfo(
                    name = name,
                    type = ChannelType.EEG,
                    unit = "a.u.",
                    samplingRateHz = recording.sampleRateHz
                )
            } else {
                original.copy(type = ChannelType.EEG)
            }
        }

        val sampleCount = selectedChannels.values.maxOfOrNull { it.size } ?: return null
        val compatibilityView = recording.copy(
            channels = selectedChannels,
            channelInfo = selectedInfo,
            sampleCount = sampleCount,
            durationSeconds = if (recording.sampleRateHz > 0.0) sampleCount / recording.sampleRateHz else 0.0,
            metadata = recording.metadata + mapOf(
                "v07_input_adapter_version" to VERSION,
                "v07_input_profile" to InputProfile.PREPROCESSED_SOURCE_NODES.name,
                "v07_selected_source_nodes" to selected.joinToString(",")
            )
        )
        return PreparedInput(
            recording = compatibilityView,
            profile = InputProfile.PREPROCESSED_SOURCE_NODES,
            selectedSourceNodes = selected
        )
    }

    fun analyzeDetailed(
        recording: MeegRecording,
        requestedWindowSamples: Int = CouplingFieldV07Engine.DEFAULT_WINDOW_SAMPLES,
        maxScales: Int = CouplingFieldV07Engine.DEFAULT_MAX_SCALES
    ): Analysis? {
        val prepared = prepare(recording) ?: return null
        val raw = CouplingFieldV07Engine.analyze(
            prepared.recording,
            requestedWindowSamples = requestedWindowSamples,
            maxScales = maxScales
        ) ?: return null
        val result = if (prepared.profile == InputProfile.PREPROCESSED_SOURCE_NODES) {
            raw.copy(
                warnings = raw.warnings +
                    "Quellraum-Eingang sind bereits vorverarbeitete Knotenzeitreihen; die App führt keine Quellenrekonstruktion oder inverse Lösung durch."
            )
        } else {
            raw
        }
        return Analysis(result, prepared.profile, prepared.selectedSourceNodes)
    }

    fun analyze(
        recording: MeegRecording,
        requestedWindowSamples: Int = CouplingFieldV07Engine.DEFAULT_WINDOW_SAMPLES,
        maxScales: Int = CouplingFieldV07Engine.DEFAULT_MAX_SCALES
    ): CouplingFieldV07Engine.Result? = analyzeDetailed(
        recording,
        requestedWindowSamples,
        maxScales
    )?.result

    fun declaresSourceSpace(recording: MeegRecording): Boolean = listOfNotNull(
        recording.metadata["space"],
        recording.metadata["analysis_space"],
        recording.metadata["source_space"]
    ).joinToString(" ").lowercase().contains("source")
}
