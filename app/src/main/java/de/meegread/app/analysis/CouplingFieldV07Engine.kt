package de.meegread.app.analysis

import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegRecording
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frozen research adapter for the v0.7 recursive coupling-field operationalisation.
 *
 * The implementation deliberately separates observables from clinical claims:
 * - Psi: analytic EEG/MEG node signal in a fixed analysis window
 * - A_ij: circular mean phase transport from j to i
 * - K_ij: phase-locking value
 * - kappa: normalized mutual-information overlap
 * - J_i: normalized phase-flow proxy
 * - Gamma = kappa * c
 * - Q = P * (1 - Gamma)
 * - chi_dyn = rho(A_VAR(1)) via the Gelfand spectral-radius limit
 * - B_d: deterministic greedy pairwise coarse graining with phase transport
 *
 * Unless the recording explicitly declares a source-space representation, the result is a
 * SENSOR_SPACE_PROXY. Q is not physical power and chi_dyn is not the calibrated L/C load ratio.
 */
object CouplingFieldV07Engine {
    const val VERSION = "coupling-field-v0.7-adapter-1.0"
    const val MIN_SAMPLES = 128
    const val DEFAULT_WINDOW_SAMPLES = 2048
    const val DEFAULT_MAX_SCALES = 8
    private const val EPS = 1e-12

    enum class OperationalSpace {
        SOURCE_SPACE,
        SENSOR_SPACE_PROXY
    }

    data class PairCoupling(
        val nodeA: String,
        val nodeB: String,
        val phaseTransportRad: Double,
        val phaseLocking: Double,
        val informationOverlap: Double,
        val bundleWeight: Double
    )

    data class ScaleResult(
        val level: Int,
        val nodeCount: Int,
        val kappa: Double,
        val directionCoherence: Double,
        val gamma: Double,
        val flowMagnitudeP: Double,
        val couplingLossQ: Double,
        val chiDyn: Double,
        val stabilityReserve: Double,
        val meanPhaseLocking: Double,
        val pairs: List<PairCoupling>
    )

    data class Result(
        val version: String,
        val operationalSpace: OperationalSpace,
        val sourceChannels: List<String>,
        val sampleRateHz: Double,
        val analysisWindowSamples: Int,
        val finiteInputFraction: Double,
        val scales: List<ScaleResult>,
        val warnings: List<String>
    ) {
        val baseScale: ScaleResult? get() = scales.firstOrNull()
    }

    private data class ComplexNode(
        val label: String,
        val real: DoubleArray,
        val imaginary: DoubleArray
    ) {
        val size: Int get() = min(real.size, imaginary.size)
    }

    fun analyze(
        recording: MeegRecording,
        requestedWindowSamples: Int = DEFAULT_WINDOW_SAMPLES,
        maxScales: Int = DEFAULT_MAX_SCALES
    ): Result? {
        if (recording.sampleRateHz <= 0.0 || !recording.sampleRateHz.isFinite()) return null

        val eligible = recording.channels.entries
            .filter { (name, samples) ->
                samples.size >= MIN_SAMPLES && recording.infoFor(name).type in setOf(
                    ChannelType.EEG,
                    ChannelType.MEG_MAG,
                    ChannelType.MEG_GRAD
                )
            }
            .sortedBy { it.key }

        if (eligible.size < 2) return null
        val commonLength = eligible.minOf { it.value.size }
        val requested = min(requestedWindowSamples.coerceAtLeast(MIN_SAMPLES), commonLength)
        val window = FastFourierTransform.floorPowerOfTwo(requested)
        if (window < MIN_SAMPLES) return null

        var finiteCount = 0L
        var totalCount = 0L
        val nodes = eligible.mapNotNull { (name, raw) ->
            val tail = raw.takeLast(window)
            totalCount += tail.size
            finiteCount += tail.count { it.isFinite() }
            val clean = tail.map { if (it.isFinite()) it else 0.0 }
            val high = min(40.0, recording.sampleRateHz * 0.45)
            if (high <= 0.5) return@mapNotNull null
            val low = min(1.0, high * 0.25)
            val filtered = SignalAnalysisEngine.bandpassFilter(clean, recording.sampleRateHz, low, high)
            val normalized = zScore(filtered)
            val analytic = analyticSignal(normalized)
            ComplexNode(name, analytic.first, analytic.second)
        }
        if (nodes.size < 2) return null

        val scales = mutableListOf<ScaleResult>()
        var current = nodes
        var level = 0
        val hardMax = maxScales.coerceAtLeast(1)
        while (current.isNotEmpty() && level < hardMax) {
            val summary = summarize(current, recording.sampleRateHz, level)
            scales += summary
            if (current.size <= 1) break
            val next = coarseGrain(current, summary.pairs)
            if (next.size >= current.size) break
            current = next
            level++
        }

        val metadataSpace = listOfNotNull(
            recording.metadata["space"],
            recording.metadata["analysis_space"],
            recording.metadata["source_space"]
        ).joinToString(" ").lowercase()
        val operationalSpace = if ("source" in metadataSpace) {
            OperationalSpace.SOURCE_SPACE
        } else {
            OperationalSpace.SENSOR_SPACE_PROXY
        }

        val warnings = buildList {
            if (operationalSpace == OperationalSpace.SENSOR_SPACE_PROXY) {
                add("Sensorraum-Proxy: v0.7 fordert für den starken Neuro-Test eine Quellraumrekonstruktion.")
            }
            add("Q=P(1-Gamma) ist ein normierter Kopplungsverlust-Proxy, keine physikalische Leistung.")
            add("chi_dyn=rho(A_VAR1) ist eine dynamische Stabilitätskoordinate; L/C wird ohne kalibriertes Belastungs-/Recovery-Protokoll nicht behauptet.")
            add("Forschungsfunktion: keine Diagnose, kein Biomarker und keine Therapieentscheidung.")
        }

        return Result(
            version = VERSION,
            operationalSpace = operationalSpace,
            sourceChannels = nodes.map { it.label },
            sampleRateHz = recording.sampleRateHz,
            analysisWindowSamples = window,
            finiteInputFraction = if (totalCount == 0L) 0.0 else finiteCount.toDouble() / totalCount,
            scales = scales,
            warnings = warnings
        )
    }

    private fun summarize(nodes: List<ComplexNode>, sampleRateHz: Double, level: Int): ScaleResult {
        val pairs = mutableListOf<PairCoupling>()
        for (i in 0 until nodes.lastIndex) {
            for (j in i + 1 until nodes.size) {
                val phase = phaseCoupling(nodes[i], nodes[j])
                val info = normalizedMutualInformation(nodes[i].real, nodes[j].real)
                pairs += PairCoupling(
                    nodeA = nodes[i].label,
                    nodeB = nodes[j].label,
                    phaseTransportRad = phase.first,
                    phaseLocking = phase.second,
                    informationOverlap = info,
                    bundleWeight = (phase.second * info).coerceIn(0.0, 1.0)
                )
            }
        }

        val kappa = if (pairs.isEmpty()) 1.0 else pairs.map { it.informationOverlap }.average().coerceIn(0.0, 1.0)
        val currents = nodes.map { phaseFlow(it, sampleRateHz) }
        val p = currents.sumOf { abs(it) }
        val c = if (p <= EPS) 1.0 else (abs(currents.sum()) / p).coerceIn(0.0, 1.0)
        val gamma = (kappa * c).coerceIn(0.0, 1.0)
        val q = max(0.0, p * (1.0 - gamma))
        val chi = estimateVarSpectralRadius(nodes.map { it.real })
        val meanPlv = if (pairs.isEmpty()) 1.0 else pairs.map { it.phaseLocking }.average().coerceIn(0.0, 1.0)

        return ScaleResult(
            level = level,
            nodeCount = nodes.size,
            kappa = kappa,
            directionCoherence = c,
            gamma = gamma,
            flowMagnitudeP = p,
            couplingLossQ = q,
            chiDyn = chi,
            stabilityReserve = 1.0 - chi,
            meanPhaseLocking = meanPlv,
            pairs = pairs.sortedByDescending { it.bundleWeight }
        )
    }

    private fun coarseGrain(nodes: List<ComplexNode>, pairMetrics: List<PairCoupling>): List<ComplexNode> {
        if (nodes.size <= 1) return nodes
        val byName = nodes.associateBy { it.label }
        val used = mutableSetOf<String>()
        val output = mutableListOf<ComplexNode>()

        for (pair in pairMetrics.sortedByDescending { it.bundleWeight }) {
            if (pair.nodeA in used || pair.nodeB in used) continue
            val a = byName[pair.nodeA] ?: continue
            val b = byName[pair.nodeB] ?: continue
            output += bundle(a, b, pair)
            used += pair.nodeA
            used += pair.nodeB
        }

        nodes.filter { it.label !in used }.forEach(output::add)
        return output.sortedBy { it.label }
    }

    private fun bundle(a: ComplexNode, b: ComplexNode, pair: PairCoupling): ComplexNode {
        val n = min(a.size, b.size)
        val weight = pair.bundleWeight.coerceIn(0.0, 1.0)
        val phase = pair.phaseTransportRad
        val cosP = cos(phase)
        val sinP = sin(phase)
        val norm = sqrt(1.0 + weight * weight).coerceAtLeast(EPS)
        val real = DoubleArray(n)
        val imaginary = DoubleArray(n)
        for (t in 0 until n) {
            val br = b.real[t] * cosP - b.imaginary[t] * sinP
            val bi = b.real[t] * sinP + b.imaginary[t] * cosP
            real[t] = (a.real[t] + weight * br) / norm
            imaginary[t] = (a.imaginary[t] + weight * bi) / norm
        }
        return ComplexNode("(${a.label}+${b.label})", real, imaginary)
    }

    /** Returns phase(i)-phase(j) and PLV. */
    private fun phaseCoupling(a: ComplexNode, b: ComplexNode): Pair<Double, Double> {
        val n = min(a.size, b.size)
        if (n == 0) return 0.0 to 0.0
        var sumCos = 0.0
        var sumSin = 0.0
        var count = 0
        for (t in 0 until n) {
            val ampA = sqrt(a.real[t] * a.real[t] + a.imaginary[t] * a.imaginary[t])
            val ampB = sqrt(b.real[t] * b.real[t] + b.imaginary[t] * b.imaginary[t])
            val denom = ampA * ampB
            if (denom <= EPS) continue
            sumCos += (a.real[t] * b.real[t] + a.imaginary[t] * b.imaginary[t]) / denom
            sumSin += (a.imaginary[t] * b.real[t] - a.real[t] * b.imaginary[t]) / denom
            count++
        }
        if (count == 0) return 0.0 to 0.0
        val meanCos = sumCos / count
        val meanSin = sumSin / count
        return atan2(meanSin, meanCos) to sqrt(meanCos * meanCos + meanSin * meanSin).coerceIn(0.0, 1.0)
    }

    private fun phaseFlow(node: ComplexNode, sampleRateHz: Double): Double {
        if (node.size < 2) return 0.0
        var sum = 0.0
        var count = 0
        var previous = atan2(node.imaginary[0], node.real[0])
        for (t in 1 until node.size) {
            val phase = atan2(node.imaginary[t], node.real[t])
            val dPhi = wrapPhase(phase - previous)
            val amplitude2 = node.real[t] * node.real[t] + node.imaginary[t] * node.imaginary[t]
            if (amplitude2.isFinite() && dPhi.isFinite()) {
                sum += amplitude2 * dPhi * sampleRateHz
                count++
            }
            previous = phase
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun wrapPhase(value: Double): Double {
        var x = value
        while (x > PI) x -= 2.0 * PI
        while (x < -PI) x += 2.0 * PI
        return x
    }

    /**
     * Truncated odd Hilbert kernel. The real signal is already band-limited and z-normalized.
     * This avoids adding a second FFT implementation solely for the analytic representation.
     */
    private fun analyticSignal(realInput: List<Double>, radius: Int = 31): Pair<DoubleArray, DoubleArray> {
        val real = realInput.toDoubleArray()
        val imag = DoubleArray(real.size)
        for (t in real.indices) {
            var acc = 0.0
            for (k in -radius..radius) {
                if (k == 0 || abs(k) % 2 == 0) continue
                val index = t - k
                if (index !in real.indices) continue
                acc += (2.0 / (PI * k)) * real[index]
            }
            imag[t] = acc
        }
        return real to imag
    }

    private fun zScore(values: List<Double>): List<Double> {
        if (values.isEmpty()) return values
        val mean = values.average()
        val variance = values.sumOf { val d = it - mean; d * d } / values.size
        val sd = sqrt(variance).coerceAtLeast(EPS)
        return values.map { (it - mean) / sd }
    }

    internal fun normalizedMutualInformation(a: DoubleArray, b: DoubleArray, bins: Int = 8): Double {
        val n = min(a.size, b.size)
        if (n < 8) return 0.0
        val aa = a.copyOf(n)
        val bb = b.copyOf(n)
        val minA = aa.minOrNull() ?: return 0.0
        val maxA = aa.maxOrNull() ?: return 0.0
        val minB = bb.minOrNull() ?: return 0.0
        val maxB = bb.maxOrNull() ?: return 0.0
        if (maxA - minA <= EPS || maxB - minB <= EPS) return 0.0

        val joint = Array(bins) { IntArray(bins) }
        val countA = IntArray(bins)
        val countB = IntArray(bins)
        for (i in 0 until n) {
            val x = binIndex(aa[i], minA, maxA, bins)
            val y = binIndex(bb[i], minB, maxB, bins)
            joint[x][y]++
            countA[x]++
            countB[y]++
        }

        var mi = 0.0
        var hA = 0.0
        var hB = 0.0
        for (x in 0 until bins) {
            val px = countA[x].toDouble() / n
            if (px > 0.0) hA -= px * ln(px)
            val py = countB[x].toDouble() / n
            if (py > 0.0) hB -= py * ln(py)
        }
        for (x in 0 until bins) for (y in 0 until bins) {
            val pxy = joint[x][y].toDouble() / n
            if (pxy <= 0.0) continue
            val px = countA[x].toDouble() / n
            val py = countB[y].toDouble() / n
            if (px > 0.0 && py > 0.0) mi += pxy * ln(pxy / (px * py))
        }
        val denom = sqrt(hA * hB)
        return if (denom <= EPS) 0.0 else (mi / denom).coerceIn(0.0, 1.0)
    }

    private fun binIndex(value: Double, minValue: Double, maxValue: Double, bins: Int): Int {
        val fraction = (value - minValue) / (maxValue - minValue)
        return floor(fraction * bins).toInt().coerceIn(0, bins - 1)
    }

    /**
     * Fits a ridge-regularised VAR(1), A = YX' (XX' + lambda I)^-1, and estimates rho(A)
     * with the Gelfand limit ||A^k||^(1/k). No diagnostic threshold is applied here.
     */
    internal fun estimateVarSpectralRadius(signals: List<DoubleArray>, ridge: Double = 1e-3): Double {
        if (signals.isEmpty()) return 0.0
        val n = signals.size
        val tCount = signals.minOf { it.size }
        if (tCount < 8) return 0.0
        val standardized = signals.map { input ->
            val mean = input.take(tCount).average()
            val variance = input.take(tCount).sumOf { val d = it - mean; d * d } / tCount
            val sd = sqrt(variance).coerceAtLeast(EPS)
            DoubleArray(tCount) { t -> (input[t] - mean) / sd }
        }

        val xx = Array(n) { DoubleArray(n) }
        val yx = Array(n) { DoubleArray(n) }
        val observations = (tCount - 1).toDouble()
        for (t in 1 until tCount) {
            for (i in 0 until n) {
                val yi = standardized[i][t]
                for (j in 0 until n) {
                    val xj = standardized[j][t - 1]
                    yx[i][j] += yi * xj / observations
                    xx[i][j] += standardized[i][t - 1] * xj / observations
                }
            }
        }
        for (i in 0 until n) xx[i][i] += ridge
        val inverse = invert(xx) ?: return 0.0
        val a = multiply(yx, inverse)
        return spectralRadiusGelfand(a)
    }

    private fun spectralRadiusGelfand(matrix: Array<DoubleArray>, iterations: Int = 80): Double {
        val n = matrix.size
        if (n == 0) return 0.0
        var power = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        var cumulativeLogNorm = 0.0
        var estimate = 0.0
        for (k in 1..iterations) {
            power = multiply(power, matrix)
            val norm = frobeniusNorm(power)
            if (!norm.isFinite() || norm <= EPS) return 0.0
            cumulativeLogNorm += ln(norm)
            for (i in 0 until n) for (j in 0 until n) power[i][j] /= norm
            estimate = exp(cumulativeLogNorm / k)
        }
        return if (estimate.isFinite()) max(0.0, estimate) else 0.0
    }

    private fun frobeniusNorm(matrix: Array<DoubleArray>): Double = sqrt(matrix.sumOf { row -> row.sumOf { it * it } })

    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val inner = if (a.isEmpty()) 0 else a[0].size
        val cols = if (b.isEmpty()) 0 else b[0].size
        val out = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) {
            for (k in 0 until inner) {
                val aik = a[i][k]
                if (abs(aik) <= EPS) continue
                for (j in 0 until cols) out[i][j] += aik * b[k][j]
            }
        }
        return out
    }

    private fun invert(input: Array<DoubleArray>): Array<DoubleArray>? {
        val n = input.size
        if (n == 0 || input.any { it.size != n }) return null
        val aug = Array(n) { i -> DoubleArray(2 * n) { j ->
            when {
                j < n -> input[i][j]
                j - n == i -> 1.0
                else -> 0.0
            }
        } }

        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(aug[row][col]) > abs(aug[pivot][col])) pivot = row
            if (abs(aug[pivot][col]) <= EPS) return null
            if (pivot != col) {
                val tmp = aug[pivot]
                aug[pivot] = aug[col]
                aug[col] = tmp
            }
            val scale = aug[col][col]
            for (j in 0 until 2 * n) aug[col][j] /= scale
            for (row in 0 until n) {
                if (row == col) continue
                val factor = aug[row][col]
                if (abs(factor) <= EPS) continue
                for (j in 0 until 2 * n) aug[row][j] -= factor * aug[col][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> aug[i][j + n] } }
    }
}
