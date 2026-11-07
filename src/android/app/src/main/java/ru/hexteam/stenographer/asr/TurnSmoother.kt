package ru.hexteam.stenographer.asr

import ru.hexteam.stenographer.model.SpeakerTurn

/**
 * Presentations: speakers take long sequential turns, so very short turns are almost
 * always diarization noise (a cough, applause, a speaker's voice changing). They are
 * given to the surrounding speaker, and adjacent turns of one speaker are merged.
 */
object TurnSmoother {

    fun smooth(turns: List<SpeakerTurn>, minTurnSec: Float): List<SpeakerTurn> {
        if (turns.size < 2) return turns
        val sorted = turns.sortedBy { it.start }
        val relabeled = sorted.mapIndexed { i, t ->
            if (t.end - t.start >= minTurnSec) return@mapIndexed t
            val prev = sorted.getOrNull(i - 1)
            val next = sorted.getOrNull(i + 1)
            val owner = when {
                prev != null && next != null && prev.speaker == next.speaker -> prev.speaker
                prev != null && next != null -> if (prev.end - prev.start >= next.end - next.start) prev.speaker else next.speaker
                else -> (prev ?: next)?.speaker ?: t.speaker
            }
            t.copy(speaker = owner)
        }
        return merge(relabeled)
    }

    fun merge(turns: List<SpeakerTurn>): List<SpeakerTurn> =
        turns.fold(mutableListOf()) { acc, t ->
            val last = acc.lastOrNull()
            if (last != null && last.speaker == t.speaker) acc[acc.lastIndex] = last.copy(end = maxOf(last.end, t.end))
            else acc += t
            acc
        }
}
