package ru.hexteam.stenographer.audio

import java.io.File

/**
 * Extracts the vocals from a song with UVR MDX-Net (sherpa-onnx C API via JNI).
 * The model needs ~2.4 GB of RAM per call regardless of length up to ~6 s, and far more
 * for longer input, so the song is processed in 5.9 s chunks merged by overlap-add
 * (same accuracy on the test song: 80.8%).
 */
class VocalSeparator(private val model: File, private val threads: Int) {

    /** @return vocals as mono PCM at [targetRate]. */
    fun separate(audio: DecodedAudio, targetRate: Int, onProgress: (Float) -> Unit = {}): FloatArray {
        check(model.exists()) { "Модель отделения вокала не найдена" }
        val (left, right) = stereoAtModelRate(audio)
        val handle = VocalSeparatorNative.nativeCreate(model.path, threads)
        check(handle != 0L) { "Не удалось загрузить модель отделения вокала" }
        try {
            val windows = OverlapAdd.windows(left.size, CHUNK_FRAMES, OVERLAP_FRAMES)
            var outRate = MODEL_SAMPLE_RATE
            val pieces = windows.mapIndexed { i, w ->
                val packed = VocalSeparatorNative.nativeProcess(
                    handle, left.copyOfRange(w.first, w.last + 1), right.copyOfRange(w.first, w.last + 1), MODEL_SAMPLE_RATE,
                ) ?: error("Не удалось отделить вокал")
                outRate = packed[0].toInt()
                onProgress((i + 1f) / windows.size)
                packed.copyOfRange(1, packed.size)
            }
            val vocals = OverlapAdd.merge(windows, pieces, left.size, OVERLAP_FRAMES)
            return Resampler.resample(vocals, outRate, targetRate)
        } finally {
            VocalSeparatorNative.nativeDestroy(handle)
        }
    }

    /** Planar left/right at 44.1 kHz; mono input is duplicated. */
    private fun stereoAtModelRate(audio: DecodedAudio): Pair<FloatArray, FloatArray> {
        val frames = audio.samples.size / audio.channels
        fun plane(c: Int): FloatArray {
            val channel = minOf(c, audio.channels - 1)
            val raw = FloatArray(frames) { f -> audio.samples[f * audio.channels + channel] }
            return Resampler.resample(raw, audio.sampleRate, MODEL_SAMPLE_RATE)
        }
        return plane(0) to plane(1)
    }

    companion object {
        /** UVR models are trained on 44.1 kHz stereo. */
        const val MODEL_SAMPLE_RATE = 44_100
        private const val CHUNK_FRAMES = 260_000 // ≈ 5.9 s: one MDX-Net segment
        private const val OVERLAP_FRAMES = MODEL_SAMPLE_RATE // 1 s cross-fade
    }
}

/** Chunking with linear cross-fades between neighbouring chunks. */
object OverlapAdd {

    fun windows(total: Int, chunk: Int, overlap: Int): List<IntRange> {
        require(chunk > overlap) { "Chunk must be longer than overlap" }
        if (total <= 0) return emptyList()
        val result = mutableListOf<IntRange>()
        var start = 0
        while (true) {
            val end = minOf(total, start + chunk)
            result += start until end
            if (end == total) return result
            start += chunk - overlap
        }
    }

    fun merge(windows: List<IntRange>, pieces: List<FloatArray>, total: Int, overlap: Int): FloatArray {
        val out = FloatArray(total)
        val weight = FloatArray(total)
        windows.forEachIndexed { index, w ->
            val piece = pieces[index]
            val length = minOf(piece.size, w.last - w.first + 1)
            for (i in 0 until length) {
                var g = 1f
                if (index > 0 && i < overlap) g = minOf(g, (i + 1f) / (overlap + 1))
                if (index < windows.lastIndex && i >= length - overlap) g = minOf(g, (length - i).toFloat() / (overlap + 1))
                out[w.first + i] += piece[i] * g
                weight[w.first + i] += g
            }
        }
        for (i in 0 until total) if (weight[i] > 0f) out[i] /= weight[i]
        return out
    }
}

private object VocalSeparatorNative {
    init {
        System.loadLibrary("stenollm")
    }

    external fun nativeCreate(model: String, threads: Int): Long
    external fun nativeProcess(handle: Long, left: FloatArray, right: FloatArray, sampleRate: Int): FloatArray?
    external fun nativeDestroy(handle: Long)
}
