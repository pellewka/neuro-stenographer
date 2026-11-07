package ru.hexteam.stenographer.export

import ru.hexteam.stenographer.model.Utterance

/**
 * Serializes the transcript into the format required by the task:
 * {"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"..."}]}
 */
object TranscriptJson {

    fun encode(utterances: List<Utterance>): String = buildString {
        append("{\n  \"transcript\": [")
        utterances.forEachIndexed { index, u ->
            append(if (index == 0) "\n" else ",\n")
            append("    {\n")
            append("      \"speaker_id\": ").append(quote(u.speakerId)).append(",\n")
            append("      \"start_ms\": ").append(u.startMs).append(",\n")
            append("      \"end_ms\": ").append(u.endMs).append(",\n")
            append("      \"text\": ").append(quote(u.text)).append("\n")
            append("    }")
        }
        append(if (utterances.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
    }

    internal fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
