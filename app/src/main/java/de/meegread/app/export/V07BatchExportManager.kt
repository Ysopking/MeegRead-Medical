package de.meegread.app.export

import android.content.Context
import android.net.Uri
import de.meegread.app.analysis.CouplingFieldV07BatchValidator
import de.meegread.app.model.MeegRecording
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a self-contained v0.7 batch-validation bundle without embedding the raw recordings. */
object V07BatchExportManager {
    fun writeZip(
        context: Context,
        uri: Uri,
        recordings: List<MeegRecording>,
        requestedWindowSamples: Int = de.meegread.app.analysis.CouplingFieldV07Engine.DEFAULT_WINDOW_SAMPLES,
        maxScales: Int = de.meegread.app.analysis.CouplingFieldV07Engine.DEFAULT_MAX_SCALES
    ) {
        val batch = CouplingFieldV07BatchValidator.run(recordings, requestedWindowSamples, maxScales)
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("v0.7-Batch-Zieldatei konnte nicht geöffnet werden.")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.writeText("manifest.json", batch.toManifestJson())
            zip.writeText("scales.csv", batch.toScaleCsv())
            batch.items.forEach { item ->
                val audit = item.audit ?: return@forEach
                val safeName = item.recordingName
                    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    .trim('_')
                    .ifBlank { "recording" }
                zip.writeText(
                    "audits/%04d_%s_v07_audit.json".format(item.batchIndex, safeName),
                    audit.toJson()
                )
            }
        }
    }

    private fun ZipOutputStream.writeText(path: String, text: String) {
        putNextEntry(ZipEntry(path).apply { time = 0L })
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
