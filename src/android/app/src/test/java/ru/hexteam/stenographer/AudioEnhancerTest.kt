package ru.hexteam.stenographer

import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.audio.AudioEnhancer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class AudioEnhancerTest {

    private val rate = 16_000

    private fun tone(freq: Double, seconds: Double, amp: Float = 0.5f) =
        FloatArray((rate * seconds).toInt()) { i -> amp * sin(2 * PI * freq * i / rate).toFloat() }

    private fun peak(x: FloatArray, from: Int = rate / 10) = (from until x.size).maxOf { abs(x[it]) }

    @Test
    fun `high-pass removes mains hum and keeps speech band`() {
        assertTrue(peak(AudioEnhancer.highPass(tone(50.0, 1.0), rate)) < 0.25f)
        assertTrue(peak(AudioEnhancer.highPass(tone(1000.0, 1.0), rate)) > 0.48f)
    }

    @Test
    fun `quiet audio is amplified but loud audio is untouched`() {
        assertTrue(peak(AudioEnhancer.normalizePeak(tone(300.0, 0.5, amp = 0.1f)), 0) > 0.85f)
        val loud = tone(300.0, 0.5, amp = 0.95f)
        assertTrue(AudioEnhancer.normalizePeak(loud) === loud)
    }

    @Test
    fun `snr is high for speech with pauses and low for noisy audio`() {
        val random = Random(1)
        val speechWithPauses = tone(300.0, 1.0) + FloatArray(rate) { random.nextFloat() * 0.001f }
        val noisy = FloatArray(2 * rate) { i ->
            (if (i < rate) 0.5f * sin(2 * PI * 300 * i / rate).toFloat() else 0f) + (random.nextFloat() - 0.5f) * 0.6f
        }

        assertTrue(AudioEnhancer.estimateSnrDb(speechWithPauses, rate) > 30f)
        assertTrue(AudioEnhancer.estimateSnrDb(noisy, rate) < 15f)
    }
}
