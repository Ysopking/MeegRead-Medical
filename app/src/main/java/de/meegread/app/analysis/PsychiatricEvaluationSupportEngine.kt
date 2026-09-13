package de.meegread.app.analysis

import kotlin.math.ln

/**
 * Converts BRMH cohort scores into an auditable, uncertainty-aware research report.
 *
 * This layer deliberately does not turn EEG scores into psychiatric diagnoses. It exposes
 * ranked cohort similarity, uncertainty, dataset coverage and ICD-10-GM reference candidates.
 */
object PsychiatricEvaluationSupportEngine {
    const val VERSION = "psy-eval-support-v1"

    enum class EvidenceGrade {
        INSUFFICIENT_FOR_DIAGNOSIS,
        RESEARCH_RANKING_ONLY
    }

    data class DiagnosticCandidate(
        val cohortLabel: String,
        val cohortDisplayLabel: String,
        val cohortScore: Double,
        val datasetSpecificLabel: String,
        val datasetCount: Int,
        val icd10gm: String?,
        val icdDisplayName: String?,
        val mappingSpecificity: PsychiatricReferenceTaxonomy.MappingSpecificity?
    )

    data class Evaluation(
        val topCohortScore: Double,
        val top1Margin: Double,
        val normalizedEntropy: Double,
        val evidenceGrade: EvidenceGrade,
        val candidates: List<DiagnosticCandidate>,
        val unsupportedChapterRanges: List<String>,
        val note: String
    )

    fun evaluate(result: BrmhCohortSupportEngine.BrmhCohortSupportResult): Evaluation {
        require(result.scores.isNotEmpty())
        val ordered = result.scores.sortedByDescending { it.score }
        val top1 = ordered[0].score
        val top2 = ordered.getOrNull(1)?.score ?: 0.0
        val entropy = normalizedEntropy(ordered.map { it.score })

        val candidates = ordered.take(3).flatMap { cohort ->
            cohort.meta.specificLabels.map { specific ->
                val icd = PsychiatricReferenceTaxonomy.supportForDatasetLabel(specific.label)
                DiagnosticCandidate(
                    cohortLabel = cohort.meta.sourceLabel,
                    cohortDisplayLabel = cohort.meta.displayLabel,
                    cohortScore = cohort.score,
                    datasetSpecificLabel = specific.label,
                    datasetCount = specific.count,
                    icd10gm = icd?.icd10gm,
                    icdDisplayName = icd?.displayName,
                    mappingSpecificity = icd?.specificity
                )
            }
        }

        val unsupported = PsychiatricReferenceTaxonomy.chapterCoverage
            .filterNot { it.representedInBrmh }
            .map { it.range }

        return Evaluation(
            topCohortScore = top1,
            top1Margin = (top1 - top2).coerceAtLeast(0.0),
            normalizedEntropy = entropy,
            evidenceGrade = EvidenceGrade.RESEARCH_RANKING_ONLY,
            candidates = candidates,
            unsupportedChapterRanges = unsupported,
            note = "BRMH liefert nur eine Forschungsrangfolge. Einzelne ICD-10-GM-Diagnosen werden nicht aus EEG allein vergeben; spezifische Diagnosen innerhalb einer BRMH-Hauptgruppe bleiben klinisch zu bestätigen."
        )
    }

    fun normalizedEntropy(probabilities: List<Double>): Double {
        if (probabilities.size <= 1) return 0.0
        val clean = probabilities.map { if (it.isFinite() && it > 0.0) it else 0.0 }
        val sum = clean.sum()
        if (sum <= 0.0) return 1.0
        val normalized = clean.map { it / sum }
        val h = -normalized.sumOf { p -> if (p > 0.0) p * ln(p) else 0.0 }
        return (h / ln(normalized.size.toDouble())).coerceIn(0.0, 1.0)
    }
}
