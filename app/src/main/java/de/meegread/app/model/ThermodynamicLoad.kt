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
    val omegaRatio: Double,
    val flowGate: Double = 0.0,
    val loadDriveRate: Double = 0.0,
    val flowReliefRate: Double = 0.0,
    val recoveryRate: Double = 0.0,
    val netLoadRate: Double = 0.0
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
