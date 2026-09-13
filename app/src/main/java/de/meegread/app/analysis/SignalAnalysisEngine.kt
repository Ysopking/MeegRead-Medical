package de.meegread.app.analysis

import de.meegread.app.model.BandPower
import de.meegread.app.model.ChannelMetrics
import de.meegread.app.model.ChannelType
import de.meegread.app.model.CoherenceResult
import de.meegread.app.model.Epoch
import de.meegread.app.model.FrequencyBand
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.SpectralPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SignalAnalysisEngine {
    fun analyze(recording: MeegRecording): List<ChannelMetrics> = recording.channels.map { (name, samples) -> analyzeChannel(name, samples, recording.sampleRateHz) }.sortedBy { it.channel }

    fun analyzeChannel(channel: String, samples: List<Double>, sampleRateHz: Double): ChannelMetrics {
        require(samples.isNotEmpty()) { "Kanal $channel enthält keine Samples." }
        val finite = samples.filter { it.isFinite() }
        require(finite.isNotEmpty()) { "Kanal $channel enthält keine endlichen Samples." }
        val mean = finite.average()
        val rms = sqrt(finite.sumOf { it * it } / finite.size)
        val variance = finite.sumOf { (it - mean).pow(2) } / finite.size
        return ChannelMetrics(channel, mean, rms, sqrt(variance), (finite.maxOrNull() ?: 0.0) - (finite.minOrNull() ?: 0.0), dominantFrequency(finite, sampleRateHz))
    }

    fun welchPsd(samples: List<Double>, sampleRateHz: Double, requestedSegmentSize: Int = 512): List<SpectralPoint> {
        val clean = samples.filter { it.isFinite() }
        if (clean.size < 8 || sampleRateHz <= 0.0) return emptyList()
        val segmentSize = FastFourierTransform.floorPowerOfTwo(min(requestedSegmentSize.coerceAtLeast(8), clean.size)).coerceAtLeast(8)
        val hop = (segmentSize / 2).coerceAtLeast(1)
        val starts = segmentStarts(clean.size, segmentSize, hop)
        val window = DoubleArray(segmentSize) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (segmentSize - 1)) }
        val windowPower = window.sumOf { it * it }.coerceAtLeast(1e-30)
        val bins = segmentSize / 2 + 1
        val accumulated = DoubleArray(bins)
        starts.forEach { start ->
            val mean = (start until start + segmentSize).sumOf { clean[it] } / segmentSize
            val segment = DoubleArray(segmentSize) { index -> (clean[start + index] - mean) * window[index] }
            val spectrum = FastFourierTransform.transformReal(segment)
            for (k in 0 until bins) {
                var power = (spectrum.real[k] * spectrum.real[k] + spectrum.imaginary[k] * spectrum.imaginary[k]) / (sampleRateHz * windowPower)
                if (k != 0 && k != segmentSize / 2) power *= 2.0
                accumulated[k] += power
            }
        }
        val count = starts.size.coerceAtLeast(1)
        return (0 until bins).map { k -> SpectralPoint(k * sampleRateHz / segmentSize, accumulated[k] / count) }
    }

    fun bandPowers(samples: List<Double>, sampleRateHz: Double, bands: List<FrequencyBand> = FrequencyBand.entries): List<BandPower> {
        val psd = welchPsd(samples, sampleRateHz)
        if (psd.size < 2) return emptyList()
        val df = psd[1].frequencyHz - psd[0].frequencyHz
        val total = psd.filter { it.frequencyHz in 0.5..min(80.0, sampleRateHz / 2.0) }.sumOf { it.powerDensity * df }.coerceAtLeast(1e-30)
        return bands.map { band ->
            val absolute = psd.filter { it.frequencyHz >= band.minHz && it.frequencyHz < band.maxHz }.sumOf { it.powerDensity * df }
            BandPower(band, absolute, absolute / total)
        }
    }

    fun coherence(channelA: String, samplesA: List<Double>, channelB: String, samplesB: List<Double>, sampleRateHz: Double, requestedSegmentSize: Int = 512): CoherenceResult {
        val available = min(samplesA.size, samplesB.size)
        if (available < 8 || sampleRateHz <= 0.0) return CoherenceResult(channelA, channelB, emptyList(), emptyList())
        val aInput = samplesA.take(available).map { if (it.isFinite()) it else 0.0 }
        val bInput = samplesB.take(available).map { if (it.isFinite()) it else 0.0 }
        val segmentSize = FastFourierTransform.floorPowerOfTwo(min(requestedSegmentSize.coerceAtLeast(8), available)).coerceAtLeast(8)
        val starts = segmentStarts(available, segmentSize, (segmentSize / 2).coerceAtLeast(1))
        val bins = segmentSize / 2 + 1
        val sxx = DoubleArray(bins); val syy = DoubleArray(bins); val sxyReal = DoubleArray(bins); val sxyImag = DoubleArray(bins)
        val window = DoubleArray(segmentSize) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (segmentSize - 1)) }
        starts.forEach { start ->
            val meanA = (start until start + segmentSize).sumOf { aInput[it] } / segmentSize
            val meanB = (start until start + segmentSize).sumOf { bInput[it] } / segmentSize
            val a = DoubleArray(segmentSize) { i -> (aInput[start + i] - meanA) * window[i] }
            val b = DoubleArray(segmentSize) { i -> (bInput[start + i] - meanB) * window[i] }
            val fa = FastFourierTransform.transformReal(a); val fb = FastFourierTransform.transformReal(b)
            for (k in 0 until bins) {
                val ar = fa.real[k]; val ai = fa.imaginary[k]; val br = fb.real[k]; val bi = fb.imaginary[k]
                sxx[k] += ar * ar + ai * ai; syy[k] += br * br + bi * bi
                sxyReal[k] += ar * br + ai * bi; sxyImag[k] += ai * br - ar * bi
            }
        }
        val values = (0 until bins).map { k ->
            val denominator = (sxx[k] * syy[k]).coerceAtLeast(1e-30)
            ((sxyReal[k] * sxyReal[k] + sxyImag[k] * sxyImag[k]) / denominator).coerceIn(0.0, 1.0)
        }
        return CoherenceResult(channelA, channelB, (0 until bins).map { it * sampleRateHz / segmentSize }, values)
    }

    fun extractEpochs(recording: MeegRecording, preSeconds: Double, postSeconds: Double, eventFilter: (de.meegread.app.model.MeegEvent) -> Boolean = { true }): List<Epoch> {
        require(preSeconds >= 0.0 && postSeconds > 0.0)
        val preSamples = (preSeconds * recording.sampleRateHz).toInt(); val postSamples = (postSeconds * recording.sampleRateHz).toInt()
        return recording.events.filter(eventFilter).mapNotNull { event ->
            val start = event.sample - preSamples; val endExclusive = event.sample + postSamples
            if (start < 0 || endExclusive > recording.sampleCount) return@mapNotNull null
            val channels = recording.channels.mapValues { (_, values) -> if (endExclusive <= values.size) values.subList(start, endExclusive).toList() else emptyList() }.filterValues { it.isNotEmpty() }
            if (channels.isEmpty()) null else Epoch(event, -preSeconds, postSeconds, channels)
        }
    }

    fun averageEpochChannel(epochs: List<Epoch>, channel: String): List<Double> {
        val valid = epochs.mapNotNull { it.channels[channel] }.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return emptyList()
        val length = valid.minOf { it.size }
        return List(length) { index -> valid.sumOf { it[index] } / valid.size }
    }

    fun commonAverageReference(recording: MeegRecording): MeegRecording {
        val eegNames = recording.channels.keys.filter { recording.infoFor(it).type == ChannelType.EEG }
        if (eegNames.size < 2) return recording
        val commonLength = eegNames.minOf { recording.channels.getValue(it).size }
        val averages = DoubleArray(commonLength) { index -> eegNames.sumOf { recording.channels.getValue(it)[index] } / eegNames.size }
        val updated = recording.channels.mapValues { (name, samples) -> if (name !in eegNames) samples else samples.mapIndexed { index, value -> if (index < commonLength) value - averages[index] else value } }
        return recording.copy(channels = updated, metadata = recording.metadata + ("reference" to "common-average"))
    }

    fun notchFilter(samples: List<Double>, sampleRateHz: Double, frequencyHz: Double = 50.0, q: Double = 30.0): List<Double> {
        if (samples.isEmpty() || sampleRateHz <= 0.0 || frequencyHz <= 0.0 || frequencyHz >= sampleRateHz / 2.0) return samples
        val omega = 2.0 * PI * frequencyHz / sampleRateHz; val alpha = sin(omega) / (2.0 * q.coerceAtLeast(0.1)); val a0 = 1.0 + alpha
        return biquad(samples, 1.0 / a0, -2.0 * cos(omega) / a0, 1.0 / a0, -2.0 * cos(omega) / a0, (1.0 - alpha) / a0)
    }

    fun bandpassFilter(samples: List<Double>, sampleRateHz: Double, lowHz: Double, highHz: Double): List<Double> {
        if (samples.isEmpty() || sampleRateHz <= 0.0) return samples
        var result = samples
        if (lowHz > 0.0 && lowHz < sampleRateHz / 2.0) result = highPass(result, sampleRateHz, lowHz)
        if (highHz > 0.0 && highHz < sampleRateHz / 2.0) result = lowPass(result, sampleRateHz, highHz)
        return result
    }

    private fun lowPass(samples: List<Double>, fs: Double, cutoff: Double): List<Double> {
        val omega = 2.0 * PI * cutoff / fs; val cosW = cos(omega); val sinW = sin(omega); val alpha = sinW / (2.0 / sqrt(2.0)); val a0 = 1.0 + alpha
        return biquad(samples, (1.0 - cosW) / 2.0 / a0, (1.0 - cosW) / a0, (1.0 - cosW) / 2.0 / a0, -2.0 * cosW / a0, (1.0 - alpha) / a0)
    }

    private fun highPass(samples: List<Double>, fs: Double, cutoff: Double): List<Double> {
        val omega = 2.0 * PI * cutoff / fs; val cosW = cos(omega); val sinW = sin(omega); val alpha = sinW / (2.0 / sqrt(2.0)); val a0 = 1.0 + alpha
        return biquad(samples, (1.0 + cosW) / 2.0 / a0, -(1.0 + cosW) / a0, (1.0 + cosW) / 2.0 / a0, -2.0 * cosW / a0, (1.0 - alpha) / a0)
    }

    private fun biquad(samples: List<Double>, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): List<Double> {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return samples.map { raw ->
            val x0 = if (raw.isFinite()) raw else 0.0
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0; y0
        }
    }

    private fun dominantFrequency(samples: List<Double>, sampleRateHz: Double): Double? = welchPsd(samples, sampleRateHz, min(samples.size, 1024)).filter { it.frequencyHz in 0.5..min(100.0, sampleRateHz / 2.0) }.maxByOrNull { it.powerDensity }?.frequencyHz

    private fun segmentStarts(total: Int, segment: Int, hop: Int): List<Int> {
        if (total <= segment) return listOf(0)
        val starts = mutableListOf<Int>(); var start = 0
        while (start + segment <= total) { starts += start; start += hop }
        if (starts.lastOrNull() != total - segment) starts += total - segment
        return starts.distinct()
    }
}
