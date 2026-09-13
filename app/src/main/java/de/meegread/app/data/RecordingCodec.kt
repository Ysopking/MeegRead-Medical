package de.meegread.app.data

import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegEvent
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import de.meegread.app.model.SensorPosition
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object RecordingCodec {
    private const val MAGIC = 0x4D475231
    private const val VERSION = 1

    fun write(file: File, recording: MeegRecording) {
        file.parentFile?.mkdirs()
        DataOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(file)))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(recording.name)
            out.writeInt(recording.modality.ordinal)
            out.writeDouble(recording.sampleRateHz)
            out.writeInt(recording.channels.size)
            recording.channels.forEach { (name, samples) ->
                out.writeUTF(name)
                val info = recording.infoFor(name)
                out.writeInt(info.type.ordinal)
                out.writeUTF(info.unit)
                out.writeDouble(info.samplingRateHz ?: recording.sampleRateHz)
                out.writeDouble(info.calibration)
                out.writeBoolean(info.position != null)
                info.position?.let { out.writeDouble(it.x); out.writeDouble(it.y); out.writeDouble(it.z) }
                out.writeInt(samples.size)
                samples.forEach(out::writeDouble)
            }
            out.writeInt(recording.events.size)
            recording.events.forEach { event ->
                out.writeInt(event.sample)
                out.writeDouble(event.onsetSeconds)
                out.writeDouble(event.durationSeconds)
                out.writeBoolean(event.code != null)
                event.code?.let(out::writeInt)
                out.writeUTF(event.label)
            }
            out.writeInt(recording.metadata.size)
            recording.metadata.forEach { (key, value) -> out.writeUTF(key); out.writeUTF(value) }
        }
    }

    fun read(file: File): MeegRecording {
        DataInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(file)))).use { input ->
            require(input.readInt() == MAGIC) { "Ungültige MeegRead-Aufzeichnung." }
            require(input.readInt() <= VERSION) { "Die Aufzeichnung stammt aus einer neueren MeegRead-Version." }
            val name = input.readUTF()
            val modality = Modality.entries.getOrElse(input.readInt()) { Modality.UNKNOWN }
            val sampleRate = input.readDouble()
            val channelCount = input.readInt()
            val channels = linkedMapOf<String, List<Double>>()
            val infos = linkedMapOf<String, ChannelInfo>()
            repeat(channelCount) {
                val channelName = input.readUTF()
                val type = ChannelType.entries.getOrElse(input.readInt()) { ChannelType.OTHER }
                val unit = input.readUTF()
                val channelRate = input.readDouble()
                val calibration = input.readDouble()
                val position = if (input.readBoolean()) SensorPosition(input.readDouble(), input.readDouble(), input.readDouble()) else null
                val count = input.readInt()
                val samples = List(count) { input.readDouble() }
                channels[channelName] = samples
                infos[channelName] = ChannelInfo(channelName, type, unit, channelRate, calibration, position)
            }
            val eventCount = input.readInt()
            val events = List(eventCount) {
                val sample = input.readInt()
                val onset = input.readDouble()
                val duration = input.readDouble()
                val code = if (input.readBoolean()) input.readInt() else null
                val label = input.readUTF()
                MeegEvent(sample, onset, duration, code, label)
            }
            val metadataCount = input.readInt()
            val metadata = buildMap { repeat(metadataCount) { put(input.readUTF(), input.readUTF()) } }
            val sampleCount = channels.values.maxOfOrNull { it.size } ?: 0
            return MeegRecording(name, modality, sampleRate, channels, sampleCount, if (sampleRate > 0) sampleCount / sampleRate else 0.0, infos, events, metadata)
        }
    }
}
