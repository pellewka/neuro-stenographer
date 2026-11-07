package ru.hexteam.stenographer.model

/** One replica of the dialog, exactly the fields required by the export format. */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** A recognized word with absolute times in seconds. */
data class TimedWord(
    val text: String,
    val start: Float,
    val end: Float,
)

/** A diarization turn: who spoke in [start, end) seconds. Speaker index is 0-based. */
data class SpeakerTurn(
    val start: Float,
    val end: Float,
    val speaker: Int,
)
