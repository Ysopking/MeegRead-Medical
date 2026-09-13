package de.meegread.app.data

import android.util.JsonReader
import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegEvent
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import de.meegread.app.model.SensorPosition
import java.io.File
import java.io.InputStreamReader

object JsonRecordingParser {
    private data class ParsedChannel(val name: String, val type: ChannelType, val unit: String, val position: SensorPosition?, val samples: List<Double>)

    fun parse(file: File): MeegRecording {
        var name = file.name
        var modality = Modality.UNKNOWN
        var sampleRate = 256.0
        val metadata = linkedMapOf<String, String>()
        val events = mutableListOf<MeegEvent>()
        val channels = mutableListOf<ParsedChannel>()
        JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "schema" -> json.nextString()
                    "name" -> name = json.nextString()
                    "modality" -> modality = runCatching { Modality.valueOf(json.nextString()) }.getOrDefault(Modality.UNKNOWN)
                    "sampleRateHz" -> sampleRate = json.nextDouble()
                    "sampleCount" -> json.nextLong()
                    "metadata" -> { json.beginObject(); while (json.hasNext()) metadata[json.nextName()] = json.nextString(); json.endObject() }
                    "events" -> { json.beginArray(); while (json.hasNext()) events += readEvent(json); json.endArray() }
                    "channels" -> { json.beginArray(); while (json.hasNext()) channels += readChannel(json); json.endArray() }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        require(channels.isNotEmpty()) { "JSON enthält keine Kanäle." }
        val channelMap = channels.associate { it.name to it.samples }
        val info = channels.associate { parsed -> parsed.name to ChannelInfo(parsed.name, parsed.type, parsed.unit, sampleRate, position = parsed.position) }
        val sampleCount = channelMap.values.maxOf { it.size }
        return MeegRecording(name, modality, sampleRate, channelMap, sampleCount, sampleCount / sampleRate, info, events, metadata + ("format" to "JSON"))
    }

    private fun readEvent(json: JsonReader): MeegEvent {
        var sample = 0; var onset = 0.0; var duration = 0.0; var code: Int? = null; var label = "Event"
        json.beginObject()
        while (json.hasNext()) when (json.nextName()) {
            "sample" -> sample = json.nextInt(); "onsetSeconds" -> onset = json.nextDouble(); "durationSeconds" -> duration = json.nextDouble(); "code" -> code = json.nextInt(); "label" -> label = json.nextString(); else -> json.skipValue()
        }
        json.endObject(); return MeegEvent(sample, onset, duration, code, label)
    }

    private fun readChannel(json: JsonReader): ParsedChannel {
        var name = "CH"; var type = ChannelType.OTHER; var unit = "a.u."; var position: SensorPosition? = null
        val samples = mutableListOf<Double>()
        json.beginObject()
        while (json.hasNext()) when (json.nextName()) {
            "name" -> name = json.nextString()
            "type" -> type = runCatching { ChannelType.valueOf(json.nextString()) }.getOrDefault(ChannelType.OTHER)
            "unit" -> unit = json.nextString()
            "position" -> { json.beginArray(); val xyz = mutableListOf<Double>(); while (json.hasNext()) xyz += json.nextDouble(); json.endArray(); if (xyz.size >= 3) position = SensorPosition(xyz[0], xyz[1], xyz[2]) }
            "samples" -> { json.beginArray(); while (json.hasNext()) samples += json.nextDouble(); json.endArray() }
            else -> json.skipValue()
        }
        json.endObject(); return ParsedChannel(name, type, unit, position, samples)
    }
}
