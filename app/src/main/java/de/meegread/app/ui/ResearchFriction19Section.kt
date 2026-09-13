package de.meegread.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.meegread.app.analysis.ResearchFriction19Engine
import de.meegread.app.model.MeegRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ResearchFriction19Section(recording: MeegRecording) {
    val missing = remember(recording) { ResearchFriction19Engine.missingChannels(recording) }
    var computing by remember(recording) { mutableStateOf(missing.isEmpty()) }
    var result by remember(recording) { mutableStateOf<ResearchFriction19Engine.Result?>(null) }

    LaunchedEffect(recording) {
        if (missing.isEmpty()) {
            computing = true
            result = withContext(Dispatchers.Default) { ResearchFriction19Engine.analyze(recording) }
            computing = false
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("19-Kanal W/Y/Z · Forschung", fontWeight = FontWeight.Bold)
            when {
                missing.isNotEmpty() -> Text(
                    "Nicht berechenbar: ${missing.joinToString(", ")} fehlen. Verwendet werden die 19 exakten 10–20-Kanäle.",
                    style = MaterialTheme.typography.bodySmall
                )
                computing -> Text("19-Kanal-Metrik wird berechnet …", style = MaterialTheme.typography.bodySmall)
                result == null -> Text("Nicht berechenbar: Signalqualität oder Datenlänge unzureichend.")
                else -> ResearchFrictionResult(result!!)
            }
            Text("Nur Forschungsmetrik; Grenzwerte sind Modellparameter.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResearchFrictionResult(result: ResearchFriction19Engine.Result) {
    Text("W=${f3(result.normalizedRatio)} · Y=${f3(result.loadY)} · Z=${f3(result.capacityZ)} · D=${f3(result.dispersionD)}")
    Text(
        "Alpha-Kohärenz=${f1(result.alphaCoherencePercent)}% · ${regimeLabel(result.regime)}",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Rohquotient Y/Z=${f3(result.rawRatio)} · Referenzmedian roh ${f3(ResearchFriction19Engine.HC_RAW_MEDIAN)} → normiert 1,000",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Dispersion: 114 Zellen (6 Bänder × 19 Kanäle); Kohärenz: Mittel über 171 Kanalpaare im Alpha-Band.",
        style = MaterialTheme.typography.bodySmall
    )
    val hottest = result.localRatios.entries.sortedByDescending { it.value }.take(5)
    Text("Höchste lokale Quotienten: ${hottest.joinToString { "${it.key} ${f2(it.value)}" }}", style = MaterialTheme.typography.bodySmall)
    Text("Engine ${ResearchFriction19Engine.VERSION}", style = MaterialTheme.typography.bodySmall)
}

private fun regimeLabel(value: ResearchFriction19Engine.Regime): String = when (value) {
    ResearchFriction19Engine.Regime.BASELINE -> "Basisbereich"
    ResearchFriction19Engine.Regime.ELEVATED -> "erhöhter Quotient"
    ResearchFriction19Engine.Regime.HIGH -> "hoher Quotient"
    ResearchFriction19Engine.Regime.ABOVE_RESEARCH_LIMIT -> "oberhalb Forschungsgrenze"
}

private fun f1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun f2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun f3(value: Double): String = String.format(Locale.US, "%.3f", value)
