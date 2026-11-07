package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.audio.OverlapAdd

class OverlapAddTest {

    @Test
    fun `windows cover the whole signal with the requested overlap`() {
        val windows = OverlapAdd.windows(total = 25, chunk = 10, overlap = 2)

        assertEquals(listOf(0 until 10, 8 until 18, 16 until 25), windows)
    }

    @Test
    fun `short signal is a single window`() {
        assertEquals(listOf(0 until 5), OverlapAdd.windows(total = 5, chunk = 10, overlap = 2))
        assertTrue(OverlapAdd.windows(total = 0, chunk = 10, overlap = 2).isEmpty())
    }

    @Test
    fun `merging identical content reconstructs the signal exactly`() {
        val signal = FloatArray(25) { it * 0.1f }
        val windows = OverlapAdd.windows(25, 10, 3)
        val pieces = windows.map { w -> signal.copyOfRange(w.first, w.last + 1) }

        val merged = OverlapAdd.merge(windows, pieces, 25, 3)

        signal.indices.forEach { assertEquals(signal[it], merged[it], 1e-5f) }
    }

    @Test
    fun `overlap region cross-fades between neighbouring chunks`() {
        val windows = OverlapAdd.windows(14, 8, 2)
        val pieces = listOf(FloatArray(8) { 1f }, FloatArray(8) { 0f })

        val merged = OverlapAdd.merge(windows, pieces, 14, 2)

        assertEquals(1f, merged[5], 1e-5f)
        assertTrue(merged[6] in 0.01f..0.99f && merged[7] in 0.01f..0.99f && merged[6] > merged[7])
        assertEquals(0f, merged[8], 1e-5f)
    }
}
