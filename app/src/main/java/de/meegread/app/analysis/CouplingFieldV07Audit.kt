package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import java.security.MessageDigest

/**
 * Reproducibility envelope for the frozen v0.7 coupling-field adapter.
 *
 * This class does not alter the scientific operator. It records the exact run configuration,
 * frozen source identities and every exported scale/pair value in a deterministic JSON document.
 */
object CouplingFieldV07Audit {
    const val SCHEMA = "meegread.v07.audit.v1"
    const val ENGINE_BLOB_SHA1 = "be472fb4939d723dc4133f3652a8deabee470a04"
    const val SIGNAL_ENGINE_BLOB_SHA1 = "e6f25721ad18be07666bf38de4921de6ee914a21"

    private val algorithmDescriptor = listOf(
        "schema=meegread.v07.algorithm-fingerprint.v1",
        "engine_version=${CouplingFieldV07Engine.VERSION}",
        "engine_blob_sha1=$ENGINE_BLOB_SHA1",
        "signal_engine_blob_sha1=$SIGNAL_ENGINE_BLOB_SHA1",
        "default_window_samples=${CouplingFieldV07Engine.DEFAULT_WINDOW_SAMPLES}",
        "default_max_scales=${CouplingFieldV07Engine.DEFAULT_MAX_SCALES}",
        "filter=biquad_highpass_1Hz_then_lowpass_min(40Hz,0.45*fs)",
        "hilbert=truncated_odd_kernel_radius_31",
        "nmi_bins=8",
        "bundle_weight=phase_locking*information_overlap",
        "gamma=kappa*direction_coherence",
        "q=P*(1-gamma)",
        "chi_dyn=ridge_VAR1_Gelfand80",
        "var_ridge=0.001",
        "coarse_grain=greedy_desc_bundle_weight_then_sorted_label"
    ).joinToString("\n")

    val algorithmFingerprintSha256: String by lazy { sha256(algorithmDescriptor) }

    data class Snapshot(
        val recordingName: String,
        val engineVersion: String,
        val inputAdapterVersion: String,
        val inputProfile: CouplingFieldV07InputAdapter.InputProfile,
        val selectedSourceNodes: List<String>,
        val algorithmFingerprintSha256: String,
        val configSha256: String,
        val requestedWindowSamples: Int,
        val maxScales: Int,
        val result: CouplingFieldV07Engine.Result
    ) {
        fun toJson(): String = buildString {
            append("{\n")
            field("schema", SCHEMA, comma = true, indent = 1)
            field("recordingName", recordingName, comma = true, indent = 1)
            field("engineVersion", engineVersion, comma = true, indent = 1)
            field("inputAdapterVersion", inputAdapterVersion, comma = true, indent = 1)
            field("inputProfile", inputProfile.name, comma = true, indent = 1)
            field("algorithmFingerprintSha256", algorithmFingerprintSha256, comma = true, indent = 1)
            field("engineBlobSha1", ENGINE_BLOB_SHA1, comma = true, indent = 1)
            field("signalEngineBlobSha1", SIGNAL_ENGINE_BLOB_SHA1, comma = true, indent = 1)
            field("configSha256", configSha256, comma = true, indent = 1)
            numberField("requestedWindowSamples", requestedWindowSamples.toString(), comma = true, indent = 1)
            numberField("maxScales", maxScales.toString(), comma = true, indent = 1)
            field("operationalSpace", result.operationalSpace.name, comma = true, indent = 1)
            numberField("sampleRateHz", result.sampleRateHz.toString(), comma = true, indent = 1)
            numberField("analysisWindowSamples", result.analysisWindowSamples.toString(), comma = true, indent = 1)
            numberField("finiteInputFraction", result.finiteInputFraction.toString(), comma = true, indent = 1)
            append("  \"selectedSourceNodes\": [")
            selectedSourceNodes.forEachIndexed { index, channel ->
                if (index > 0) append(", ")
                appendQuoted(channel)
            }
            append("],\n")
            append("  \"sourceChannels\": [")
            result.sourceChannels.forEachIndexed { index, channel ->
                if (index > 0) append(", ")
                appendQuoted(channel)
            }
            append("],\n")
            append("  \"scales\": [\n")
            result.scales.forEachIndexed { scaleIndex, scale ->
                append("    {\n")
                numberField("level", scale.level.toString(), true, 3)
                numberField("nodeCount", scale.nodeCount.toString(), true, 3)
                numberField("kappa", scale.kappa.toString(), true, 3)
                numberField("directionCoherence", scale.directionCoherence.toString(), true, 3)
                numberField("gamma", scale.gamma.toString(), true, 3)
                numberField("flowMagnitudeP", scale.flowMagnitudeP.toString(), true, 3)
                numberField("couplingLossQ", scale.couplingLossQ.toString(), true, 3)
                numberField("chiDyn", scale.chiDyn.toString(), true, 3)
                numberField("stabilityReserve", scale.stabilityReserve.toString(), true, 3)
                numberField("meanPhaseLocking", scale.meanPhaseLocking.toString(), true, 3)
                append("      \"pairs\": [\n")
                scale.pairs.forEachIndexed { pairIndex, pair ->
                    append("        {")
                    inlineField("nodeA", pair.nodeA, true)
                    inlineField("nodeB", pair.nodeB, true)
                    inlineNumber("phaseTransportRad", pair.phaseTransportRad.toString(), true)
                    inlineNumber("phaseLocking", pair.phaseLocking.toString(), true)
                    inlineNumber("informationOverlap", pair.informationOverlap.toString(), true)
                    inlineNumber("bundleWeight", pair.bundleWeight.toString(), false)
                    append("}")
                    if (pairIndex != scale.pairs.lastIndex) append(',')
                    append('\n')
                }
                append("      ]\n")
                append("    }")
                if (scaleIndex != result.scales.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"warnings\": [")
            result.warnings.forEachIndexed { index, warning ->
                if (index > 0) append(", ")
                appendQuoted(warning)
            }
            append("]\n")
            append("}\n")
        }
    }

    fun build(
        recording: MeegRecording,
        requestedWindowSamples: Int = CouplingFieldV07Engine.DEFAULT_WINDOW_SAMPLES,
        maxScales: Int = CouplingFieldV07Engine.DEFAULT_MAX_SCALES
    ): Snapshot? {
        val analysis = CouplingFieldV07InputAdapter.analyzeDetailed(
            recording,
            requestedWindowSamples,
            maxScales
        ) ?: return null
        val result = analysis.result
        val config = listOf(
            "schema=$SCHEMA",
            "engine_version=${result.version}",
            "input_adapter_version=${CouplingFieldV07InputAdapter.VERSION}",
            "input_profile=${analysis.inputProfile.name}",
            "algorithm_sha256=$algorithmFingerprintSha256",
            "operational_space=${result.operationalSpace.name}",
            "sample_rate_hz=${java.lang.Double.toHexString(result.sampleRateHz)}",
            "requested_window_samples=$requestedWindowSamples",
            "analysis_window_samples=${result.analysisWindowSamples}",
            "max_scales=$maxScales",
            "selected_source_nodes=${analysis.selectedSourceNodes.joinToString("\u001f")}",
            "source_channels=${result.sourceChannels.joinToString("\u001f")}"
        ).joinToString("\n")
        return Snapshot(
            recordingName = recording.name,
            engineVersion = result.version,
            inputAdapterVersion = CouplingFieldV07InputAdapter.VERSION,
            inputProfile = analysis.inputProfile,
            selectedSourceNodes = analysis.selectedSourceNodes,
            algorithmFingerprintSha256 = algorithmFingerprintSha256,
            configSha256 = sha256(config),
            requestedWindowSamples = requestedWindowSamples,
            maxScales = maxScales,
            result = result
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun StringBuilder.field(name: String, value: String, comma: Boolean, indent: Int) {
        append("  ".repeat(indent)); appendQuoted(name); append(": "); appendQuoted(value); if (comma) append(','); append('\n')
    }

    private fun StringBuilder.numberField(name: String, value: String, comma: Boolean, indent: Int) {
        append("  ".repeat(indent)); appendQuoted(name); append(": "); append(value); if (comma) append(','); append('\n')
    }

    private fun StringBuilder.inlineField(name: String, value: String, comma: Boolean) {
        appendQuoted(name); append(':'); appendQuoted(value); if (comma) append(',')
    }

    private fun StringBuilder.inlineNumber(name: String, value: String, comma: Boolean) {
        appendQuoted(name); append(':'); append(value); if (comma) append(',')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
