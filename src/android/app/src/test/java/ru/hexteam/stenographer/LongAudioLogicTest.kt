package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.asr.SpeakerLinker
import ru.hexteam.stenographer.asr.SpeakerLinker.LocalSpeaker
import ru.hexteam.stenographer.asr.TranscriptAligner
import ru.hexteam.stenographer.asr.TurnSmoother
import ru.hexteam.stenographer.model.SpeakerTurn
import ru.hexteam.stenographer.model.TimedWord

class LongAudioLogicTest {

    private val voiceA = floatArrayOf(1f, 0f, 0f, 0.1f)
    private val voiceB = floatArrayOf(0f, 1f, 0f, 0.1f)
    private val voiceC = floatArrayOf(0f, 0f, 1f, 0.1f)
    private fun near(v: FloatArray) = FloatArray(v.size) { v[it] + 0.05f * (it % 2) }

    @Test
    fun `same voices in different blocks get the same global speaker`() {
        val linker = SpeakerLinker()

        val block1 = linker.link(listOf(LocalSpeaker(0, voiceA, 30f), LocalSpeaker(1, voiceB, 20f)))
        // In block 2 the local ids are swapped by the diarizer.
        val block2 = linker.link(listOf(LocalSpeaker(0, near(voiceB), 25f), LocalSpeaker(1, near(voiceA), 25f)))

        assertEquals(block1[0], block2[1])
        assertEquals(block1[1], block2[0])
    }

    @Test
    fun `a new voice becomes a new speaker`() {
        val linker = SpeakerLinker()
        linker.link(listOf(LocalSpeaker(0, voiceA, 30f)))

        val block2 = linker.link(listOf(LocalSpeaker(0, voiceC, 30f)))

        assertEquals(mapOf(0 to 1), block2)
    }

    @Test
    fun `two locals of one block never collapse into one global`() {
        val linker = SpeakerLinker()
        linker.link(listOf(LocalSpeaker(0, voiceA, 30f)))

        val block2 = linker.link(listOf(LocalSpeaker(0, voiceA, 10f), LocalSpeaker(1, near(voiceA), 10f)))

        assertNotEquals(block2[0], block2[1])
    }

    @Test
    fun `speaker cap merges the most similar voices`() {
        val linker = SpeakerLinker(maxSpeakers = 2)
        val m1 = linker.link(listOf(LocalSpeaker(0, voiceA, 30f), LocalSpeaker(1, voiceB, 30f)))
        val m2 = linker.link(listOf(LocalSpeaker(0, floatArrayOf(0.6f, 0f, 0f, 0.5f), 5f)))

        val final = linker.finalMapping()

        assertEquals(2, final.values.toSet().size)
        assertEquals(final.getValue(m1.getValue(0)), final.getValue(m2.getValue(0)))
    }

    @Test
    fun `smoother gives short interruptions to the surrounding speaker`() {
        val turns = listOf(
            SpeakerTurn(0f, 60f, 0),
            SpeakerTurn(60f, 61f, 1), // cough misattributed
            SpeakerTurn(61f, 120f, 0),
            SpeakerTurn(120f, 300f, 1), // next presenter
        )

        val result = TurnSmoother.smooth(turns, minTurnSec = 3f)

        assertEquals(listOf(SpeakerTurn(0f, 120f, 0), SpeakerTurn(120f, 300f, 1)), result)
    }

    @Test
    fun `long monologue is split at sentence ends`() {
        val words = (0 until 40).map { i ->
            val text = if (i % 5 == 4) "слово." else "слово"
            TimedWord(text, i * 2f, i * 2f + 1.5f)
        }

        val result = TranscriptAligner.align(words, listOf(SpeakerTurn(0f, 100f, 0)), maxUtteranceSec = 20f)

        assertTrue(result.size > 1)
        assertTrue(result.all { it.endMs - it.startMs <= 40_000 })
        assertTrue(result.dropLast(1).all { it.text.endsWith(".") })
    }

    @Test
    fun `speaker lookup stays correct with many turns`() {
        val turns = (0 until 10_000).map { SpeakerTurn(it * 2f, it * 2f + 2f, it % 3) }

        assertEquals(5000 % 3, TranscriptAligner.speakerFor(TimedWord("x", 10_000.2f, 10_000.8f), turns))
    }
}
