package ru.hexteam.stenographer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tells songs from speech. Music has strong bass (kick, bass line) in 40-120 Hz and
 * almost no pauses; speech - even noisy, even with mains hum - has neither.
 * Measured: song 0.60 bass share, noisy speech 0.035, clean speech ~0.
 */
object MusicDetector {

    private const val FRAME = 1024
    private const val BASS_LOW_HZ = 40.0
    private const val BASS_HIGH_HZ = 120.0
    private const val BASS_SHARE_THRESHOLD = 0.3
    private const val CONTINUITY_THRESHOLD = 0.95
    private const val SILENCE_RATIO = 1e-3

    data class Features(val bassShare: Double, val continuity: Double) {
        val isMusic: Boolean get() = bassShare > BASS_SHARE_THRESHOLD && continuity > CONTINUITY_THRESHOLD
    }

    fun isMusic(mono: FloatArray, sampleRate: Int): Boolean = features(mono, sampleRate).isMusic

    fun features(mono: FloatArray, sampleRate: Int): Features {
        val frames = mono.size / FRAME
        if (frames < 8) return Features(0.0, 0.0)
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        val binHz = sampleRate.toDouble() / FRAME
        val lowBin = (BASS_LOW_HZ / binHz).toInt().coerceAtLeast(1)
        val highBin = (BASS_HIGH_HZ / binHz).toInt()

        val totals = DoubleArray(frames)
        val bass = DoubleArray(frames)
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        for (f in 0 until frames) {
            for (i in 0 until FRAME) {
                re[i] = mono[f * FRAME + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            var total = 1e-12
            var low = 0.0
            for (k in 1..FRAME / 2) {
                val power = re[k] * re[k] + im[k] * im[k]
                total += power
                if (k in lowBin until highBin) low += power
            }
            totals[f] = total
            bass[f] = low / total
        }

        val sorted = totals.sorted()
        val median = sorted[frames / 2]
        val loudBass = (0 until frames).filter { totals[it] > median }.map { bass[it] }.sorted()
        val bassShare = if (loudBass.isEmpty()) 0.0 else loudBass[loudBass.size / 2]
        val p90 = sorted[(frames * 0.9).toInt()]
        val continuity = totals.count { it > p90 * SILENCE_RATIO }.toDouble() / frames
        return Features(bassShare, continuity)
    }

    /** In-place iterative radix-2 FFT; size must be a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(angle * k)
                    val wi = sin(angle * k)
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
    }
}
