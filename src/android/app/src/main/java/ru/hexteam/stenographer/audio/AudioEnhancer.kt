package ru.hexteam.stenographer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Cheap signal conditioning that never hurts recognition: DC/hum removal, level, noise estimate. */
object AudioEnhancer {

    private const val HIGHPASS_HZ = 80.0
    private const val FRAME_MS = 20
    private const val TARGET_PEAK = 0.9f
    private const val MAX_GAIN = 10f

    /** 2nd-order Butterworth high-pass: removes DC offset, rumble and 50/60 Hz mains hum. */
    fun highPass(samples: FloatArray, sampleRate: Int, cutoffHz: Double = HIGHPASS_HZ): FloatArray {
        if (samples.isEmpty()) return samples
        val w0 = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / sqrt(2.0) // sin(w0) / (2Q), Q = 1/√2 (Butterworth)
        val cosW = cos(w0)
        val a0 = 1 + alpha
        val b0 = (1 + cosW) / 2 / a0
        val b1 = -(1 + cosW) / a0
        val b2 = b0
        val a1 = -2 * cosW / a0
        val a2 = (1 - alpha) / a0

        val out = FloatArray(samples.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0.toFloat()
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
        }
        return out
    }

    /** Brings quiet recordings up to a usable level without clipping loud ones. */
    fun normalizePeak(samples: FloatArray): FloatArray {
        val peak = samples.maxOfOrNull { abs(it) } ?: return samples
        if (peak < 1e-4f || peak >= TARGET_PEAK) return samples
        val gain = minOf(TARGET_PEAK / peak, MAX_GAIN)
        return FloatArray(samples.size) { samples[it] * gain }
    }

    /**
     * Rough SNR in dB: loud frames (speech) vs quiet frames (background).
     * Clean speech with pauses gives 30+ dB, a noisy room or street 5-15 dB.
     */
    fun estimateSnrDb(samples: FloatArray, sampleRate: Int): Float {
        val frame = sampleRate * FRAME_MS / 1000
        val count = samples.size / frame
        if (count < 10) return Float.POSITIVE_INFINITY
        val energies = DoubleArray(count) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += samples[i] * samples[i]
            sum / frame + 1e-10
        }.sorted()
        val noise = energies[(count * 0.10).toInt()]
        val speech = energies[(count * 0.90).toInt()]
        return (10 * log10(speech / noise)).toFloat()
    }
}
