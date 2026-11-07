package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.asr.WordBuilder

class WordBuilderTest {

    @Test
    fun `joins subword tokens into words and attaches punctuation`() {
        val tokens = listOf("▁Добр", "ый", "▁день", "!", "▁Как", "▁дела", "?")
        val ts = listOf(0.0f, 0.2f, 0.5f, 0.8f, 1.2f, 1.5f, 1.8f)

        val words = WordBuilder.build(tokens, ts, chunkStart = 10f, chunkEnd = 12.5f)

        assertEquals(listOf("Добрый", "день!", "Как", "дела?"), words.map { it.text })
        assertEquals(10f, words[0].start, 1e-3f)
        assertEquals(10.5f - 0.08f, words[1].start, 1e-3f)
    }

    @Test
    fun `space-prefixed tokens from sherpa results split words too`() {
        val tokens = listOf(" Запа", "сов", " ", "х", "ватит", " на", " 3", " месяца", ",")
        val ts = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.6f, 0.8f, 1.0f, 1.2f)

        val words = WordBuilder.build(tokens, ts, 0f, 2f)

        assertEquals(listOf("Запасов", "хватит", "на", "3", "месяца,"), words.map { it.text })
    }

    @Test
    fun `standalone boundary token starts the next word`() {
        val words = WordBuilder.build(listOf("▁", "е", "сть"), listOf(0f, 0.1f, 0.2f), 0f, 1f)

        assertEquals(listOf("есть"), words.map { it.text })
    }

    @Test
    fun `word end never exceeds the next word start or the chunk end`() {
        val words = WordBuilder.build(listOf("▁а", "▁б"), listOf(0f, 5f), 0f, 5.1f)

        assertTrue(words[0].end <= words[1].start)
        assertTrue(words[1].end <= 5.1f)
        assertTrue(words.all { it.end >= it.start })
    }

    @Test
    fun `falls back to even spread when timestamps are missing`() {
        val words = WordBuilder.build(listOf("▁раз", "▁два"), emptyList(), 2f, 4f)

        assertEquals(listOf("раз", "два"), words.map { it.text })
        assertEquals(2f, words[0].start, 1e-3f)
        assertEquals(3f, words[1].start, 1e-3f)
        assertEquals(4f, words[1].end, 1e-3f)
    }

    @Test
    fun `empty input yields no words`() {
        assertTrue(WordBuilder.build(emptyList(), emptyList(), 0f, 1f).isEmpty())
    }
}
