package de.meegread.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsychiatricEvaluationSupportEngineTest {
    @Test
    fun evaluationKeepsSpecificLabelsAsCandidatesNotDiagnoses() {
        val scores = BrmhCohortSupportEngine.cohortMetadata.mapIndexed { index, meta ->
            BrmhCohortSupportEngine.BrmhCohortScore(meta, if (index == 0) 0.40 else 0.60 / 6.0)
        }.sortedByDescending { it.score }

        val result = BrmhCohortSupportEngine.BrmhCohortSupportResult(
            scores = scores,
            sourceChannels = mapOf("F7" to "F7", "F8" to "F8", "T3" to "T3", "T4" to "T4"),
            featureCount = 20
        )

        val evaluation = PsychiatricEvaluationSupportEngine.evaluate(result)
        assertEquals(PsychiatricEvaluationSupportEngine.EvidenceGrade.RESEARCH_RANKING_ONLY, evaluation.evidenceGrade)
        assertTrue(evaluation.candidates.isNotEmpty())
        assertTrue(evaluation.normalizedEntropy in 0.0..1.0)
        assertEquals(0.30, evaluation.top1Margin, 1e-12)
        assertTrue(evaluation.unsupportedChapterRanges.contains("F00-F09"))
    }

    @Test
    fun taxonomyMapsSupportedLabelsWithoutInventingSubtypes() {
        val panic = PsychiatricReferenceTaxonomy.supportForDatasetLabel("Panic disorder")
        val schizophrenia = PsychiatricReferenceTaxonomy.supportForDatasetLabel("Schizophrenia")
        val behavioral = PsychiatricReferenceTaxonomy.supportForDatasetLabel("Behavioral addiction disorder")

        assertEquals("F41.0", panic?.icd10gm)
        assertEquals("F20.-", schizophrenia?.icd10gm)
        assertEquals(null, behavioral?.icd10gm)
    }
}
