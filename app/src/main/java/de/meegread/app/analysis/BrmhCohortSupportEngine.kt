package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * BRMH-derived research-only cohort support model.
 *
 * Training source: EEG.machinelearing_data_BRMH.csv (N=945)
 * SHA-256: 88c71df61fbc6d07c8e8f83b9b19c00c9200c664a9ede4a54207cb2d72a4ec09
 *
 * Model: balanced multinomial logistic regression on log1p absolute band powers from
 * F7/F8/T3/T4 (delta, theta, alpha, beta, high-beta), standardized on the BRMH cohort.
 *
 * 5-fold CV on BRMH:
 *   accuracy ~17.7%, balanced accuracy ~20.4%, macro-F1 ~17.4%, top-3 accuracy ~53.8%.
 *
 * These scores are NOT diagnoses and are intentionally exposed as cohort-support scores.
 */
object BrmhCohortSupportEngine {
    const val MODEL_VERSION = "brmh-4ch-logreg-v1.0"
    const val DATASET_SHA256 = "88c71df61fbc6d07c8e8f83b9b19c00c9200c664a9ede4a54207cb2d72a4ec09"
    const val DATASET_SIZE = 945
    const val CV_ACCURACY = 0.177
    const val CV_BALANCED_ACCURACY = 0.204
    const val CV_MACRO_F1 = 0.174
    const val CV_TOP3_ACCURACY = 0.538

    private const val EPS = 1e-12
    private val requiredChannels = listOf("F7", "F8", "T3", "T4")

    data class BrmhSpecificLabel(val label: String, val count: Int)

    data class BrmhCohortMeta(
        val sourceLabel: String,
        val displayLabel: String,
        val count: Int,
        val cvOvrAuc: Double,
        val specificLabels: List<BrmhSpecificLabel>
    )

    data class BrmhCohortScore(
        val meta: BrmhCohortMeta,
        val score: Double
    )

    data class BrmhCohortSupportResult(
        val scores: List<BrmhCohortScore>,
        val sourceChannels: Map<String, String>,
        val featureCount: Int,
        val modelVersion: String = MODEL_VERSION
    ) {
        val top3: List<BrmhCohortScore> get() = scores.take(3)
    }

    val cohortMetadata = listOf(
        BrmhCohortMeta("Addictive disorder", "Suchtbezogene Störung", 186, 0.615, listOf(BrmhSpecificLabel("Alcohol use disorder", 93), BrmhSpecificLabel("Behavioral addiction disorder", 93))),
        BrmhCohortMeta("Anxiety disorder", "Angststörung", 107, 0.569, listOf(BrmhSpecificLabel("Panic disorder", 59), BrmhSpecificLabel("Social anxiety disorder", 48))),
        BrmhCohortMeta("Healthy control", "Gesunde Kontrollgruppe", 95, 0.645, listOf(BrmhSpecificLabel("Healthy control", 95))),
        BrmhCohortMeta("Mood disorder", "Affektive Störung", 266, 0.568, listOf(BrmhSpecificLabel("Depressive disorder", 199), BrmhSpecificLabel("Bipolar disorder", 67))),
        BrmhCohortMeta("Obsessive compulsive disorder", "Zwangsstörung", 46, 0.644, listOf(BrmhSpecificLabel("Obsessive compulsitve disorder", 46))),
        BrmhCohortMeta("Schizophrenia", "Schizophrenie", 117, 0.567, listOf(BrmhSpecificLabel("Schizophrenia", 117))),
        BrmhCohortMeta("Trauma and stress related disorder", "Trauma-/stressbezogene Störung", 128, 0.529, listOf(BrmhSpecificLabel("Posttraumatic stress disorder", 52), BrmhSpecificLabel("Acute stress disorder", 38), BrmhSpecificLabel("Adjustment disorder", 38)))
    )

    private val featureMeans = doubleArrayOf(2.81459419233, 2.78091981973, 2.42272188111, 2.46692765643, 2.31903898114, 2.36991861058, 1.96440778164, 2.07180758505, 2.57323081459, 2.65058121135, 2.18805915845, 2.3477232337, 2.28829415724, 2.3691070975, 2.12396701929, 2.21471363039, 1.06581241067, 1.09310179765, 0.954565806412, 0.962350407359)
    private val featureScales = doubleArrayOf(0.47392627508, 0.466133924546, 0.487816837423, 0.477963363572, 0.48015326787, 0.485878688176, 0.485971576037, 0.487131756583, 0.808889854289, 0.817898733785, 0.720709128176, 0.739210294806, 0.463064935233, 0.47497020078, 0.531916746135, 0.507723386071, 0.423845161351, 0.436525913631, 0.467579333461, 0.440895899331)
    private val intercepts = doubleArrayOf(0.0161612877045, 0.0468902722645, -0.101073486994, 0.0945147737225, -0.18517780515, 0.0675087606249, 0.0611761978283)
    private val coefficients = arrayOf(
        doubleArrayOf(-0.442893626269, 0.308787050536, -0.293973441999, 0.168392221314, 0.622425618219, -0.223058012541, -0.270413921605, 0.0173856437176, 0.335666698681, -0.538787846122, 0.585615496233, -0.475351628756, 0.0562530429586, -0.201856506068, 0.101859993659, 0.123897654055, -0.122738572074, -0.0871798652738, 0.172107704949, -0.186304441718),
        doubleArrayOf(0.130752139751, -0.226061351738, 0.285573100613, 0.287601326797, -0.367711747949, -0.0653576892386, -0.0225161666546, 0.0456298694095, 0.247405238576, -0.0924537012912, -0.0483541934007, -0.147412664322, -0.313022809358, 0.505496722846, -0.463883686114, 0.380617581447, 0.365146205503, -0.256230677204, 0.33242632819, -0.576123671464),
        doubleArrayOf(-0.372335030291, 0.780889608026, -0.415186032109, -0.15014210616, 0.218761181215, -0.316083264254, 0.0928045185626, -0.0622468886021, -0.36194911576, 0.165226665274, 0.101322032393, 0.400571568218, 0.856689779043, -0.708756044677, -0.513165570676, -0.383865073547, -0.47440643694, 0.182005109447, 0.150162955857, 0.474777184499),
        doubleArrayOf(0.146296762942, -0.326579236689, 0.280076383842, 0.103729443884, -0.221605653433, 0.477984708044, -0.42021151816, -0.0544669490179, 0.0401742351259, 0.195459820499, -0.347587410809, -0.0619638711756, 0.082034389506, -0.0691848693019, 0.489485496212, -0.000355824266818, 0.0274753972675, 0.164084782161, -0.172722767027, -0.211211646843),
        doubleArrayOf(0.494360907949, -0.384931864701, 0.0041035376109, -0.684660131653, -0.427865294664, 0.206669744372, 0.646184411786, 0.143271989687, -0.666169563093, 0.35622494886, 0.164753726371, 0.208425508995, -0.54140840475, -0.102903240002, -0.249233404675, 0.179169355084, 0.0226884222091, 0.290075864351, -0.15134127497, 0.34657307048),
        doubleArrayOf(0.278726245416, -0.0054174107146, -0.0374174907099, 0.0888448046071, 0.053675076418, -0.270155339197, -0.246987310829, 0.261747973636, -0.0265522742222, 0.24462594135, -0.244435785831, -0.074265153774, -0.319806046444, 0.600462270687, 0.586224600233, -0.4144302818, 0.0383689908269, -0.14236998414, -0.264087320611, 0.260406227188),
        doubleArrayOf(-0.234907399498, -0.146686794719, 0.176823942751, 0.186234441212, 0.122320820195, 0.189999852815, 0.2211399869, -0.35132163883, 0.431424780692, -0.33029582857, -0.211313864957, 0.149996240816, 0.179260049045, -0.0232583334849, 0.0487125713612, 0.114966589028, 0.143465993207, -0.150385229342, -0.066545626388, -0.108116722142)
    )

    fun missingChannels(recording: MeegRecording): List<String> =
        requiredChannels.filter { resolveExact10x20(recording, it) == null }

    fun analyze(recording: MeegRecording): BrmhCohortSupportResult? {
        if (recording.sampleRateHz <= 0.0) return null
        val names = requiredChannels.associateWith { resolveExact10x20(recording, it) ?: return null }
        val values = mutableListOf<Double>()

        val bands = listOf(
            0.5 to 4.0,
            4.0 to 8.0,
            8.0 to 13.0,
            13.0 to 30.0,
            20.0 to 30.0
        )

        for ((lo, hi) in bands) {
            for (electrode in requiredChannels) {
                val samples = recording.channels.getValue(names.getValue(electrode))
                if (!usable(samples)) return null
                values += bandPower(samples, recording.sampleRateHz, lo, hi)
            }
        }

        if (values.size != featureMeans.size) return null
        val standardized = DoubleArray(values.size) { i ->
            val transformed = ln(1.0 + max(0.0, values[i]))
            (transformed - featureMeans[i]) / max(featureScales[i], EPS)
        }

        val logits = DoubleArray(cohortMetadata.size) { classIndex ->
            var z = intercepts[classIndex]
            val row = coefficients[classIndex]
            for (i in standardized.indices) z += row[i] * standardized[i]
            z
        }
        val maxLogit = logits.maxOrNull() ?: return null
        val exps = logits.map { exp(it - maxLogit) }
        val denom = exps.sum().takeIf { it.isFinite() && it > 0.0 } ?: return null

        val scores = cohortMetadata.indices.map { i ->
            BrmhCohortScore(cohortMetadata[i], exps[i] / denom)
        }.sortedByDescending { it.score }

        return BrmhCohortSupportResult(
            scores = scores,
            sourceChannels = names,
            featureCount = values.size
        )
    }

    private fun bandPower(samples: List<Double>, fs: Double, lo: Double, hi: Double): Double {
        val psd = SignalAnalysisEngine.welchPsd(samples, fs)
        if (psd.size < 2) return 0.0
        val df = psd[1].frequencyHz - psd[0].frequencyHz
        return psd.asSequence()
            .filter { it.frequencyHz >= lo && it.frequencyHz < hi }
            .sumOf { it.powerDensity * df }
            .coerceAtLeast(0.0)
    }

    private fun usable(values: List<Double>): Boolean {
        if (values.size < 32) return false
        val finite = values.count { it.isFinite() }
        if (finite.toDouble() / values.size < 0.995) return false
        val finiteValues = values.filter { it.isFinite() }
        return ((finiteValues.maxOrNull() ?: 0.0) - (finiteValues.minOrNull() ?: 0.0)) > EPS
    }

    private fun resolveExact10x20(recording: MeegRecording, electrode: String): String? =
        recording.channels.keys.firstOrNull { name ->
            val upper = name.uppercase()
            if (upper == electrode) return@firstOrNull true
            val tokens = upper.split(Regex("[^A-Z0-9]+" )).filter { it.isNotBlank() }
            tokens.contains(electrode)
        }
}
