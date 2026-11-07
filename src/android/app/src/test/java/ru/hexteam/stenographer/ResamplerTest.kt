package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.audio.Resampler
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
    fun `same rate returns a copy`() {
        val input = floatArrayOf(0.1f, 0.2f)
        val out = Resampler.resample(input, 16_000, 16_000)

        assertTrue(out.contentEquals(input) && out !== input)
    }
}
