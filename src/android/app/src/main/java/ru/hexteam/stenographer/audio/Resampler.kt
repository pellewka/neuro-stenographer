package ru.hexteam.stenographer.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Band-limited (windowed-sinc) resampler; avoids the aliasing of linear interpolation. */
object Resampler {

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "Sample rates must be positive" }
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val out = FloatArrayBuilder(maxOf(16, (input.size.toLong() * toRate / fromRate).toInt() + 1))
        StreamingResampler(fromRate, toRate).apply {
            push(input) { out.addAll(it) }
            finish { out.addAll(it) }
        }
        return out.toArray()
    }
}

/**
 * The same windowed-sinc resampler fed chunk by chunk, so hours of audio never
 * have to be in memory at once. Output is identical to resampling the whole signal.
 */
class StreamingResampler(private val fromRate: Int, private val toRate: Int) {

    private val ratio = toRate.toDouble() / fromRate
    private val cutoff = min(1.0, ratio) * 0.95
    private val halfWidth = HALF_TAPS / cutoff

    private var buffer = FloatArray(1 shl 16)
    private var bufferLength = 0
    /** Absolute index (in input samples) of buffer[0]. */
    private var bufferStart = 0L
    private var totalIn = 0L
    private var nextOut = 0L

    fun push(chunk: FloatArray, emit: (FloatArray) -> Unit) {
        if (fromRate == toRate) {
            emit(chunk.copyOf())
            return
        }
        append(chunk)
        totalIn += chunk.size
        emitReady(final = false, emit)
    }

    fun finish(emit: (FloatArray) -> Unit) {
        if (fromRate == toRate) return
        emitReady(final = true, emit)
    }

    private fun emitReady(final: Boolean, emit: (FloatArray) -> Unit) {
        val limit = if (final) floor(totalIn * ratio).toLong() else Long.MAX_VALUE
        val out = FloatArrayBuilder(1024)
        while (nextOut < limit) {
            val center = nextOut / ratio
            val right = floor(center + halfWidth).toLong()
            if (!final && right >= totalIn) break
            out.add(sample(center, minOf(right, totalIn - 1)))
            nextOut++
        }
        if (out.size > 0) emit(out.toArray())
        // Drop input no future output can reach.
        val keepFrom = maxOf(0L, ceil(nextOut / ratio - halfWidth).toLong())
        val drop = (keepFrom - bufferStart).toInt().coerceIn(0, bufferLength)
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, bufferLength - drop)
            bufferLength -= drop
            bufferStart += drop
        }
    }

    private fun sample(center: Double, right: Long): Float {
        val left = maxOf(0L, ceil(center - halfWidth).toLong())
        var acc = 0.0
        var norm = 0.0
        for (j in left..right) {
            val x = j - center
            val window = 0.5 + 0.5 * cos(PI * x / halfWidth)
            val weight = cutoff * sinc(cutoff * x) * window
            acc += buffer[(j - bufferStart).toInt()] * weight
            norm += weight
        }
        return if (norm > 1e-9) (acc / norm).toFloat() else 0f
    }

    private fun append(chunk: FloatArray) {
        if (bufferLength + chunk.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, bufferLength + chunk.size))
        }
        System.arraycopy(chunk, 0, buffer, bufferLength, chunk.size)
        bufferLength += chunk.size
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private companion object {
        const val HALF_TAPS = 10
    }
}
