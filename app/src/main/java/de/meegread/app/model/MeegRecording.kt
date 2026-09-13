package de.meegread.app.model

import java.util.UUID

enum class Modality { EEG, MEG, MIXED, UNKNOWN }
enum class ChannelType { EEG, MEG_MAG, MEG_GRAD, STIM, EOG, ECG, EMG, RESP, MISC, OTHER }

data class SensorPosition(val x: Double, val y: Double, val z: Double = 0.0)

data class ChannelInfo(
    val name: String,
    val type: ChannelType = ChannelType.OTHER,
    val unit: String = "a.u.",
    val samplingRateHz: Double? = null,
    val calibration: Double = 1.0,
    val position: SensorPosition? = null
)

data class MeegEvent(
    val sample: Int,
    val onsetSeconds: Double,
    val durationSeconds: Double = 0.0,
    val code: Int? = null,
    val label: String = "Event"
)

data class MeegRecording(
    val name: String,
    val modality: Modality,
    val sampleRateHz: Double,
    val channels: Map<String, List<Double>>,
    val sampleCount: Int,
    val durationSeconds: Double,
    val channelInfo: Map<String, ChannelInfo> = emptyMap(),
    val events: List<MeegEvent> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    val channelCount: Int get() = channels.size
    fun infoFor(channel: String): ChannelInfo = channelInfo[channel] ?: ChannelInfo(name = channel, type = inferChannelType(channel))

    companion object {
        fun inferChannelType(name: String): ChannelType {
            val upper = name.uppercase()
            return when {
                upper.startsWith("MEG") && upper.lastOrNull() == '1' -> ChannelType.MEG_MAG
                upper.startsWith("MEG") -> ChannelType.MEG_GRAD
                upper.startsWith("EOG") -> ChannelType.EOG
                upper.startsWith("ECG") || upper.startsWith("EKG") -> ChannelType.ECG
                upper.startsWith("EMG") -> ChannelType.EMG
                upper.startsWith("RESP") -> ChannelType.RESP
                upper.startsWith("STI") || upper.contains("TRIG") || upper.contains("MARK") -> ChannelType.STIM
                upper.matches(Regex("(FP|AF|F|FC|C|CP|P|PO|O|T|TP)[A-Z0-9-]*")) -> ChannelType.EEG
                else -> ChannelType.OTHER
            }
        }
    }
}

data class ChannelMetrics(val channel: String, val mean: Double, val rms: Double, val standardDeviation: Double, val peakToPeak: Double, val dominantFrequencyHz: Double?)
data class SpectralPoint(val frequencyHz: Double, val powerDensity: Double)
enum class FrequencyBand(val minHz: Double, val maxHz: Double) { DELTA(0.5,4.0), THETA(4.0,8.0), ALPHA(8.0,13.0), BETA(13.0,30.0), GAMMA(30.0,80.0) }
data class BandPower(val band: FrequencyBand, val absolutePower: Double, val relativePower: Double)
data class CoherenceResult(val channelA: String, val channelB: String, val frequenciesHz: List<Double>, val coherence: List<Double>)
data class Epoch(val event: MeegEvent, val startSeconds: Double, val endSeconds: Double, val channels: Map<String, List<Double>>)
data class SubjectProfile(val id: String = UUID.randomUUID().toString(), val pseudonym: String, val notes: String = "", val createdAtEpochMs: Long = System.currentTimeMillis())
data class SessionSummary(val id: String, val subjectId: String, val recordingName: String, val modality: Modality, val sampleRateHz: Double, val channelCount: Int, val sampleCount: Int, val durationSeconds: Double, val createdAtEpochMs: Long)
