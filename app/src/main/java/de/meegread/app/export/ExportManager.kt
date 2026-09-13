package de.meegread.app.export

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.JsonWriter
import de.meegread.app.analysis.SignalAnalysisEngine
import de.meegread.app.analysis.ThermodynamicLoadEngine
import de.meegread.app.data.RecordingCodec
import de.meegread.app.model.MeegRecording
import java.io.File
import java.io.OutputStreamWriter
import java.util.Locale

object ExportManager {
    fun writeRawCsv(context: Context, uri: Uri, recording: MeegRecording) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            val names = recording.channels.keys.toList(); writer.append("time_s"); names.forEach { writer.append(',').append(csv(it)) }; writer.newLine()
            for (sample in 0 until recording.sampleCount) {
                writer.append(format(sample / recording.sampleRateHz)); names.forEach { name -> writer.append(','); recording.channels[name]?.getOrNull(sample)?.let { writer.append(format(it)) } }; writer.newLine()
            }
        } ?: error("CSV-Zieldatei konnte nicht geöffnet werden.")
    }

    fun writeSummaryCsv(context: Context, uri: Uri, recording: MeegRecording) {
        val metrics = SignalAnalysisEngine.analyze(recording).associateBy { it.channel }
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.appendLine("channel,mean,rms,stddev,peak_to_peak,dominant_hz,delta_rel,theta_rel,alpha_rel,beta_rel,gamma_rel")
            recording.channels.forEach { (name, samples) ->
                val metric = metrics.getValue(name); val powers = SignalAnalysisEngine.bandPowers(samples, recording.sampleRateHz).associateBy { it.band.name }
                writer.append(csv(name)).append(',').append(format(metric.mean)).append(',').append(format(metric.rms)).append(',').append(format(metric.standardDeviation)).append(',').append(format(metric.peakToPeak)).append(',').append(metric.dominantFrequencyHz?.let(::format) ?: "")
                listOf("DELTA","THETA","ALPHA","BETA","GAMMA").forEach { band -> writer.append(',').append(format(powers[band]?.relativePower ?: 0.0)) }; writer.newLine()
            }
            ThermodynamicLoadEngine.analyze(recording)?.let { t ->
                writer.appendLine()
                writer.appendLine("experimental_model,algorithm_version,omega_model_parameter,final_raw_load,final_bounded_load,omega_breach,mean_faa")
                writer.append("MMSI-research,").append(ThermodynamicLoadEngine.ALGORITHM_VERSION).append(',').append(format(t.omegaCrit)).append(',').append(format(t.finalRawLoad)).append(',').append(format(t.finalBoundedLoad)).append(',').append(t.omegaBreach.toString()).append(',').append(format(t.meanFaa)).appendLine()
            }
        } ?: error("CSV-Zieldatei konnte nicht geöffnet werden.")
    }

    fun writeJson(context: Context, uri: Uri, recording: MeegRecording) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            JsonWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { json ->
                json.setIndent("  "); json.beginObject(); json.name("schema").value("meegread.medical.recording.v1"); json.name("name").value(recording.name); json.name("modality").value(recording.modality.name); json.name("sampleRateHz").value(recording.sampleRateHz); json.name("sampleCount").value(recording.sampleCount.toLong())
                json.name("metadata").beginObject(); recording.metadata.forEach { (k,v) -> json.name(k).value(v) }; json.endObject()
                json.name("events").beginArray(); recording.events.forEach { e -> json.beginObject(); json.name("sample").value(e.sample.toLong()); json.name("onsetSeconds").value(e.onsetSeconds); json.name("durationSeconds").value(e.durationSeconds); e.code?.let { json.name("code").value(it.toLong()) }; json.name("label").value(e.label); json.endObject() }; json.endArray()
                json.name("channels").beginArray(); recording.channels.forEach { (name, samples) -> val info = recording.infoFor(name); json.beginObject(); json.name("name").value(name); json.name("type").value(info.type.name); json.name("unit").value(info.unit); info.position?.let { p -> json.name("position").beginArray().value(p.x).value(p.y).value(p.z).endArray() }; json.name("samples").beginArray(); samples.forEach { json.value(it) }; json.endArray(); json.endObject() }; json.endArray()
                ThermodynamicLoadEngine.analyze(recording)?.let { t -> json.name("experimentalMmsi").beginObject(); json.name("algorithmVersion").value(ThermodynamicLoadEngine.ALGORITHM_VERSION); json.name("omegaModelParameter").value(t.omegaCrit); json.name("finalRawLoad").value(t.finalRawLoad); json.name("finalBoundedLoad").value(t.finalBoundedLoad); json.name("omegaBreach").value(t.omegaBreach); json.name("meanFaa").value(t.meanFaa); json.name("clinicalNormValidated").value(false); json.endObject() }
                json.endObject()
            }
        } ?: error("JSON-Zieldatei konnte nicht geöffnet werden.")
    }

    fun writeMgr(context: Context, uri: Uri, recording: MeegRecording) {
        val temp = File.createTempFile("meegread_export_", ".mgr", context.cacheDir)
        try { RecordingCodec.write(temp, recording); context.contentResolver.openOutputStream(uri)?.use { output -> temp.inputStream().use { it.copyTo(output) } } ?: error("MGR-Zieldatei konnte nicht geöffnet werden.") } finally { temp.delete() }
    }

    fun writePdf(context: Context, uri: Uri, recording: MeegRecording) {
        val document = PdfDocument()
        try {
            val metrics = SignalAnalysisEngine.analyze(recording); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }; val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; isFakeBoldText = true }; val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; isFakeBoldText = true }
            var pageNumber = 1; var page = document.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create()); var canvas = page.canvas; var y = 48f
            fun newPage() { document.finishPage(page); pageNumber++; page = document.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create()); canvas = page.canvas; y = 48f }
            fun line(text: String, p: Paint = paint, gap: Float = 18f) { if (y > 800f) newPage(); canvas.drawText(text.take(95),42f,y,p); y += gap }
            line("MeegRead Medical Analysebericht", titlePaint, 32f); line("Datei: ${recording.name}"); line("Modalität: ${recording.modality}"); line("Abtastrate: ${format(recording.sampleRateHz)} Hz"); line("Kanäle: ${recording.channelCount} · Samples: ${recording.sampleCount}"); line("Dauer: ${format(recording.durationSeconds)} s · Ereignisse: ${recording.events.size}"); y += 10f; line("Kanalmetriken", headerPaint,24f)
            metrics.forEach { m -> line("${m.channel}: RMS ${format(m.rms)} | σ ${format(m.standardDeviation)} | P-P ${format(m.peakToPeak)} | fdom ${m.dominantFrequencyHz?.let(::format) ?: "–"} Hz"); val powers = SignalAnalysisEngine.bandPowers(recording.channels[m.channel].orEmpty(), recording.sampleRateHz); if (powers.isNotEmpty()) line("  Bandpower rel.: " + powers.joinToString(" · ") { "${it.band.name.lowercase()}: ${format(it.relativePower*100)}%" }, paint,17f) }
            ThermodynamicLoadEngine.analyze(recording)?.let { t -> y += 10f; line("Experimenteller MMSI-Forschungsindex", headerPaint,24f); line("Algorithmus: ${ThermodynamicLoadEngine.ALGORITHM_VERSION}"); line("Wraw ${format(t.finalRawLoad)} · Wbounded ${format(t.finalBoundedLoad)} · Ω-Modellparameter ${format(t.omegaCrit)}"); line("Grenzstatus: ${if (t.omegaBreach) "Modellgrenze erreicht/überschritten" else "unter Modellgrenze"}"); line("Hinweis: Forschungsmetrik; keine etablierte klinische Normgrenze.") }
            document.finishPage(page); context.contentResolver.openOutputStream(uri)?.use(document::writeTo) ?: error("PDF-Zieldatei konnte nicht geöffnet werden.")
        } finally { document.close() }
    }

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"","\"\"")}\"" else value
    private fun format(value: Double): String = String.format(Locale.US, "%.6g", value)
}
