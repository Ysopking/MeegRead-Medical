package de.meegread.app.model

data class ThermodynamicLoadPoint(
    val timeSeconds: Double,
    val gradE: Double,
    val faa: Double,
    val hSigma: Double,
    val eFlow: Double,
    val pressureProxy: Double,
    val frictionRate: Double,
    val wRaw: Double,
    val wBounded: Double,
    val omegaRatio: Double
)

data class ThermodynamicLoadAnalysis(
    val points: List<ThermodynamicLoadPoint>,
    val omegaCrit: Double,
    val omegaBreach: Boolean,
    val firstBreachTimeSeconds: Double?,
    val finalRawLoad: Double,
    val finalBoundedLoad: Double,
    val maxRawLoad: Double,
    val meanFaa: Double,
    val sourceChannels: Map<String, String>
)
