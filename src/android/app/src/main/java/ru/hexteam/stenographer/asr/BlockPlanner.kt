package ru.hexteam.stenographer.asr

/**
 * Splits hours of audio into processing blocks. Each cut is moved to the quietest
 * 100 ms within the last seconds before the nominal boundary, so words are not cut in half.
 */
object BlockPlanner {

    private const val SEARCH_SEC = 10
    private const val FRAME_MS = 100

    /**
     * @param readWindow reads [count] mono samples starting at a frame index.
     * @return block boundaries as [start, end) frame ranges covering [0, totalFrames).
     */
    fun plan(totalFrames: Long, sampleRate: Int, blockSec: Int, readWindow: (Long, Int) -> FloatArray): List<LongRange> {
        val block = blockSec.toLong() * sampleRate
        if (totalFrames <= block + block / 4) return listOf(0L until totalFrames)
        val cuts = mutableListOf(0L)
        var nominal = block
        while (nominal < totalFrames - block / 4) {
            val searchStart = (nominal - SEARCH_SEC.toLong() * sampleRate).coerceAtLeast(cuts.last() + 1)
            val window = readWindow(searchStart, (nominal - searchStart).toInt())
            cuts += searchStart + quietestOffset(window, sampleRate)
            nominal = cuts.last() + block
        }
        cuts += totalFrames
        return cuts.zipWithNext { a, b -> a until b }
    }

    /** Offset of the centre of the quietest frame in [samples]. */
    internal fun quietestOffset(samples: FloatArray, sampleRate: Int): Int {
        val frame = sampleRate * FRAME_MS / 1000
        if (samples.size < frame) return samples.size / 2
        var best = 0
        var bestEnergy = Double.MAX_VALUE
        var start = 0
        while (start + frame <= samples.size) {
            var e = 0.0
            for (i in start until start + frame) e += samples[i] * samples[i]
            if (e < bestEnergy) {
                bestEnergy = e
                best = start
            }
            start += frame / 2
        }
        return best + frame / 2
    }
}
