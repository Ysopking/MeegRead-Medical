package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object ResearchFriction19Engine {
    const val VERSION = "friction19-research-v1.0"
    const val EPSILON = 1e-6
    const val WARNING_LIMIT = 2.30
    const val HC_RAW_MEDIAN = 0.7597781027727232
    val RESEARCH_LIMIT: Double = E

    val ELECTRODES = listOf(
        "FP1", "FP2", "F7", "F3", "FZ", "F4", "F8",
        "T3", "C3", "CZ", "C4", "T4", "T5", "P3", "PZ", "P4", "T6", "O1", "O2"
    )

    enum class Regime { BASELINE, ELEVATED, HIGH, ABOVE_RESEARCH_LIMIT }

    data class Result(
        val capacityZ: Double,
        val loadY: Double,
        val dispersionD: Double,
        val rawRatio: Double,
        val normalizedRatio: Double,
        val alphaCoherencePercent: Double,
        val regime: Regime,
        val localRatios: Map<String, Double>,
        val sourceChannels: Map<String, String>
    )

    fun evaluateFeatures(
        alpha: DoubleArray,
        beta: DoubleArray,
        highBeta: DoubleArray,
        gamma: DoubleArray,
        allSixBandCells: DoubleArray,
        alphaCoherencePercent: Double,
        normalize: Boolean = true,
        sourceChannels: Map<String, String> = emptyMap()
    ): Result {
        require(alpha.size == 19 && beta.size == 19 && highBeta.size == 19 && gamma.size == 19)
        require(allSixBandCells.size == 114)

        val meanAlpha = alpha.avg()
        val meanBeta = beta.avg()
        val meanHighBeta = highBeta.avg()
        val meanGamma = gamma.avg()
        val mu = allSixBandCells.avg()
        val sigma = sqrt(allSixBandCells.sumOf { v -> val x = safe(v); (x - mu) * (x - mu) } / 114.0)
        val dispersion = sigma / (abs(mu) + EPSILON)
        val coherence = alphaCoherencePercent.coerceIn(0.0, 100.0)
        val z = meanAlpha * (1.0 + coherence / 100.0)
        val y = (0.5 * meanBeta + meanHighBeta + meanGamma) * (1.0 + dispersion)
        val raw = y / (z + EPSILON)
        val ratio = if (normalize) raw / HC_RAW_MEDIAN else raw

        val local = linkedMapOf<String, Double>()
        for (i in ELECTRODES.indices) {
            val yi = 0.5 * safe(beta[i]) + safe(highBeta[i]) + safe(gamma[i])
            val zi = safe(alpha[i]) + EPSILON
            local[ELECTRODES[i]] = yi / zi
        }

        val regime = when {
            ratio <= 1.0 -> Regime.BASELINE
            ratio < WARNING_LIMIT -> Regime.ELEVATED
            ratio < RESEARCH_LIMIT -> Regime.HIGH
            else -> Regime.ABOVE_RESEARCH_LIMIT
        }
        return Result(z, y, dispersion, raw, ratio, coherence, regime, local, sourceChannels)
    }

    fun analyze(recording: MeegRecording): Result? {
        if (recording.sampleRateHz <= 0.0) return null
        val names = ELECTRODES.associateWith { resolve(recording, it) ?: return null }
        if (names.values.any { recording.channels.getValue(it).size < 64 }) return null

        val ranges = listOf(0.5 to 4.0, 4.0 to 8.0, 8.0 to 12.0, 12.0 to 20.0, 20.0 to 30.0, 30.0 to 45.0)
        val powers = Array(6) { DoubleArray(19) }
        for ((b, range) in ranges.withIndex()) {
            for (i in ELECTRODES.indices) {
                val values = recording.channels.getValue(names.getValue(ELECTRODES[i]))
                powers[b][i] = bandPower(values, recording.sampleRateHz, range.first, range.second)
            }
        }
        val cells = DoubleArray(114)
        var cursor = 0
        for (band in powers) for (value in band) cells[cursor++] = value

        return evaluateFeatures(
            alpha = powers[2], beta = powers[3], highBeta = powers[4], gamma = powers[5],
            allSixBandCells = cells,
            alphaCoherencePercent = meanAllPairAlphaCoherence(recording, names),
            normalize = true,
            sourceChannels = names
        )
    }

    fun missingChannels(recording: MeegRecording): List<String> = ELECTRODES.filter { resolve(recording, it) == null }

    private fun meanAllPairAlphaCoherence(recording: MeegRecording, names: Map<String, String>): Double {
        var sum = 0.0
        var count = 0
        for (i in 0 until ELECTRODES.lastIndex) for (j in i + 1 until ELECTRODES.size) {
            val a = names.getValue(ELECTRODES[i]); val b = names.getValue(ELECTRODES[j])
            val c = SignalAnalysisEngine.coherence(a, recording.channels.getValue(a), b, recording.channels.getValue(b), recording.sampleRateHz)
            for (k in c.frequenciesHz.indices) {
                if (c.frequenciesHz[k] >= 8.0 && c.frequenciesHz[k] < 12.0) {
                    val v = c.coherence.getOrNull(k)
                    if (v != null && v.isFinite()) { sum += v.coerceIn(0.0, 1.0); count++ }
                }
            }
        }
        return if (count == 0) 0.0 else 100.0 * sum / count
    }

    private fun bandPower(samples: List<Double>, fs: Double, lo: Double, hi: Double): Double {
        val psd = SignalAnalysisEngine.welchPsd(samples, fs)
        if (psd.size < 2) return 0.0
        val df = psd[1].frequencyHz - psd[0].frequencyHz
        return psd.asSequence().filter { it.frequencyHz >= lo && it.frequencyHz < hi }.sumOf { it.powerDensity * df }.coerceAtLeast(0.0)
    }

    private fun resolve(recording: MeegRecording, electrode: String): String? = recording.channels.keys.firstOrNull { name ->
        val upper = name.uppercase()
        upper == electrode || upper.split(Regex("[^A-Z0-9]+" )).filter { it.isNotBlank() }.contains(electrode)
    }

    private fun safe(v: Double): Double = if (v.isFinite()) max(0.0, v) else 0.0
    private fun DoubleArray.avg(): Double = if (isEmpty()) 0.0 else map { safe(it) }.average()
}
