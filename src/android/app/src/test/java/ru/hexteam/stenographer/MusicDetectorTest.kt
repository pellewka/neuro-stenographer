package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.audio.MusicDetector
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class MusicDetectorTest {

    private val rate = 16_000

    @Test
    fun `fft finds a pure tone in the right bin`() {
        val n = 64
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) }
        val im = DoubleArray(n)

        MusicDetector.fft(re, im)

        val peak = (0..n / 2).maxBy { re[it] * re[it] + im[it] * im[it] }
        assertEquals(5, peak)
    }

    @Test
    fun `continuous bass-heavy audio is music`() {
        val random = Random(2)
        val song = FloatArray(rate * 4) { i ->
            val t = i.toDouble() / rate
            (0.5 * sin(2 * PI * 70 * t) + 0.2 * sin(2 * PI * 440 * t) + 0.05 * (random.nextDouble() - 0.5)).toFloat()
        }

        assertTrue(MusicDetector.isMusic(song, rate))
    }

    @Test
    fun `speech-like audio with pauses is not music`() {
        val speech = FloatArray(rate * 4) { i ->
            val t = i.toDouble() / rate
            if ((t * 2).toInt() % 2 == 0) (0.4 * sin(2 * PI * 300 * t)).toFloat() else 0f
        }

        assertFalse(MusicDetector.isMusic(speech, rate))
    }

    @Test
    fun `continuous broadband noise without bass is not music`() {
        val random = Random(3)
        val noise = FloatArray(rate * 4) { (random.nextFloat() - 0.5f) * 0.5f }

        assertFalse(MusicDetector.isMusic(noise, rate))
    }
}
