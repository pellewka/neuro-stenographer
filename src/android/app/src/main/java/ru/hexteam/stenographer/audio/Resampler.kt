package ru.hexteam.stenographer.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Band-limited (windowed-sinc) resampler; avoids the aliasing of linear interpolation. */
object Resampler {

    private const val HALF_TAPS = 10

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "Sample rates must be positive" }
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()

        val ratio = toRate.toDouble() / fromRate
        val cutoff = min(1.0, ratio) * 0.95
        val halfWidth = HALF_TAPS / cutoff
        val outLength = floor(input.size * ratio).toInt()
        val output = FloatArray(outLength)

        for (i in 0 until outLength) {
            val center = i / ratio
            val left = maxOf(0, ceil(center - halfWidth).toInt())
            val right = minOf(input.size - 1, floor(center + halfWidth).toInt())
            var acc = 0.0
            var norm = 0.0
            for (j in left..right) {
                val x = j - center
                val window = 0.5 + 0.5 * cos(PI * x / halfWidth)
                val weight = cutoff * sinc(cutoff * x) * window
                acc += input[j] * weight
                norm += weight
            }
            output[i] = if (norm > 1e-9) (acc / norm).toFloat() else 0f
        }
        return output
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
}
