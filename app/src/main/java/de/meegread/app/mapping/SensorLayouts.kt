package de.meegread.app.mapping

import de.meegread.app.model.MeegRecording
import de.meegread.app.model.SensorPosition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SensorLayouts {
    private fun p(x: Double, y: Double, z: Double = 0.35): SensorPosition = SensorPosition(x, y, z)
    private val eeg1020 = mapOf(
        "FP1" to p(-0.32,0.90,0.18), "FPZ" to p(0.0,0.96,0.15), "FP2" to p(0.32,0.90,0.18),
        "AF7" to p(-0.63,0.74,0.20), "AF3" to p(-0.30,0.70), "AFZ" to p(0.0,0.75), "AF4" to p(0.30,0.70), "AF8" to p(0.63,0.74,0.20),
        "F7" to p(-0.82,0.46,0.15), "F3" to p(-0.34,0.48), "FZ" to p(0.0,0.52), "F4" to p(0.34,0.48), "F8" to p(0.82,0.46,0.15),
        "FC3" to p(-0.36,0.25), "FCZ" to p(0.0,0.27), "FC4" to p(0.36,0.25),
        "T7" to p(-0.98,0.0,0.08), "C3" to p(-0.38,0.0), "CZ" to p(0.0,0.0,0.70), "C4" to p(0.38,0.0), "T8" to p(0.98,0.0,0.08),
        "TP7" to p(-0.92,-0.24,0.12), "CP3" to p(-0.36,-0.26), "CPZ" to p(0.0,-0.29), "CP4" to p(0.36,-0.26), "TP8" to p(0.92,-0.24,0.12),
        "P7" to p(-0.82,-0.48,0.15), "P3" to p(-0.34,-0.50), "PZ" to p(0.0,-0.54), "P4" to p(0.34,-0.50), "P8" to p(0.82,-0.48,0.15),
        "PO7" to p(-0.63,-0.72,0.18), "PO3" to p(-0.30,-0.72), "POZ" to p(0.0,-0.77), "PO4" to p(0.30,-0.72), "PO8" to p(0.63,-0.72,0.18),
        "O1" to p(-0.32,-0.91,0.15), "OZ" to p(0.0,-0.97,0.12), "O2" to p(0.32,-0.91,0.15),
        "TP9" to p(-0.94,-0.34,0.05), "TP10" to p(0.94,-0.34,0.05), "A1" to p(-1.0,-0.12,0.0), "A2" to p(1.0,-0.12,0.0), "M1" to p(-1.0,-0.12,0.0), "M2" to p(1.0,-0.12,0.0)
    )

    fun positions(recording: MeegRecording): Map<String, SensorPosition> {
        val actual = recording.channels.keys.mapNotNull { name -> recording.infoFor(name).position?.let { name to it } }.toMap()
        val maxRadius = actual.values.maxOfOrNull { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }?.takeIf { it > 0.0 } ?: 1.0
        val names = recording.channels.keys.toList()
        return names.mapIndexed { index, name ->
            val actualPosition = actual[name]
            val normalized = if (actualPosition != null) SensorPosition(actualPosition.x / maxRadius, actualPosition.y / maxRadius, actualPosition.z / maxRadius)
            else eeg1020[layoutName(name)] ?: run { val angle = 2.0 * PI * index / names.size.coerceAtLeast(1); SensorPosition(0.78 * cos(angle), 0.78 * sin(angle), 0.15) }
            name to normalized
        }.toMap()
    }
    private fun layoutName(name: String): String {
        val upper = name.trim().uppercase()
        val stripped = if (upper.substringBefore(' ') in setOf("EEG","EOG","ECG","EMG","SEEG","ECOG","DBS")) upper.substringAfter(' ') else upper
        return stripped.removeSuffix("-REF").removeSuffix("-LE").removeSuffix("-RE")
    }
}
