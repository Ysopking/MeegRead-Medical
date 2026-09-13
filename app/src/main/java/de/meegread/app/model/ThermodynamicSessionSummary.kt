package de.meegread.app.model

data class ThermodynamicSessionSummary(
    val sessionId: String,
    val subjectId: String,
    val createdAtEpochMs: Long,
    val algorithmVersion: String,
    val omegaCrit: Double,
    val finalRawLoad: Double,
    val finalBoundedLoad: Double,
    val maxRawLoad: Double,
    val meanFaa: Double,
    val omegaBreach: Boolean,
    val firstBreachTimeSeconds: Double?
)
