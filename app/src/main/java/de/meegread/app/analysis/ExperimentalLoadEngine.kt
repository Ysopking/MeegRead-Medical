package de.meegread.app.analysis

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.ThermodynamicLoadAnalysis
import de.meegread.app.model.ThermodynamicLoadPoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

object ThermodynamicLoadEngine {
    const val ALGORITHM_VERSION = "mmsi-load-research-v1.1"
    const val OMEGA_KRIT = 5800.0
    private const val EPS = 1e-12
    private val electrodes = listOf("AF7", "AF8", "TP9", "TP10")

    fun missingChannels(recording: MeegRecording): List<String> = electrodes.filter { resolve(recording, it) == null }

    fun analyze(recording: MeegRecording, windowSeconds: Double = 4.0, hopSeconds: Double = 1.0): ThermodynamicLoadAnalysis? {
        if (recording.sampleRateHz <= 0.0) return null
        val names = electrodes.associateWith { resolve(recording, it) ?: return null }
        val data = names.mapValues { recording.channels.getValue(it.value) }
        if (data.values.any { unusable(it) }) return null
        val available = data.values.minOf { it.size }
        val fs = recording.sampleRateHz
        val window = min((windowSeconds * fs).toInt().coerceAtLeast(32), available)
        val hop = (hopSeconds * fs).toInt().coerceAtLeast(1)
        val starts = starts(available, window, hop)
        if (starts.isEmpty()) return null
        var rawLoad = 0.0
        var first: Double? = null
        val points = mutableListOf<ThermodynamicLoadPoint>()
        starts.forEachIndexed { index, start ->
            val end = start + window
            val af7 = data.getValue("AF7").subList(start, end)
            val af8 = data.getValue("AF8").subList(start, end)
            val tp9 = data.getValue("TP9").subList(start, end)
            val tp10 = data.getValue("TP10").subList(start, end)
            val a7 = power(af7, fs, 8.0, 13.0)
            val a8 = power(af8, fs, 8.0, 13.0)
            val alpha = (a7 + a8) / 2.0
            val theta = (power(tp9, fs, 4.0, 8.0) + power(tp10, fs, 4.0, 8.0)) / 2.0
            val highBeta = (power(tp9, fs, 20.0, 30.0) + power(tp10, fs, 20.0, 30.0)) / 2.0
            val midBeta = (power(af7, fs, 13.0, 20.0) + power(af8, fs, 13.0, 20.0)) / 2.0
            val gradE = (highBeta + theta) / (alpha + EPS)
            val faa = ln(a8 + EPS) - ln(a7 + EPS)
            val h = 1.0 / (1.0 + exp(-2.0 * faa))
            val flow = (midBeta * alpha) / (highBeta + EPS) * h
            val pressure = gradE * (1.0 - h)
            val friction = abs(gradE - pressure)
            val dt = if (index == 0) hop / fs else (start - starts[index - 1]) / fs
            rawLoad += friction * dt
            val bounded = ThermodynamicLoadMath.bounded(rawLoad)
            val t = (start + window / 2.0) / fs
            if (first == null && rawLoad >= OMEGA_KRIT) first = t
            points += ThermodynamicLoadPoint(t, gradE, faa, h, flow, pressure, friction, rawLoad, bounded, rawLoad / OMEGA_KRIT)
        }
        return ThermodynamicLoadAnalysis(points, OMEGA_KRIT, points.any { it.wRaw >= OMEGA_KRIT }, first, points.last().wRaw, points.last().wBounded, points.maxOf { it.wRaw }, points.map { it.faa }.average(), names)
    }

    fun boundedLoad(rawLoad: Double): Double = ThermodynamicLoadMath.bounded(rawLoad)
    fun isOmegaBreach(rawLoad: Double): Boolean = rawLoad >= OMEGA_KRIT

    private fun unusable(values: List<Double>): Boolean {
        if (values.size < 32) return true
        val finite = values.filter { it.isFinite() }
        return finite.size.toDouble() / values.size < 0.995 || (finite.maxOrNull() ?: 0.0) - (finite.minOrNull() ?: 0.0) <= EPS
    }

    private fun power(values: List<Double>, fs: Double, lo: Double, hi: Double): Double {
        val psd = SignalAnalysisEngine.welchPsd(values, fs)
        if (psd.size < 2) return 0.0
        val df = psd[1].frequencyHz - psd[0].frequencyHz
        return psd.filter { it.frequencyHz >= lo && it.frequencyHz < hi }.sumOf { it.powerDensity * df }
    }

    private fun resolve(recording: MeegRecording, electrode: String): String? = recording.channels.keys.firstOrNull { it.equals(electrode, true) }
        ?: recording.channels.keys.firstOrNull { it.uppercase().contains(electrode) }

    private fun starts(total: Int, window: Int, hop: Int): List<Int> {
        if (total < window) return emptyList()
        val out = mutableListOf<Int>()
        var start = 0
        while (start + window <= total) { out += start; start += hop }
        if (out.lastOrNull() != total - window) out += total - window
        return out.distinct()
    }
}
