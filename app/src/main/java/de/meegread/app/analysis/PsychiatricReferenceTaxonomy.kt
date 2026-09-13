package de.meegread.app.analysis

/**
 * ICD-10-GM 2026 reference layer for the research-only BRMH cohort-support output.
 *
 * This is NOT a diagnostic engine. It maps labels already present in the uploaded BRMH
 * dataset to the narrowest defensible ICD-10-GM code or code family. Broad dataset labels
 * intentionally remain broad instead of being forced into a single diagnosis.
 */
object PsychiatricReferenceTaxonomy {
    const val VERSION = "icd10gm-2026-brmh-map-v1"

    enum class MappingSpecificity {
        EXACT_CATEGORY,
        CODE_FAMILY,
        NO_UNIQUE_CODE,
        HEALTHY_CONTROL
    }

    data class IcdSupport(
        val datasetLabel: String,
        val icd10gm: String?,
        val displayName: String,
        val specificity: MappingSpecificity,
        val note: String
    )

    data class ChapterCoverage(
        val range: String,
        val title: String,
        val representedInBrmh: Boolean,
        val representedLabels: List<String> = emptyList()
    )

    private val mappings = mapOf(
        "Alcohol use disorder" to IcdSupport(
            "Alcohol use disorder", "F10.-", "Psychische und Verhaltensstörungen durch Alkohol",
            MappingSpecificity.CODE_FAMILY,
            "Der BRMH-Label unterscheidet nicht Intoxikation, schädlichen Gebrauch, Abhängigkeit, Entzug oder andere F10-Unterformen."
        ),
        "Behavioral addiction disorder" to IcdSupport(
            "Behavioral addiction disorder", null, "Verhaltenssucht / Verhaltensbezogene Abhängigkeit",
            MappingSpecificity.NO_UNIQUE_CODE,
            "Der Datensatz benennt die konkrete Verhaltenssucht nicht. Daher keine erzwungene Zuordnung z. B. zu F63.0."
        ),
        "Panic disorder" to IcdSupport(
            "Panic disorder", "F41.0", "Panikstörung [episodisch paroxysmale Angst]",
            MappingSpecificity.EXACT_CATEGORY,
            "Eindeutige Zuordnung des Datensatzlabels zur ICD-10-GM-Kategorie."
        ),
        "Social anxiety disorder" to IcdSupport(
            "Social anxiety disorder", "F40.1", "Soziale Phobien",
            MappingSpecificity.EXACT_CATEGORY,
            "Der BRMH-Label 'social anxiety disorder' wird der ICD-10-GM-Kategorie F40.1 zugeordnet."
        ),
        "Healthy control" to IcdSupport(
            "Healthy control", null, "Gesunde Kontrollgruppe",
            MappingSpecificity.HEALTHY_CONTROL,
            "Keine psychiatrische ICD-10-GM-Diagnose."
        ),
        "Depressive disorder" to IcdSupport(
            "Depressive disorder", "F32.- / F33.-", "Depressive Störung",
            MappingSpecificity.CODE_FAMILY,
            "Der BRMH-Label reicht nicht aus, um einzelne Episode und rezidivierende depressive Störung sicher zu trennen."
        ),
        "Bipolar disorder" to IcdSupport(
            "Bipolar disorder", "F31.-", "Bipolare affektive Störung",
            MappingSpecificity.CODE_FAMILY,
            "Episode/Remissionsstatus sind aus dem Datensatzlabel nicht eindeutig bestimmbar."
        ),
        "Obsessive compulsitve disorder" to IcdSupport(
            "Obsessive compulsitve disorder", "F42.-", "Zwangsstörung",
            MappingSpecificity.CODE_FAMILY,
            "Der Datensatz trennt die F42-Unterformen nicht."
        ),
        "Schizophrenia" to IcdSupport(
            "Schizophrenia", "F20.-", "Schizophrenie",
            MappingSpecificity.CODE_FAMILY,
            "Die Schizophrenie-Unterform ist aus dem BRMH-Label nicht eindeutig bestimmbar."
        ),
        "Posttraumatic stress disorder" to IcdSupport(
            "Posttraumatic stress disorder", "F43.1", "Posttraumatische Belastungsstörung",
            MappingSpecificity.EXACT_CATEGORY,
            "Eindeutige Zuordnung des Datensatzlabels zur ICD-10-GM-Kategorie."
        ),
        "Acute stress disorder" to IcdSupport(
            "Acute stress disorder", "F43.0", "Akute Belastungsreaktion",
            MappingSpecificity.EXACT_CATEGORY,
            "Terminologische Abbildung des BRMH-Labels auf die ICD-10-GM-Kategorie F43.0."
        ),
        "Adjustment disorder" to IcdSupport(
            "Adjustment disorder", "F43.2", "Anpassungsstörungen",
            MappingSpecificity.EXACT_CATEGORY,
            "Eindeutige Zuordnung des Datensatzlabels zur ICD-10-GM-Kategorie."
        )
    )

    val chapterCoverage = listOf(
        ChapterCoverage("F00-F09", "Organische, einschließlich symptomatischer psychischer Störungen", false),
        ChapterCoverage("F10-F19", "Psychische und Verhaltensstörungen durch psychotrope Substanzen", true, listOf("Alcohol use disorder")),
        ChapterCoverage("F20-F29", "Schizophrenie, schizotype und wahnhafte Störungen", true, listOf("Schizophrenia")),
        ChapterCoverage("F30-F39", "Affektive Störungen", true, listOf("Depressive disorder", "Bipolar disorder")),
        ChapterCoverage("F40-F48", "Neurotische, Belastungs- und somatoforme Störungen", true, listOf("Panic disorder", "Social anxiety disorder", "Obsessive compulsitve disorder", "Posttraumatic stress disorder", "Acute stress disorder", "Adjustment disorder")),
        ChapterCoverage("F50-F59", "Verhaltensauffälligkeiten mit körperlichen Störungen und Faktoren", false),
        ChapterCoverage("F60-F69", "Persönlichkeits- und Verhaltensstörungen", false, listOf("Behavioral addiction disorder: zu unspezifisch für eindeutige ICD-Zuordnung")),
        ChapterCoverage("F70-F79", "Intelligenzstörung", false),
        ChapterCoverage("F80-F89", "Entwicklungsstörungen", false),
        ChapterCoverage("F90-F98", "Verhaltens- und emotionale Störungen mit Beginn in der Kindheit und Jugend", false),
        ChapterCoverage("F99", "Nicht näher bezeichnete psychische Störungen", false)
    )

    fun supportForDatasetLabel(label: String): IcdSupport? = mappings[label]
}
