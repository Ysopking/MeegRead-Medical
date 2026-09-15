package de.meegread.app.export

import android.content.Context
import android.net.Uri
import de.meegread.app.analysis.CouplingFieldV07Audit
import de.meegread.app.model.MeegRecording

object V07AuditExportManager {
    fun writeJson(context: Context, uri: Uri, recording: MeegRecording) {
        val snapshot = CouplingFieldV07Audit.build(recording)
            ?: error("v0.7-Audit konnte für diese Aufnahme nicht berechnet werden.")
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(snapshot.toJson())
        } ?: error("v0.7-Audit-Zieldatei konnte nicht geöffnet werden.")
    }
}
