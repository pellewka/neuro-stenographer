package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.asr.TranscriptAligner
import ru.hexteam.stenographer.model.SpeakerTurn
import ru.hexteam.stenographer.model.TimedWord

class TranscriptAlignerTest {

    private fun w(text: String, start: Float, end: Float) = TimedWord(text, start, end)

    @Test
    fun `splits replicas on speaker change and numbers speakers by first appearance`() {
        val words = listOf(
            w("Привет,", 0.5f, 0.9f), w("коллеги.", 1.0f, 1.5f),
            w("Здравствуйте!", 2.0f, 2.7f),
            w("Начнём.", 3.2f, 3.6f),
        )
        val turns = listOf(
            SpeakerTurn(0.4f, 1.6f, speaker = 3),
            SpeakerTurn(1.9f, 2.8f, speaker = 0),
            SpeakerTurn(3.1f, 3.7f, speaker = 3),
        )

        val result = TranscriptAligner.align(words, turns)

        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), result.map { it.speakerId })
        assertEquals("Привет, коллеги.", result[0].text)
        assertEquals(500L, result[0].startMs)
        assertEquals(1500L, result[0].endMs)
        assertEquals(2000L, result[1].startMs)
    }

    @Test
    fun `long pause inside one speaker starts a new replica`() {
        val words = listOf(w("раз", 0f, 0.5f), w("два", 5f, 5.5f))
        val turns = listOf(SpeakerTurn(0f, 6f, 0))

        val result = TranscriptAligner.align(words, turns, maxPauseSec = 2f)

        assertEquals(2, result.size)
        assertEquals("Раз", result[0].text)
        assertEquals("Два", result[1].text)
    }

    @Test
    fun `word outside every turn goes to the nearest speaker`() {
        val turns = listOf(SpeakerTurn(0f, 1f, 0), SpeakerTurn(5f, 6f, 1))

        assertEquals(1, TranscriptAligner.speakerFor(w("x", 4.0f, 4.2f), turns))
        assertEquals(0, TranscriptAligner.speakerFor(w("y", 1.2f, 1.4f), turns))
    }

    @Test
    fun `word overlapping two turns gets the larger overlap`() {
        val turns = listOf(SpeakerTurn(0f, 1.1f, 0), SpeakerTurn(1.1f, 3f, 1))

        assertEquals(1, TranscriptAligner.speakerFor(w("x", 1.0f, 1.6f), turns))
    }

    @Test
    fun `no diarization turns means a single speaker`() {
        val result = TranscriptAligner.align(listOf(w("а", 0f, 1f), w("б", 1f, 2f)), emptyList())

        assertEquals(1, result.size)
        assertEquals("speaker_1", result[0].speakerId)
    }

    @Test
    fun `empty words give empty transcript`() {
        assertTrue(TranscriptAligner.align(emptyList(), listOf(SpeakerTurn(0f, 1f, 0))).isEmpty())
    }
}
