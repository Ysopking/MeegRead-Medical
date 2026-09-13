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
import de.meegread.app.analysis.BrmhCohortSupportEngine
import de.meegread.app.analysis.PsychiatricEvaluationSupportEngine
import de.meegread.app.analysis.PsychiatricReferenceTaxonomy
import de.meegread.app.model.MeegRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun BrmhCohortSupportSection(recording: MeegRecording) {
    var result by remember(recording) { mutableStateOf<BrmhCohortSupportEngine.BrmhCohortSupportResult?>(null) }
    var computing by remember(recording) { mutableStateOf(true) }

    LaunchedEffect(recording) {
        computing = true
        result = withContext(Dispatchers.Default) { BrmhCohortSupportEngine.analyze(recording) }
        computing = false
    }

    val missing = BrmhCohortSupportEngine.missingChannels(recording)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("BRMH-Kohorten-Zuordnungshilfe · Forschung", fontWeight = FontWeight.Bold)
            when {
                computing -> Text("BRMH-Kohortenvergleich wird berechnet …", style = MaterialTheme.typography.bodySmall)
                result != null -> BrmhResult(result!!)
                missing.isNotEmpty() -> {
                    Text("Nicht berechenbar: ${missing.joinToString(", ")} fehlen.")
                    Text(
                        "Der hochgeladene BRMH-Datensatz nutzt F7, F8, T3 und T4. AF7/AF8/TP9/TP10 werden absichtlich nicht als identische Elektroden ersetzt.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> Text("Nicht berechenbar: Signalqualität oder Datenlänge unzureichend.")
            }

            Text(
                "Nur Zuordnungshilfe zu BRMH-Kohorten, keine Diagnose. ICD-10-GM-Zuordnungen beziehen sich auf die vorhandenen Datensatzlabels; die Referenzdiagnose muss klinisch/leitliniengerecht gestellt werden.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun BrmhResult(result: BrmhCohortSupportEngine.BrmhCohortSupportResult) {
    val evaluation = PsychiatricEvaluationSupportEngine.evaluate(result)

    Text("Top-3 Kohortenähnlichkeit", fontWeight = FontWeight.Bold)
    result.top3.forEachIndexed { index, item ->
        Text(
            "${index + 1}. ${item.meta.displayLabel}: ${pct(item.score)} · BRMH n=${item.meta.count} · OVR-AUC ${f3(item.meta.cvOvrAuc)}"
        )
        item.meta.specificLabels.forEach { specific ->
            val icd = PsychiatricReferenceTaxonomy.supportForDatasetLabel(specific.label)
            val code = icd?.icd10gm?.let { " · ICD-10-GM $it" }.orEmpty()
            val suffix = when (icd?.specificity) {
                PsychiatricReferenceTaxonomy.MappingSpecificity.EXACT_CATEGORY -> " · direkte Label-Zuordnung"
                PsychiatricReferenceTaxonomy.MappingSpecificity.CODE_FAMILY -> " · Codefamilie, Unterform offen"
                PsychiatricReferenceTaxonomy.MappingSpecificity.NO_UNIQUE_CODE -> " · kein eindeutiger ICD-Code aus BRMH-Label"
                PsychiatricReferenceTaxonomy.MappingSpecificity.HEALTHY_CONTROL -> " · keine psychiatrische ICD-Diagnose"
                null -> ""
            }
            Text(
                "• ${translateSpecific(specific.label)} (${specific.count})$code$suffix",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Text("Auswertungsunsicherheit", fontWeight = FontWeight.Bold)
    Text(
        "Top-1 ${pct(evaluation.topCohortScore)} · Abstand Top-1/Top-2 ${pct(evaluation.top1Margin)} · normierte Entropie ${f3(evaluation.normalizedEntropy)}",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Bewertung: Forschungsrangfolge בלבד. ${evaluation.note}",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Nicht durch BRMH abgedeckte ICD-Kapitel: ${evaluation.unsupportedChapterRanges.joinToString(", ")}",
        style = MaterialTheme.typography.bodySmall
    )

    Text(
        "5-fach-CV im hochgeladenen BRMH-Datensatz: Balanced Accuracy ${pct(BrmhCohortSupportEngine.CV_BALANCED_ACCURACY)}, " +
            "Top-3 Accuracy ${pct(BrmhCohortSupportEngine.CV_TOP3_ACCURACY)}. Damit ist das Modell nicht für autonome psychiatrische Diagnosen geeignet.",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Kanäle: ${result.sourceChannels.entries.joinToString { "${it.key}→${it.value}" }} · Features ${result.featureCount}",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Modelle ${result.modelVersion} · ${PsychiatricEvaluationSupportEngine.VERSION} · ${PsychiatricReferenceTaxonomy.VERSION} · BRMH N=${BrmhCohortSupportEngine.DATASET_SIZE} · Datensatz-SHA256 ${BrmhCohortSupportEngine.DATASET_SHA256.take(12)}…",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun translateSpecific(label: String): String = when (label) {
    "Alcohol use disorder" -> "Alkoholkonsumstörung"
    "Behavioral addiction disorder" -> "Verhaltenssucht"
    "Panic disorder" -> "Panikstörung"
    "Social anxiety disorder" -> "Soziale Angststörung"
    "Healthy control" -> "Gesunde Kontrolle"
    "Depressive disorder" -> "Depressive Störung"
    "Bipolar disorder" -> "Bipolare Störung"
    "Obsessive compulsitve disorder" -> "Zwangsstörung"
    "Schizophrenia" -> "Schizophrenie"
    "Posttraumatic stress disorder" -> "Posttraumatische Belastungsstörung"
    "Acute stress disorder" -> "Akute Belastungsreaktion"
    "Adjustment disorder" -> "Anpassungsstörung"
    else -> label
}

private fun pct(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)
private fun f3(value: Double): String = String.format(Locale.US, "%.3f", value)
