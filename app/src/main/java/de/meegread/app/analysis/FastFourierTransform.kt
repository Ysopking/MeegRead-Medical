package de.meegread.app.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class ComplexSpectrum(val real: DoubleArray, val imaginary: DoubleArray)

internal object FastFourierTransform {
    fun transformReal(input: DoubleArray): ComplexSpectrum {
        require(input.isNotEmpty() && input.size and (input.size - 1) == 0) { "FFT-Länge muss eine Zweierpotenz sein." }
        val n = input.size
        val real = input.copyOf()
        val imaginary = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                val half = length / 2
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val vr = real[odd] * wr - imaginary[odd] * wi
                    val vi = real[odd] * wi + imaginary[odd] * wr
                    val ur = real[even]
                    val ui = imaginary[even]
                    real[even] = ur + vr
                    imaginary[even] = ui + vi
                    real[odd] = ur - vr
                    imaginary[odd] = ui - vi
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
        return ComplexSpectrum(real, imaginary)
    }

    fun floorPowerOfTwo(value: Int): Int {
        if (value < 1) return 0
        var result = 1
        while (result <= value / 2) result = result shl 1
        return result
    }
}
