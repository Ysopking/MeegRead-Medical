package de.meegread.app.data

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import kotlin.math.max

object SignalFileParser {
    private val timeNames = setOf("time", "timestamp", "seconds", "sec", "t")

    fun parse(fileName: String, text: String, fallbackSampleRateHz: Double = 256.0): MeegRecording {
        val rawLines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        require(rawLines.size >= 2) { "Die Datei enthält zu wenige Datenzeilen." }
        val delimiter = detectDelimiter(rawLines.first())
        val headers = split(rawLines.first(), delimiter).map { it.trim().trim('"') }
        require(headers.size >= 2) { "Es wurden keine Messkanäle erkannt." }
        val timeIndex = headers.indexOfFirst { it.trim().lowercase() in timeNames }
        val channelIndices = headers.indices.filter { it != timeIndex }
        val values = channelIndices.associateWith { mutableListOf<Double>() }.toMutableMap()
        val times = mutableListOf<Double>()
        rawLines.drop(1).forEach { line ->
            val cells = split(line, delimiter)
            if (cells.size < headers.size) return@forEach
            if (timeIndex >= 0) parseNumber(cells[timeIndex], delimiter)?.let(times::add)
            channelIndices.forEach { index -> parseNumber(cells[index], delimiter)?.let { values.getValue(index).add(it) } }
        }
        val channels = channelIndices.associate { index -> headers[index] to values.getValue(index).toList() }.filterValues { it.size >= 2 }
        require(channels.isNotEmpty()) { "Keine numerischen EEG/MEG-Kanäle gefunden." }
        val sampleCount = channels.values.maxOf { it.size }
        val sampleRate = inferSampleRate(times) ?: fallbackSampleRateHz
        val duration = if (sampleRate > 0.0) max(0, sampleCount - 1) / sampleRate else 0.0
        return MeegRecording(fileName, inferModality(fileName, channels.keys), sampleRate, channels, sampleCount, duration)
    }

    private fun detectDelimiter(header: String): Char = listOf('\t', ';', ',').maxBy { d -> header.count { it == d } }
    private fun split(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false
        line.forEach { c -> when { c == '"' -> quoted = !quoted; c == delimiter && !quoted -> { result += cell.toString(); cell.clear() }; else -> cell.append(c) } }
        result += cell.toString(); return result
    }
    private fun parseNumber(value: String, delimiter: Char): Double? = value.trim().trim('"').let { if (delimiter != ',') it.replace(',', '.') else it }.toDoubleOrNull()
    private fun inferSampleRate(times: List<Double>): Double? {
        if (times.size < 3) return null
        val deltas = times.zipWithNext { a, b -> b - a }.filter { it > 0.0 && it.isFinite() }.sorted()
        if (deltas.isEmpty()) return null
        val median = deltas[deltas.size / 2]
        val secondsPerSample = if (median > 1.0) median / 1000.0 else median
        return (1.0 / secondsPerSample).takeIf { it in 0.1..100_000.0 }
    }
    private fun inferModality(fileName: String, channelNames: Set<String>): Modality {
        val lowerName = fileName.lowercase()
        if ("meg" in lowerName || lowerName.endsWith(".fif")) return Modality.MEG
        if ("eeg" in lowerName || lowerName.endsWith(".edf") || lowerName.endsWith(".bdf")) return Modality.EEG
        val upper = channelNames.map { it.uppercase() }
        if (upper.any { it.startsWith("MEG") || it.matches(Regex("M[LRZ][A-Z0-9]+")) }) return Modality.MEG
        val eegPrefixes = listOf("FP", "AF", "F", "FC", "C", "CP", "P", "PO", "O", "T", "TP")
        if (upper.any { name -> eegPrefixes.any { prefix -> name.startsWith(prefix) } }) return Modality.EEG
        return Modality.UNKNOWN
    }
}
