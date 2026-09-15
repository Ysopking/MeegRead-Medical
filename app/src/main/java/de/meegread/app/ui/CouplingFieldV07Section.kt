package de.meegread.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import de.meegread.app.analysis.CouplingFieldV07Audit
import de.meegread.app.analysis.CouplingFieldV07Engine
import de.meegread.app.export.V07AuditExportManager
import de.meegread.app.model.MeegRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun CouplingFieldV07Section(recording: MeegRecording) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var computing by remember(recording) { mutableStateOf(true) }
    var result by remember(recording) { mutableStateOf<CouplingFieldV07Engine.Result?>(null) }
    val auditLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) { V07AuditExportManager.writeJson(context, uri, recording) }
    }

    LaunchedEffect(recording) {
        computing = true
        result = withContext(Dispatchers.Default) { CouplingFieldV07Engine.analyze(recording) }
        computing = false
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Kopplungsfeld v0.7 · Forschung", fontWeight = FontWeight.Bold)
            when {
                computing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                result == null -> Text(
                    "Nicht berechenbar: mindestens zwei EEG/MEG-Kanäle mit ausreichender Datenlänge werden benötigt.",
                    style = MaterialTheme.typography.bodySmall
                )
                else -> {
                    CouplingFieldResult(result!!)
                    OutlinedButton(onClick = { auditLauncher.launch(v07AuditFileName(recording)) }) {
                        Text("v0.7 Audit JSON exportieren")
                    }
                    Text(
                        "Algorithmus-Fingerprint: ${CouplingFieldV07Audit.algorithmFingerprintSha256.take(16)}…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CouplingFieldResult(result: CouplingFieldV07Engine.Result) {
    val base = result.baseScale
    val space = when (result.operationalSpace) {
        CouplingFieldV07Engine.OperationalSpace.SOURCE_SPACE -> "Quellraum"
        CouplingFieldV07Engine.OperationalSpace.SENSOR_SPACE_PROXY -> "Sensorraum-Proxy"
    }

    Text(
        "$space · ${result.sourceChannels.size} Eingangsknoten · ${result.analysisWindowSamples} Samples · ${f1(result.sampleRateHz)} Hz",
        style = MaterialTheme.typography.bodySmall
    )
    Text("Endliche Eingabedaten: ${f1(100.0 * result.finiteInputFraction)}%", style = MaterialTheme.typography.bodySmall)

    if (base != null) {
        Text(
            "d0: κ=${f3(base.kappa)} · c=${f3(base.directionCoherence)} · Γ=${f3(base.gamma)} · PLV=${f3(base.meanPhaseLocking)}",
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "P=${f3(base.flowMagnitudeP)} · Q=P(1−Γ)=${f3(base.couplingLossQ)} · χdyn=${f3(base.chiDyn)} · Reserve=${f3(base.stabilityReserve)}",
            style = MaterialTheme.typography.bodySmall
        )
    }

    Text("Rekursive Skalen", fontWeight = FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        result.scales.forEach { scale ->
            Card {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("d${scale.level} · N=${scale.nodeCount}", fontWeight = FontWeight.Bold)
                    Text("Γ ${f3(scale.gamma)}", style = MaterialTheme.typography.bodySmall)
                    Text("Q ${f3(scale.couplingLossQ)}", style = MaterialTheme.typography.bodySmall)
                    Text("χdyn ${f3(scale.chiDyn)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    val strongest = base?.pairs?.take(4).orEmpty()
    if (strongest.isNotEmpty()) {
        Text(
            "Stärkste Bündelungen: " + strongest.joinToString(" · ") {
                "${it.nodeA}↔${it.nodeB} K=${f2(it.phaseLocking)} κ=${f2(it.informationOverlap)}"
            },
            style = MaterialTheme.typography.bodySmall
        )
    }

    result.warnings.forEach { warning ->
        Text("• $warning", style = MaterialTheme.typography.bodySmall)
    }
    Text("Engine ${result.version}", style = MaterialTheme.typography.bodySmall)
}

private fun v07AuditFileName(recording: MeegRecording): String =
    recording.name.substringBeforeLast('.').ifBlank { "recording" } + "_v07_audit.json"

private fun f1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun f2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun f3(value: Double): String = String.format(Locale.US, "%.3f", value)
