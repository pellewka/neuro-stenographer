package ru.hexteam.stenographer.asr

import kotlin.math.sqrt

/**
 * Long audio is diarized block by block; each block has its own local speaker ids.
 * The linker matches them across blocks by voice embeddings (cosine similarity of
 * speaker-embedding vectors: same voice 0.9+, different voices ≤ 0.6) and finally merges the
 * most similar global speakers until at most [maxSpeakers] remain.
 *
 * Speakers the diarizer separated inside one block are "cannot-link": a mixed cluster can look
 * moderately similar to two people, so such pairs merge only above [splitThreshold] — the level
 * of one person split in two.
 */
class SpeakerLinker(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val maxSpeakers: Int = Int.MAX_VALUE,
    private val splitThreshold: Float = DEFAULT_SPLIT_THRESHOLD,
) {

    /** A speaker found inside one block. */
    data class LocalSpeaker(val localId: Int, val embedding: FloatArray, val durationSec: Float)

    private class Global(val id: Int, var sum: FloatArray, var weight: Float) {
        val centroid: FloatArray get() = normalize(sum)
    }

    private val globals = mutableListOf<Global>()
    private val separated = mutableSetOf<Pair<Int, Int>>()

    /** @return local id → global id for this block. Two locals of one block never share a global. */
    fun link(locals: List<LocalSpeaker>): Map<Int, Int> {
        val candidates = locals.flatMap { local ->
            globals.map { g -> Triple(local, g, cosine(normalize(local.embedding), g.centroid)) }
        }.filter { it.third >= threshold }.sortedByDescending { it.third }

        val mapping = mutableMapOf<Int, Int>()
        val usedGlobals = mutableSetOf<Int>()
        for ((local, global, _) in candidates) {
            if (local.localId in mapping || global.id in usedGlobals) continue
            mapping[local.localId] = global.id
            usedGlobals += global.id
        }
        for (local in locals.sortedByDescending { it.durationSec }) {
            val id = mapping.getOrPut(local.localId) {
                Global(globals.size, FloatArray(local.embedding.size), 0f).also { globals += it }.id
            }
            val g = globals[id]
            val v = normalize(local.embedding)
            g.sum = FloatArray(v.size) { g.sum[it] + v[it] * local.durationSec }
            g.weight += local.durationSec
        }
        val ids = mapping.values.distinct()
        for (a in ids) for (b in ids) if (a < b) separated += a to b
        return mapping
    }

    /**
     * global id → final speaker index (0-based). Per-block diarization tends to split one
     * person into two clusters, so voices closer than [threshold] are always merged; then the
     * closest voices are merged further until at most [maxSpeakers] remain.
     */
    fun finalMapping(): Map<Int, Int> {
        val clusters = globals.map { mutableListOf(it.id) to it.sum.copyOf() }.toMutableList()
        val cap = maxSpeakers.coerceAtLeast(1)
        while (clusters.size > 1) {
            var best = Triple(0, 1, -2f)
            for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val sim = cosine(normalize(clusters[i].second), normalize(clusters[j].second))
                // Above the cap any pair may merge; otherwise separated pairs need split-level similarity.
                val needed = if (clusters.size > cap) -1f else if (isSeparated(clusters[i].first, clusters[j].first)) splitThreshold else threshold
                if (sim >= needed && sim > best.third) best = Triple(i, j, sim)
            }
            if (best.third < -1f) break
            val (i, j, _) = best
            val merged = clusters[i].first + clusters[j].first
            val sum = FloatArray(clusters[i].second.size) { clusters[i].second[it] + clusters[j].second[it] }
            clusters.removeAt(j)
            clusters[i] = merged.toMutableList() to sum
        }
        return clusters.flatMapIndexed { index, (ids, _) -> ids.map { it to index } }.toMap()
    }

    private fun isSeparated(a: List<Int>, b: List<Int>): Boolean =
        a.any { x -> b.any { y -> (minOf(x, y) to maxOf(x, y)) in separated } }

    companion object {
        const val DEFAULT_THRESHOLD = 0.7f
        const val DEFAULT_SPLIT_THRESHOLD = 0.85f

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }

        fun normalize(v: FloatArray): FloatArray {
            var norm = 0f
            for (x in v) norm += x * x
            val n = sqrt(norm)
            return if (n < 1e-9f) v.copyOf() else FloatArray(v.size) { v[it] / n }
        }
    }
}
