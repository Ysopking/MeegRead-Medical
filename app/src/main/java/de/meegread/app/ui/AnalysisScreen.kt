package de.meegread.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.meegread.app.analysis.SignalAnalysisEngine
import de.meegread.app.data.RecordingImporter
import de.meegread.app.export.ExportManager
import de.meegread.app.model.BandPower
import de.meegread.app.model.ChannelMetrics
import de.meegread.app.model.CoherenceResult
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.SpectralPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class SelectedAnalysis(val metrics: ChannelMetrics, val psd: List<SpectralPoint>, val bands: List<BandPower>, val coherence: CoherenceResult?)

@Composable
fun AnalysisScreen(recording: MeegRecording?, onRecordingChange: (MeegRecording) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedChannel by remember(recording?.name, recording?.channelCount) { mutableStateOf(recording?.channels?.keys?.firstOrNull()) }
    var secondChannel by remember(recording?.name, recording?.channelCount) { mutableStateOf(recording?.channels?.keys?.drop(1)?.firstOrNull()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("EDF/BDF/FIF/CSV/TSV/JSON/MGR auswählen") }
    var analysis by remember { mutableStateOf<SelectedAnalysis?>(null) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            loading = true; message = "Messdatei wird importiert …"
            runCatching { withContext(Dispatchers.IO) { RecordingImporter.import(context, uri) } }
                .onSuccess { parsed -> onRecordingChange(parsed); selectedChannel = parsed.channels.keys.firstOrNull(); secondChannel = parsed.channels.keys.drop(1).firstOrNull(); message = "${parsed.metadata["format"] ?: parsed.modality} · ${parsed.channelCount} Kanäle · ${fmt(parsed.sampleRateHz)} Hz" }
                .onFailure { message = it.message ?: "Import fehlgeschlagen" }
            loading = false
        }
    }
    val rawCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { recording?.let { r -> scope.launch(Dispatchers.IO) { ExportManager.writeRawCsv(context,it,r) } } } }
    val summaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { recording?.let { r -> scope.launch(Dispatchers.IO) { ExportManager.writeSummaryCsv(context,it,r) } } } }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { recording?.let { r -> scope.launch(Dispatchers.IO) { ExportManager.writeJson(context,it,r) } } } }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let { recording?.let { r -> scope.launch(Dispatchers.IO) { ExportManager.writePdf(context,it,r) } } } }
    val mgrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { recording?.let { r -> scope.launch(Dispatchers.IO) { ExportManager.writeMgr(context,it,r) } } } }

    LaunchedEffect(recording, selectedChannel, secondChannel) {
        val current = recording; val channel = selectedChannel; val values = channel?.let { current?.channels?.get(it) }
        if (current == null || channel == null || values.isNullOrEmpty()) { analysis = null; return@LaunchedEffect }
        analysis = withContext(Dispatchers.Default) {
            val coherence = secondChannel?.takeIf { it != channel }?.let { other -> current.channels[other]?.let { SignalAnalysisEngine.coherence(channel, values, other, it, current.sampleRateHz) } }
            SelectedAnalysis(SignalAnalysisEngine.analyzeChannel(channel, values, current.sampleRateHz), SignalAnalysisEngine.welchPsd(values, current.sampleRateHz), SignalAnalysisEngine.bandPowers(values, current.sampleRateHz), coherence)
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analyse", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = !loading) { Text("Datei öffnen") }; recording?.let { Text("${it.modality} · ${it.channelCount} Ch", Modifier.padding(top=12.dp)) } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message, style = MaterialTheme.typography.bodySmall)
        recording?.let { current ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(12.dp)) { Text(current.name, fontWeight=FontWeight.Bold); Text("${current.modality} · ${current.channelCount} Kanäle · ${current.sampleCount} Samples"); Text("${fmt(current.sampleRateHz)} Hz · ${fmt(current.durationSeconds)} s · ${current.events.size} Marker") } }
            Text("Kanäle", fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) { current.channels.keys.forEach { name -> if (name == selectedChannel) Button(onClick={selectedChannel=name}) { Text(name) } else OutlinedButton(onClick={selectedChannel=name}) { Text(name) } } }
            val selectedValues = selectedChannel?.let(current.channels::get)
            if (selectedValues != null) Card { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Signal · $selectedChannel", fontWeight=FontWeight.Bold); SignalChart(selectedValues)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick={ onRecordingChange(current.copy(channels=current.channels + (selectedChannel!! to SignalAnalysisEngine.notchFilter(selectedValues,current.sampleRateHz,50.0)))) }) { Text("50-Hz-Notch") }
                    OutlinedButton(onClick={ onRecordingChange(current.copy(channels=current.channels + (selectedChannel!! to SignalAnalysisEngine.bandpassFilter(selectedValues,current.sampleRateHz,1.0,40.0)))) }) { Text("1–40 Hz") }
                    OutlinedButton(onClick={ onRecordingChange(SignalAnalysisEngine.commonAverageReference(current)) }) { Text("EEG CAR") }
                }
            } }
            analysis?.let { a ->
                Card { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(7.dp)) { Text("Spektrum / Welch-PSD", fontWeight=FontWeight.Bold); PsdChart(a.psd,minOf(80.0,current.sampleRateHz/2)); Text("RMS ${fmt(a.metrics.rms)} · σ ${fmt(a.metrics.standardDeviation)} · P-P ${fmt(a.metrics.peakToPeak)} · fdom ${a.metrics.dominantFrequencyHz?.let(::fmt) ?: "–"} Hz"); Text(a.bands.joinToString(" · ") { "${it.band.name.lowercase()}: ${fmt(it.relativePower*100)}%" }, style=MaterialTheme.typography.bodySmall) } }
                if (current.channelCount > 1) Card { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(7.dp)) { Text("Kohärenz", fontWeight=FontWeight.Bold); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(7.dp)) { current.channels.keys.filter { it != selectedChannel }.forEach { name -> if (name == secondChannel) Button(onClick={secondChannel=name}) { Text(name) } else OutlinedButton(onClick={secondChannel=name}) { Text(name) } } }; a.coherence?.let { c -> CoherenceChart(c.frequenciesHz,c.coherence,minOf(80.0,current.sampleRateHz/2)); val alpha = c.frequenciesHz.zip(c.coherence).filter { it.first in 8.0..13.0 }.map { it.second }; Text("Mittlere Alpha-Kohärenz: ${if(alpha.isEmpty()) "–" else fmt(alpha.average())}") } } }
            }
            Card { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("Export",fontWeight=FontWeight.Bold); Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) { OutlinedButton(onClick={rawCsvLauncher.launch(baseName(current,"raw.csv"))}) { Text("Raw CSV") }; OutlinedButton(onClick={summaryLauncher.launch(baseName(current,"summary.csv"))}) { Text("Analyse CSV") }; OutlinedButton(onClick={jsonLauncher.launch(baseName(current,"json"))}) { Text("JSON") }; OutlinedButton(onClick={pdfLauncher.launch(baseName(current,"pdf"))}) { Text("PDF") }; OutlinedButton(onClick={mgrLauncher.launch(baseName(current,"mgr"))}) { Text("MGR") } } } }
        }
        Spacer(Modifier.height(80.dp))
    }
}

private fun baseName(recording: MeegRecording, suffix: String): String = recording.name.substringBeforeLast('.') + "_medical.$suffix"
private fun fmt(value: Double): String = String.format(Locale.US,"%.3f",value)
