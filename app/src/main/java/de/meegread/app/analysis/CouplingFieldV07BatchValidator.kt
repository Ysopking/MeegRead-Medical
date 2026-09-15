package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import java.security.MessageDigest

/** Deterministic multi-recording validation runner around the frozen v0.7 operator. */
object CouplingFieldV07BatchValidator {
    const val VERSION = "v07-batch-validation-1.0"

    enum class Status { SUCCESS, NOT_ANALYZABLE, ERROR }

    data class Item(
        val batchIndex: Int,
        val recordingName: String,
        val status: Status,
        val errorClass: String? = null,
        val audit: CouplingFieldV07Audit.Snapshot? = null
    )

    data class BatchResult(
        val requestedWindowSamples: Int,
        val maxScales: Int,
        val algorithmFingerprintSha256: String,
        val batchResultSha256: String,
        val items: List<Item>
    ) {
        val successCount: Int get() = items.count { it.status == Status.SUCCESS }
        val failureCount: Int get() = items.size - successCount

        /** One row per scale; failures receive one row with empty scale metrics. */
        fun toScaleCsv(): String = buildString {
            append("batchIndex,recordingName,status,errorClass,inputProfile,operationalSpace,configSha256,algorithmFingerprintSha256,level,nodeCount,kappa,directionCoherence,gamma,flowMagnitudeP,couplingLossQ,chiDyn,stabilityReserve,meanPhaseLocking\n")
            items.forEach { item ->
                val audit = item.audit
                if (audit == null) {
                    appendCsvRow(listOf(
                        item.batchIndex.toString(), item.recordingName, item.status.name,
                        item.errorClass.orEmpty(), "", "", "", algorithmFingerprintSha256,
                        "", "", "", "", "", "", "", "", "", ""
                    ))
                } else {
                    audit.result.scales.forEach { scale ->
                        appendCsvRow(listOf(
                            item.batchIndex.toString(), item.recordingName, item.status.name,
                            item.errorClass.orEmpty(), audit.inputProfile.name,
                            audit.result.operationalSpace.name, audit.configSha256,
                            audit.algorithmFingerprintSha256, scale.level.toString(),
                            scale.nodeCount.toString(), scale.kappa.toString(),
                            scale.directionCoherence.toString(), scale.gamma.toString(),
                            scale.flowMagnitudeP.toString(), scale.couplingLossQ.toString(),
                            scale.chiDyn.toString(), scale.stabilityReserve.toString(),
                            scale.meanPhaseLocking.toString()
                        ))
                    }
                }
            }
        }

        fun toManifestJson(): String = buildString {
            append("{\n")
            append("  \"schema\": \"meegread.v07.batch-validation.v1\",\n")
            append("  \"batchValidatorVersion\": \"").append(VERSION).append("\",\n")
            append("  \"algorithmFingerprintSha256\": \"").append(algorithmFingerprintSha256).append("\",\n")
            append("  \"batchResultSha256\": \"").append(batchResultSha256).append("\",\n")
            append("  \"requestedWindowSamples\": ").append(requestedWindowSamples).append(",\n")
            append("  \"maxScales\": ").append(maxScales).append(",\n")
            append("  \"itemCount\": ").append(items.size).append(",\n")
            append("  \"successCount\": ").append(successCount).append(",\n")
            append("  \"failureCount\": ").append(failureCount).append(",\n")
            append("  \"orderPolicy\": \"caller order is preserved and hashed\",\n")
            append("  \"items\": [\n")
            items.forEachIndexed { index, item ->
                append("    {\"batchIndex\":").append(item.batchIndex)
                append(",\"recordingName\":").appendJsonString(item.recordingName)
                append(",\"status\":").appendJsonString(item.status.name)
                item.errorClass?.let { append(",\"errorClass\":").appendJsonString(it) }
                item.audit?.let { audit ->
                    append(",\"inputProfile\":").appendJsonString(audit.inputProfile.name)
                    append(",\"operationalSpace\":").appendJsonString(audit.result.operationalSpace.name)
                    append(",\"configSha256\":").appendJsonString(audit.configSha256)
                    append(",\"scaleCount\":").append(audit.result.scales.size)
                }
                append('}')
                if (index != items.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
    }

    fun run(
        recordings: List<MeegRecording>,
        requestedWindowSamples: Int = CouplingFieldV07Engine.DEFAULT_WINDOW_SAMPLES,
        maxScales: Int = CouplingFieldV07Engine.DEFAULT_MAX_SCALES
    ): BatchResult {
        val items = recordings.mapIndexed { index, recording ->
            try {
                val audit = CouplingFieldV07Audit.build(recording, requestedWindowSamples, maxScales)
                if (audit == null) {
                    Item(index, recording.name, Status.NOT_ANALYZABLE)
                } else {
                    Item(index, recording.name, Status.SUCCESS, audit = audit)
                }
            } catch (t: Throwable) {
                Item(index, recording.name, Status.ERROR, errorClass = t::class.java.name)
            }
        }
        val fingerprint = sha256(canonicalResultDescriptor(items, requestedWindowSamples, maxScales))
        return BatchResult(
            requestedWindowSamples = requestedWindowSamples,
            maxScales = maxScales,
            algorithmFingerprintSha256 = CouplingFieldV07Audit.algorithmFingerprintSha256,
            batchResultSha256 = fingerprint,
            items = items
        )
    }

    private fun canonicalResultDescriptor(
        items: List<Item>,
        requestedWindowSamples: Int,
        maxScales: Int
    ): String = buildString {
        append("schema=meegread.v07.batch-result-fingerprint.v1\n")
        append("batch_validator_version=").append(VERSION).append('\n')
        append("algorithm_sha256=").append(CouplingFieldV07Audit.algorithmFingerprintSha256).append('\n')
        append("requested_window_samples=").append(requestedWindowSamples).append('\n')
        append("max_scales=").append(maxScales).append('\n')
        append("item_count=").append(items.size).append('\n')
        items.forEach { item ->
            append("item=").append(item.batchIndex).append('|')
                .append(item.recordingName).append('|').append(item.status.name).append('|')
                .append(item.errorClass.orEmpty()).append('|')
            val audit = item.audit
            if (audit != null) {
                append(audit.inputProfile.name).append('|')
                    .append(audit.result.operationalSpace.name).append('|')
                    .append(audit.configSha256)
                audit.result.scales.forEach { scale ->
                    append('|').append(scale.level)
                    append(':').append(scale.nodeCount)
                    append(':').append(java.lang.Double.toHexString(scale.kappa))
                    append(':').append(java.lang.Double.toHexString(scale.directionCoherence))
                    append(':').append(java.lang.Double.toHexString(scale.gamma))
                    append(':').append(java.lang.Double.toHexString(scale.flowMagnitudeP))
                    append(':').append(java.lang.Double.toHexString(scale.couplingLossQ))
                    append(':').append(java.lang.Double.toHexString(scale.chiDyn))
                    append(':').append(java.lang.Double.toHexString(scale.stabilityReserve))
                    append(':').append(java.lang.Double.toHexString(scale.meanPhaseLocking))
                }
            }
            append('\n')
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun StringBuilder.appendCsvRow(values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(csvEscape(value))
        }
        append('\n')
    }

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
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
        return this
    }
}
