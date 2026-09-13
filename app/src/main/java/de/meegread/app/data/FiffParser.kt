package de.meegread.app.data

import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegEvent
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import de.meegread.app.model.SensorPosition
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile

object FiffParser {
    private const val FIFF_FILE_ID = 100
    private const val FIFF_BLOCK_START = 104
    private const val FIFF_BLOCK_END = 105
    private const val FIFF_NCHAN = 200
    private const val FIFF_SFREQ = 201
    private const val FIFF_CH_INFO = 203
    private const val FIFF_FIRST_SAMPLE = 208
    private const val FIFF_DATA_BUFFER = 300
    private const val FIFF_DATA_SKIP = 301
    private const val FIFFB_RAW_DATA = 102
    private const val FIFFB_CONTINUOUS_DATA = 112
    private const val FIFFB_IAS_RAW_DATA = 119
    private const val FIFFT_SHORT = 2
    private const val FIFFT_INT = 3
    private const val FIFFT_FLOAT = 4
    private const val FIFFT_DOUBLE = 5
    private const val FIFFT_DAU_PACK16 = 16

    private sealed interface RawEntry {
        data class Buffer(val dataPosition: Long, val size: Int, val type: Int) : RawEntry
        data class Skip(val buffers: Int) : RawEntry
    }

    private data class FiffChannel(val scanNo: Int, val kind: Int, val range: Double, val cal: Double, val unit: Int, val unitMul: Int, val name: String, val position: SensorPosition?)

    fun parse(file: File): MeegRecording {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 32L) { "FIFF-Datei ist zu klein." }
            var nchan = 0; var sfreq = 0.0; var firstSample = 0
            val channels = mutableListOf<FiffChannel>(); val allRawEntries = mutableListOf<RawEntry>(); val scopedRawEntries = mutableListOf<RawEntry>(); val blockStack = mutableListOf<Int>(); val visited = mutableSetOf<Long>()
            var position = 0L; var firstKind: Int? = null
            fun insideRawBlock(): Boolean = blockStack.any { it == FIFFB_RAW_DATA || it == FIFFB_CONTINUOUS_DATA || it == FIFFB_IAS_RAW_DATA }
            while (position + 16 <= raf.length() && visited.add(position)) {
                raf.seek(position)
                val kind = raf.readInt(); val type = raf.readInt(); val size = raf.readInt(); val next = raf.readInt()
                if (firstKind == null) firstKind = kind
                if (size < 0 || position + 16L + size > raf.length()) break
                val dataPosition = position + 16L
                when (kind) {
                    FIFF_BLOCK_START -> if (size >= 4) { raf.seek(dataPosition); blockStack += raf.readInt() }
                    FIFF_BLOCK_END -> if (size >= 4) { raf.seek(dataPosition); val ended = raf.readInt(); val index = blockStack.indexOfLast { it == ended }; if (index >= 0) while (blockStack.size > index) blockStack.removeAt(blockStack.lastIndex) }
                    FIFF_NCHAN -> if (size >= 4) { raf.seek(dataPosition); nchan = raf.readInt() }
                    FIFF_SFREQ -> if (size >= 4) { raf.seek(dataPosition); sfreq = if ((type and 0xffff) == FIFFT_DOUBLE) raf.readDouble() else raf.readFloat().toDouble() }
                    FIFF_CH_INFO -> if (size >= 96) { raf.seek(dataPosition); val payload = ByteArray(size); raf.readFully(payload); parseChannelInfo(payload)?.let(channels::add) }
                    FIFF_FIRST_SAMPLE -> if (size >= 4) { raf.seek(dataPosition); firstSample = raf.readInt() }
                    FIFF_DATA_BUFFER -> { val entry = RawEntry.Buffer(dataPosition, size, type and 0xffff); allRawEntries += entry; if (insideRawBlock()) scopedRawEntries += entry }
                    FIFF_DATA_SKIP -> if (size >= 4) { raf.seek(dataPosition); val entry = RawEntry.Skip(raf.readInt().coerceAtLeast(0)); allRawEntries += entry; if (insideRawBlock()) scopedRawEntries += entry }
                }
                position = when { next > 0 && next.toLong() > position && next.toLong() < raf.length() -> next.toLong(); next == -1 -> break; else -> dataPosition + size }
            }
            require(firstKind == FIFF_FILE_ID) { "Datei besitzt keinen gültigen FIFF-Dateikopf." }
            require(nchan > 0) { "FIFF_NCHAN fehlt oder ist ungültig." }
            require(sfreq > 0.0) { "FIFF_SFREQ fehlt oder ist ungültig." }
            val rawEntries = if (scopedRawEntries.any { it is RawEntry.Buffer }) scopedRawEntries else allRawEntries
            require(rawEntries.any { it is RawEntry.Buffer }) { "Keine FIFF-Rohdatenpuffer gefunden." }
            val orderedInfo = MutableList<FiffChannel?>(nchan) { null }
            channels.forEach { channel -> val index = (channel.scanNo - 1).coerceIn(0, nchan - 1); if (orderedInfo[index] == null) orderedInfo[index] = channel }
            val names = (0 until nchan).map { index -> orderedInfo[index]?.name?.ifBlank { null } ?: "CH${index + 1}" }
            val values = List(nchan) { mutableListOf<Double>() }
            var pendingSkipBuffers = 0; var seenData = false; var effectiveFirstSample = firstSample; var dataBufferCount = 0
            rawEntries.forEach { entry ->
                when (entry) {
                    is RawEntry.Skip -> pendingSkipBuffers += entry.buffers
                    is RawEntry.Buffer -> {
                        val bytesPerValue = when (entry.type) { FIFFT_SHORT, FIFFT_DAU_PACK16 -> 2; FIFFT_INT, FIFFT_FLOAT -> 4; FIFFT_DOUBLE -> 8; else -> error("Nicht unterstützter FIFF-Rohdatentyp ${entry.type}") }
                        val valueCount = entry.size / bytesPerValue
                        require(valueCount % nchan == 0) { "FIFF-Datenpuffer ist nicht durch die Kanalzahl teilbar." }
                        val samplesInBuffer = valueCount / nchan
                        if (pendingSkipBuffers > 0) {
                            if (!seenData) effectiveFirstSample += pendingSkipBuffers * samplesInBuffer else { val skippedSamples = pendingSkipBuffers * samplesInBuffer; repeat(nchan) { channelIndex -> repeat(skippedSamples) { values[channelIndex].add(0.0) } } }
                            pendingSkipBuffers = 0
                        }
                        raf.seek(entry.dataPosition)
                        repeat(samplesInBuffer) {
                            for (channelIndex in 0 until nchan) {
                                val raw = when (entry.type) { FIFFT_SHORT, FIFFT_DAU_PACK16 -> raf.readShort().toDouble(); FIFFT_INT -> raf.readInt().toDouble(); FIFFT_FLOAT -> raf.readFloat().toDouble(); FIFFT_DOUBLE -> raf.readDouble(); else -> 0.0 }
                                val info = orderedInfo[channelIndex]
                                values[channelIndex].add(raw * ((info?.range ?: 1.0) * (info?.cal ?: 1.0)))
                            }
                        }
                        seenData = true; dataBufferCount++
                    }
                }
            }
            val channelMap = linkedMapOf<String, List<Double>>(); val infoMap = linkedMapOf<String, ChannelInfo>()
            names.forEachIndexed { index, name ->
                channelMap[name] = values[index]
                val source = orderedInfo[index]
                infoMap[name] = ChannelInfo(name, channelType(source?.kind, source?.unit, name), unitName(source?.unit), sfreq, (source?.range ?: 1.0) * (source?.cal ?: 1.0), source?.position)
            }
            val types = infoMap.values.map { it.type }
            val modality = when { types.any { it == ChannelType.EEG } && types.any { it == ChannelType.MEG_MAG || it == ChannelType.MEG_GRAD } -> Modality.MIXED; types.any { it == ChannelType.MEG_MAG || it == ChannelType.MEG_GRAD } -> Modality.MEG; types.any { it == ChannelType.EEG } -> Modality.EEG; else -> Modality.UNKNOWN }
            val sampleCount = values.maxOfOrNull { it.size } ?: 0
            val events = extractStimEvents(channelMap, infoMap, sfreq)
            return MeegRecording(file.name, modality, sfreq, channelMap, sampleCount, if (sfreq > 0.0) sampleCount / sfreq else 0.0, infoMap, events, mapOf("format" to "FIFF", "firstSample" to effectiveFirstSample.toString(), "dataBuffers" to dataBufferCount.toString(), "rawBlockScoped" to scopedRawEntries.any { it is RawEntry.Buffer }.toString()))
        }
    }

    private fun parseChannelInfo(payload: ByteArray): FiffChannel? = runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val scanNo = input.readInt(); input.readInt(); val kind = input.readInt(); val range = input.readFloat().toDouble(); val cal = input.readFloat().toDouble(); input.readInt()
            val loc = DoubleArray(12) { input.readFloat().toDouble() }; val unit = input.readInt(); val unitMul = input.readInt(); val nameBytes = ByteArray(16); input.readFully(nameBytes)
            val zero = nameBytes.indexOf(0); val length = if (zero >= 0) zero else nameBytes.size; val name = String(nameBytes, 0, length, Charsets.US_ASCII).trim()
            val position = if (loc.take(3).all { it.isFinite() } && loc.take(3).any { it != 0.0 }) SensorPosition(loc[0], loc[1], loc[2]) else null
            FiffChannel(scanNo, kind, range, cal, unit, unitMul, name, position)
        }
    }.getOrNull()

    private fun channelType(kind: Int?, unit: Int?, name: String): ChannelType = when (kind) { 1 -> if (unit == 201) ChannelType.MEG_GRAD else ChannelType.MEG_MAG; 2 -> ChannelType.EEG; 3 -> ChannelType.STIM; 202 -> ChannelType.EOG; 302 -> ChannelType.EMG; 402 -> ChannelType.ECG; 502 -> ChannelType.MISC; 602 -> ChannelType.RESP; else -> MeegRecording.inferChannelType(name) }
    private fun unitName(unit: Int?): String = when (unit) { 107 -> "V"; 112 -> "T"; 201 -> "T/m"; 202 -> "A·m"; else -> "SI" }
    private fun extractStimEvents(channels: Map<String, List<Double>>, info: Map<String, ChannelInfo>, sfreq: Double): List<MeegEvent> {
        val stim = info.entries.firstOrNull { it.value.type == ChannelType.STIM }?.key ?: return emptyList(); val values = channels[stim] ?: return emptyList(); val events = mutableListOf<MeegEvent>(); var previous = 0
        values.forEachIndexed { index, value -> val current = value.toInt(); if (current != 0 && current != previous) events += MeegEvent(index, index / sfreq, code = current, label = "$stim:$current"); previous = current }
        return events
    }
}
