package ru.hexteam.stenographer.asr

import ru.hexteam.stenographer.model.TimedWord

/**
 * Turns SentencePiece tokens with per-token timestamps (relative to a chunk)
 * into words with absolute times.
 *
 * Tokens starting with '▁' begin a new word; punctuation tokens stick to the
 * previous word. A transducer emits a token roughly at its onset, so word end
 * is approximated by the next word start, capped by [MAX_TOKEN_TAIL].
 */
object WordBuilder {

    /** Raw SentencePiece marker; sherpa-onnx results already replace it with a space. */
    private val WORD_BOUNDARIES = charArrayOf('▁', ' ')
    private const val MAX_TOKEN_TAIL = 0.35f

    /** Recognizer timestamps lag the acoustic onset a little. */
    private const val ONSET_LEAD = 0.08f

    fun build(
        tokens: List<String>,
        timestamps: List<Float>,
        chunkStart: Float,
        chunkEnd: Float,
    ): List<TimedWord> {
        if (tokens.isEmpty()) return emptyList()
        if (timestamps.size != tokens.size) {
            return spreadEvenly(joinTokens(tokens), chunkStart, chunkEnd)
        }

        data class Draft(val text: String, val firstTs: Float, val lastTs: Float)

        val drafts = mutableListOf<Draft>()
        var pendingBoundary = true
        tokens.forEachIndexed { i, raw ->
            val ts = chunkStart + timestamps[i]
            val startsWord = raw.isNotEmpty() && raw[0] in WORD_BOUNDARIES
            val piece = raw.trimStart(*WORD_BOUNDARIES)
            if (piece.isEmpty()) {
                pendingBoundary = true
                return@forEachIndexed
            }
            val isPunctuation = piece.all { !it.isLetterOrDigit() }
            val newWord = drafts.isEmpty() || ((startsWord || pendingBoundary) && !isPunctuation)
            if (newWord) {
                drafts += Draft(piece, ts, ts)
            } else {
                val last = drafts.removeAt(drafts.lastIndex)
                drafts += last.copy(text = last.text + piece, lastTs = ts)
            }
            pendingBoundary = false
        }

        return drafts.mapIndexed { i, d ->
            val start = (d.firstTs - ONSET_LEAD).coerceAtLeast(chunkStart)
            val nextStart = drafts.getOrNull(i + 1)?.let { it.firstTs - ONSET_LEAD } ?: chunkEnd
            val end = minOf(nextStart, d.lastTs + MAX_TOKEN_TAIL, chunkEnd).coerceAtLeast(start)
            TimedWord(d.text, start, end)
        }
    }

    internal fun joinTokens(tokens: List<String>): String =
        tokens.joinToString("").replace('▁', ' ').trim()

    internal fun spreadEvenly(text: String, start: Float, end: Float): List<TimedWord> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val step = (end - start) / words.size
        return words.mapIndexed { i, w -> TimedWord(w, start + i * step, start + (i + 1) * step) }
    }
}
