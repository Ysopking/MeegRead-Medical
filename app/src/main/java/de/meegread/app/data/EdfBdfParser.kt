package de.meegread.app.data

import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegEvent
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import kotlin.math.max

object EdfBdfParser {
    private data class Header(val label: String, val unit: String, val pMin: Double, val pMax: Double, val dMin: Double, val dMax: Double, val samplesPerRecord: Int)

    fun parse(file: File): MeegRecording {
        val bdf = file.extension.equals("bdf", true)
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            val version = readAscii(input, 8).trim()
            val subjectHeaderPresent = readAscii(input, 80).isNotBlank()
            val recordingHeaderPresent = readAscii(input, 80).isNotBlank()
            val startDate = readAscii(input, 8).trim()
            val startTime = readAscii(input, 8).trim()
            val headerBytes = readAscii(input, 8).trim().toIntOrNull() ?: 256
            readAscii(input, 44)
            val declaredRecords = readAscii(input, 8).trim().toIntOrNull() ?: -1
            val recordDuration = readAscii(input, 8).trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
            val signalCount = readAscii(input, 4).trim().toIntOrNull() ?: error("Ungültige EDF/BDF-Kanalzahl")
            require(signalCount in 1..4096)
            val labels = readFields(input, signalCount, 16)
            readFields(input, signalCount, 80)
            val units = readFields(input, signalCount, 8)
            val pMin = readFields(input, signalCount, 8).map { it.trim().toDoubleOrNull() ?: -1.0 }
            val pMax = readFields(input, signalCount, 8).map { it.trim().toDoubleOrNull() ?: 1.0 }
            val dMin = readFields(input, signalCount, 8).map { it.trim().toDoubleOrNull() ?: if (bdf) -8388608.0 else -32768.0 }
            val dMax = readFields(input, signalCount, 8).map { it.trim().toDoubleOrNull() ?: if (bdf) 8388607.0 else 32767.0 }
            readFields(input, signalCount, 80)
            val spr = readFields(input, signalCount, 8).map { it.trim().toIntOrNull() ?: 0 }
            readFields(input, signalCount, 32)
            val consumed = 256 + signalCount * 256
            if (headerBytes > consumed) skipFully(input, (headerBytes - consumed).toLong())
            val headers = (0 until signalCount).map { i -> Header(labels[i].trim().ifBlank { "CH${i + 1}" }, units[i].trim().ifBlank { "a.u." }, pMin[i], pMax[i], dMin[i], dMax[i], spr[i]) }
            val annotations = headers.indices.filter { headers[it].label.contains("Annotations", true) }.toSet()
            val raw = headers.indices.associateWith { mutableListOf<Double>() }.toMutableMap()
            val annotationBytes = ByteArrayOutputStream()
            var recordsRead = 0
            recordLoop@ while (declaredRecords < 0 || recordsRead < declaredRecords) {
                for (i in headers.indices) {
                    val h = headers[i]
                    repeat(h.samplesPerRecord) {
                        val bytes = try { if (bdf) readExactly(input, 3) else readExactly(input, 2) } catch (_: EOFException) { break@recordLoop }
                        if (i in annotations) annotationBytes.write(bytes) else {
                            val digital = if (bdf) signed24(bytes) else signed16(bytes)
                            raw.getValue(i).add(scale(digital.toDouble(), h))
                        }
                    }
                }
                recordsRead++
            }
            val dataIndices = headers.indices.filter { it !in annotations && raw.getValue(it).isNotEmpty() }
            require(dataIndices.isNotEmpty()) { "Keine EDF/BDF-Messkanäle gefunden." }
            val rates = dataIndices.associateWith { headers[it].samplesPerRecord / recordDuration }
            val targetRate = rates.values.maxOrNull()?.takeIf { it > 0.0 } ?: 256.0
            val targetSamples = max(1, (recordsRead * recordDuration * targetRate).toInt())
            val channels = linkedMapOf<String, List<Double>>(); val infos = linkedMapOf<String, ChannelInfo>()
            dataIndices.forEach { i ->
                val h = headers[i]; val sourceRate = rates.getValue(i); val values = raw.getValue(i)
                val normalized = if (sourceRate > 0 && kotlin.math.abs(sourceRate - targetRate) > 1e-9) resample(values, sourceRate, targetRate, targetSamples) else values.take(targetSamples)
                channels[h.label] = normalized
                infos[h.label] = ChannelInfo(h.label, inferType(h.label, h.unit), h.unit, sourceRate)
            }
            val sampleCount = channels.values.maxOf { it.size }
            return MeegRecording(
                file.name,
                inferModality(infos.values.map { it.type }),
                targetRate,
                channels,
                sampleCount,
                sampleCount / targetRate,
                infos,
                parseAnnotations(annotationBytes.toByteArray().toString(Charsets.UTF_8), targetRate),
                mapOf("format" to if (bdf) "BDF" else "EDF", "version" to version, "sourceSubjectHeaderPresent" to subjectHeaderPresent.toString(), "sourceRecordingHeaderPresent" to recordingHeaderPresent.toString(), "startDate" to startDate, "startTime" to startTime, "records" to recordsRead.toString())
            )
        }
    }

    private fun inferType(label: String, unit: String): ChannelType {
        val upper = label.trim().uppercase(); val prefix = upper.substringBefore(' '); val u = unit.lowercase()
        return when {
            prefix == "EOG" || upper.startsWith("EOG") -> ChannelType.EOG
            prefix == "ECG" || prefix == "EKG" || upper.startsWith("ECG") || upper.startsWith("EKG") -> ChannelType.ECG
            prefix == "EMG" || upper.startsWith("EMG") -> ChannelType.EMG
            prefix == "RESP" || upper.startsWith("RESP") -> ChannelType.RESP
            upper.contains("TRIG") || upper.startsWith("STI") || upper.contains("MARK") || upper == "STATUS" -> ChannelType.STIM
            upper.startsWith("MEG") && ("t/m" in u || "t / m" in u) -> ChannelType.MEG_GRAD
            upper.startsWith("MEG") || u == "t" || u.endsWith("ft") || u.endsWith("pt") -> ChannelType.MEG_MAG
            else -> MeegRecording.inferChannelType(upper.substringAfter(' ', upper)).let { if (it == ChannelType.OTHER) ChannelType.EEG else it }
        }
    }

    private fun inferModality(types: List<ChannelType>): Modality {
        val eeg = types.any { it == ChannelType.EEG }; val meg = types.any { it == ChannelType.MEG_MAG || it == ChannelType.MEG_GRAD }
        return when { eeg && meg -> Modality.MIXED; meg -> Modality.MEG; eeg -> Modality.EEG; else -> Modality.UNKNOWN }
    }

    private fun parseAnnotations(text: String, fs: Double): List<MeegEvent> = text.split('\u0000').flatMap { tal ->
        if (tal.isBlank()) return@flatMap emptyList()
        val first = tal.indexOf('\u0014'); if (first <= 0) return@flatMap emptyList()
        val timing = tal.substring(0, first).split('\u0015'); val onset = timing.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@flatMap emptyList(); val duration = timing.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0
        tal.substring(first + 1).split('\u0014').filter { it.isNotBlank() }.map { label -> MeegEvent((onset * fs).toInt().coerceAtLeast(0), onset, duration, label = label.trim()) }
    }.sortedBy { it.onsetSeconds }

    private fun scale(value: Double, h: Header): Double { val range = h.dMax - h.dMin; return if (range == 0.0) value else (value - h.dMin) * (h.pMax - h.pMin) / range + h.pMin }
    private fun resample(values: List<Double>, sourceRate: Double, targetRate: Double, count: Int): List<Double> = if (values.size <= 1) List(count) { values.firstOrNull() ?: 0.0 } else List(count) { index -> val p = index * sourceRate / targetRate; val l = p.toInt().coerceIn(0, values.lastIndex); val r = (l + 1).coerceAtMost(values.lastIndex); val f = p - l; values[l] * (1 - f) + values[r] * f }
    private fun signed16(bytes: ByteArray): Int { val raw = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8); return if (raw and 0x8000 != 0) raw - 0x10000 else raw }
    private fun signed24(bytes: ByteArray): Int { val raw = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8) or ((bytes[2].toInt() and 0xff) shl 16); return if (raw and 0x800000 != 0) raw or -0x1000000 else raw }
    private fun readFields(input: BufferedInputStream, count: Int, width: Int): List<String> = List(count) { readAscii(input, width) }
    private fun readAscii(input: BufferedInputStream, length: Int): String = String(readExactly(input, length), StandardCharsets.US_ASCII)
    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray { val buffer = ByteArray(length); var offset = 0; while (offset < length) { val read = input.read(buffer, offset, length - offset); if (read < 0) throw EOFException(); offset += read }; return buffer }
    private fun skipFully(input: BufferedInputStream, count: Long) { var remaining = count; while (remaining > 0) { val skipped = input.skip(remaining); if (skipped <= 0) { if (input.read() < 0) throw EOFException(); remaining-- } else remaining -= skipped } }
}
