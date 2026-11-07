package ru.hexteam.stenographer.asr

import ru.hexteam.stenographer.model.SpeakerTurn
import ru.hexteam.stenographer.model.TimedWord
import ru.hexteam.stenographer.model.Utterance
import kotlin.math.roundToLong

/**
 * Merges ASR words and diarization turns into replicas: every word gets the
 * speaker whose turn overlaps it most, consecutive words of one speaker form
 * a replica, and a long pause also starts a new replica.
 */
object TranscriptAligner {

    const val DEFAULT_MAX_PAUSE_SEC = 2.0f

    private val SENTENCE_END = Regex("[.!?…]$")

    /**
     * @param maxUtteranceSec long monologues (lectures, podcasts) are split at the first
     *   sentence end after this length, and unconditionally at twice this length.
     */
    fun align(
        words: List<TimedWord>,
        turns: List<SpeakerTurn>,
        maxPauseSec: Float = DEFAULT_MAX_PAUSE_SEC,
        maxUtteranceSec: Float = Float.POSITIVE_INFINITY,
    ): List<Utterance> {
        if (words.isEmpty()) return emptyList()
        val sortedWords = words.sortedBy { it.start }
        val sortedTurns = turns.sortedBy { it.start }
        val labeled = sortedWords.map { it to speakerFor(it, sortedTurns) }

        val groups = mutableListOf<MutableList<Pair<TimedWord, Int>>>()
        for (item in labeled) {
            val current = groups.lastOrNull()
            val previous = current?.last()
            val sameSpeaker = previous?.second == item.second
            val shortPause = previous != null && item.first.start - previous.first.end <= maxPauseSec
            val length = if (current != null) item.first.end - current.first().first.start else 0f
            val tooLong = previous != null &&
                ((length > maxUtteranceSec && SENTENCE_END.containsMatchIn(previous.first.text)) || length > 2 * maxUtteranceSec)
            if (current != null && sameSpeaker && shortPause && !tooLong) current += item else groups += mutableListOf(item)
        }

        val speakerNames = mutableMapOf<Int, String>()
        return groups.map { group ->
            val speaker = group.first().second
            val name = speakerNames.getOrPut(speaker) { "speaker_${speakerNames.size + 1}" }
            Utterance(
                speakerId = name,
                startMs = (group.first().first.start * 1000).roundToLong(),
                endMs = (group.last().first.end * 1000).roundToLong(),
                text = capitalizeFirst(group.joinToString(" ") { it.first.text }),
            )
        }
    }

    /** @param turns sorted by start. Only turns near the word are inspected: hours of audio stay fast. */
    internal fun speakerFor(word: TimedWord, turns: List<SpeakerTurn>): Int {
        if (turns.isEmpty()) return 0
        // First turn starting at or after the word end; overlapping turns are just before it.
        var lo = 0
        var hi = turns.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (turns[mid].start < word.end) lo = mid + 1 else hi = mid
        }
        val window = (maxOf(0, lo - NEIGHBOURS) until minOf(turns.size, lo + 1)).map { turns[it] }
        val best = window.maxBy { overlap(word, it) }
        if (overlap(word, best) > 0f) return best.speaker
        val mid = (word.start + word.end) / 2
        return window.minBy { distance(mid, it) }.speaker
    }

    private const val NEIGHBOURS = 16

    private fun overlap(word: TimedWord, turn: SpeakerTurn): Float =
        minOf(word.end, turn.end) - maxOf(word.start, turn.start)

    private fun distance(t: Float, turn: SpeakerTurn): Float = when {
        t < turn.start -> turn.start - t
        t > turn.end -> t - turn.end
        else -> 0f
    }

    private fun capitalizeFirst(text: String): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
