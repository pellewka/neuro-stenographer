package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.audio.Resampler
import ru.hexteam.stenographer.audio.StreamingResampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {

    private fun tone(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { i -> sin(2 * PI * freq * i / rate).toFloat() }

    @Test
    fun `48k to 16k keeps duration and a speech-band tone`() {
        val input = tone(440.0, 48_000, 1.0)

        val out = Resampler.resample(input, 48_000, 16_000)

        assertEquals(16_000, out.size)
        val expected = tone(440.0, 16_000, 1.0)
        val maxErr = (100 until 15_900).maxOf { abs(out[it] - expected[it]) }
        assertTrue("max error $maxErr", maxErr < 0.02f)
    }

    @Test
    fun `tone above the new Nyquist is suppressed`() {
        val out = Resampler.resample(tone(12_000.0, 48_000, 0.5), 48_000, 16_000)

        val peak = (100 until out.size - 100).maxOf { abs(out[it]) }
        assertTrue("alias peak $peak", peak < 0.1f)
    }

    @Test
    fun `streaming in uneven chunks matches whole-signal resampling`() {
        val input = tone(440.0, 48_000, 0.7)
        val whole = Resampler.resample(input, 44_100, 16_000)

        val streamed = mutableListOf<Float>()
        val resampler = StreamingResampler(44_100, 16_000)
        var offset = 0
        for (size in generateSequence(997) { (it * 7 + 13) % 5000 + 1 }) {
            if (offset >= input.size) break
            val end = minOf(input.size, offset + size)
            resampler.push(input.copyOfRange(offset, end)) { streamed += it.toList() }
            offset = end
        }
        resampler.finish { streamed += it.toList() }

        assertEquals(whole.size, streamed.size)
        whole.indices.forEach { assertEquals(whole[it], streamed[it], 1e-5f) }
    }

    @Test
    fun `same rate returns a copy`() {
        val input = floatArrayOf(0.1f, 0.2f)
        val out = Resampler.resample(input, 16_000, 16_000)

        assertTrue(out.contentEquals(input) && out !== input)
    }
}
