package de.meegread.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.meegread.app.model.MeegRecording
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

object RecordingImporter {
    suspend fun import(context: Context, uri: Uri): MeegRecording {
        val displayName = displayName(context, uri) ?: "recording"
        val lower = displayName.lowercase(Locale.ROOT)
        val cached = File(context.cacheDir, "import_${System.nanoTime()}_${sanitize(displayName)}")
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(cached).use { input.copyTo(it) } }
            ?: error("Datei konnte nicht geöffnet werden.")
        val compressed = lower.endsWith(".gz")
        val baseLower = if (compressed) lower.removeSuffix(".gz") else lower
        val actualFile = if (compressed) {
            val decompressed = File(context.cacheDir, cached.name.removeSuffix(".gz"))
            GZIPInputStream(cached.inputStream().buffered()).use { input -> decompressed.outputStream().buffered().use { input.copyTo(it) } }
            cached.delete(); decompressed
        } else cached
        return try {
            when {
                baseLower.endsWith(".edf") || baseLower.endsWith(".bdf") -> EdfBdfParser.parse(actualFile)
                baseLower.endsWith(".fif") -> FiffParser.parse(actualFile)
                baseLower.endsWith(".mgr") -> RecordingCodec.read(actualFile)
                baseLower.endsWith(".json") -> JsonRecordingParser.parse(actualFile)
                else -> SignalFileParser.parse(displayName.removeSuffix(".gz"), actualFile.readText())
            }
        } finally { actualFile.delete() }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
