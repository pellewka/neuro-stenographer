package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenographer.export.TranscriptJson
import ru.hexteam.stenographer.model.Utterance

class TranscriptJsonTest {

    @Test
    fun `encodes the required fields in task format`() {
        val json = TranscriptJson.encode(listOf(Utterance("speaker_1", 1250, 4380, "Добрый день!")))

        val compact = json.replace(Regex("\\s+(?=[\"{}\\[\\]])"), "").replace(": ", ":")
        assertEquals(
            "{\"transcript\":[{\"speaker_id\":\"speaker_1\",\"start_ms\":1250,\"end_ms\":4380,\"text\":\"Добрый день!\"}]}",
            compact.trim(),
        )
    }

    @Test
    fun `escapes quotes, backslashes and control characters`() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", TranscriptJson.quote("a\"b\\c\nd\u0001"))
    }

    @Test
    fun `empty transcript is a valid empty array`() {
        val json = TranscriptJson.encode(emptyList())

        assertTrue(json.replace(Regex("\\s"), "") == "{\"transcript\":[]}")
    }
}
