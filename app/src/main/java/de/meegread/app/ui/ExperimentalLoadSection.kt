package de.meegread.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import de.meegread.app.analysis.ThermodynamicLoadEngine
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.ThermodynamicLoadAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ThermodynamicLoadSection(recording: MeegRecording) {
    var result by remember(recording) { mutableStateOf<ThermodynamicLoadAnalysis?>(null) }
    var computing by remember(recording) { mutableStateOf(true) }
    LaunchedEffect(recording) { computing = true; result = withContext(Dispatchers.Default) { ThermodynamicLoadEngine.analyze(recording) }; computing = false }
    val missing = ThermodynamicLoadEngine.missingChannels(recording)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Thermodynamische Last · MMSI-Forschung", fontWeight = FontWeight.Bold)
            when {
                computing -> Text("Lasttrajektorie wird berechnet …", style = MaterialTheme.typography.bodySmall)
                result != null -> ThermodynamicResult(result!!)
                missing.isNotEmpty() -> { Text("Nicht berechenbar: ${missing.joinToString(", ")} fehlen."); Text("Benötigt werden AF7, AF8, TP9 und TP10.", style=MaterialTheme.typography.bodySmall) }
                else -> Text("Nicht berechenbar: Signalqualität oder Datenlänge unzureichend.")
            }
            Text("Ωkrit = 5800 ist ein MMSI-Modellparameter. Der Wert ist keine etablierte klinische Normgrenze und ersetzt keine ärztliche Diagnose.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThermodynamicResult(result: ThermodynamicLoadAnalysis) {
    val latest = result.points.last(); val remaining = result.omegaCrit - latest.wRaw
    Text(if (result.omegaBreach) "MMSI-Modellgrenze erreicht/überschritten" else "Unter MMSI-Modellgrenze", fontWeight=FontWeight.Bold)
    Text("Wraw ${f(latest.wRaw)} / ${f(result.omegaCrit)} · Ω-Auslastung ${f(latest.omegaRatio*100)} %")
    Text("Wbounded ${f(latest.wBounded)} · ΔΩ ${f(remaining)}")
    result.firstBreachTimeSeconds?.let { Text("Erste Modell-Grenzüberschreitung bei ${f(it)} s") }
    if (result.points.size > 1) { Text("W(t)-Verlauf", style=MaterialTheme.typography.bodySmall, fontWeight=FontWeight.Bold); SignalChart(result.points.map { it.wRaw }, Modifier.fillMaxWidth()) }
    Text("FAA μ ${f(result.meanFaa)} · ∇Ekog ${f(latest.gradE)} · ΔPproxy ${f(latest.pressureProxy)} · Eflow ${f(latest.eFlow)}", style=MaterialTheme.typography.bodySmall)
    Text("Algorithmus: ${ThermodynamicLoadEngine.ALGORITHM_VERSION}", style=MaterialTheme.typography.bodySmall)
}

private fun f(value: Double): String = String.format(Locale.US,"%.3f",value)
