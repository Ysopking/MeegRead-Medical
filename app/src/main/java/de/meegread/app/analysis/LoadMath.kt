package de.meegread.app.analysis

import kotlin.math.tanh

object ThermodynamicLoadMath {
    const val RESEARCH_OMEGA_MODEL_PARAMETER = 5800.0
    fun bounded(raw: Double): Double = RESEARCH_OMEGA_MODEL_PARAMETER * tanh(raw.coerceAtLeast(0.0) / RESEARCH_OMEGA_MODEL_PARAMETER)
}
