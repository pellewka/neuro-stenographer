package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.llm.QwenPrompts
import ru.hexteam.stenographer.llm.WordGuard
import ru.hexteam.stenographer.model.Utterance

class LlmTextTest {

    @Test
    fun `guard accepts punctuation and case changes only`() {
        assertTrue(WordGuard.sameWords("добрый день как дела", "Добрый день! Как дела?"))
        assertTrue(WordGuard.sameWords("ещё раз", "Еще раз."))
    }

    @Test
    fun `guard rejects changed, added or dropped words`() {
        assertFalse(WordGuard.sameWords("добрый день", "Добрый вечер."))
        assertFalse(WordGuard.sameWords("добрый день", "Добрый день, коллеги."))
        assertFalse(WordGuard.sameWords("добрый день", ""))
    }

    @Test
    fun `clean strips think block and end token`() {
        assertEquals("Текст.", QwenPrompts.clean("<think>\nхм\n</think>\n\nТекст.<|im_end|>"))
    }

    @Test
    fun `clean removes markdown emphasis and headers`() {
        assertEquals("Тема: план\nspeaker_1: да", QwenPrompts.clean("## Тема: план\n**speaker_1**: да"))
    }

    @Test
    fun `dialog text is truncated by whole lines`() {
        val utterances = listOf(
            Utterance("speaker_1", 0, 1, "раз"),
            Utterance("speaker_2", 1, 2, "два"),
        )

        assertEquals("speaker_1: раз", QwenPrompts.dialogText(utterances, maxChars = 20))
    }

    @Test
    fun `chat prompt disables thinking`() {
        val prompt = QwenPrompts.chat("sys", "hi")

        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertTrue(prompt.contains("hi /no_think"))
    }
}
