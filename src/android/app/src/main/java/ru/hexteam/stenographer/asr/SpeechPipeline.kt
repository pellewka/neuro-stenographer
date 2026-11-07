package ru.hexteam.stenographer.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.Vad
import ru.hexteam.stenographer.model.SpeakerTurn
import ru.hexteam.stenographer.model.TimedWord
import ru.hexteam.stenographer.model.Utterance
import kotlin.math.abs

/**
 * Full on-device pipeline: VAD → GigaAM ASR with token timestamps →
 * pyannote + 3D-Speaker diarization → word/speaker alignment.
 */
class SpeechPipeline(private val assets: AssetManager, private val threads: Int) {

    enum class Stage(val title: String) {
        VAD("Поиск речи"),
        ASR("Распознавание речи"),
        DIARIZATION("Определение говорящих"),
        ALIGN("Сборка реплик"),
    }

    fun interface ProgressListener {
        fun onProgress(stage: Stage, fraction: Float)
    }

    private val recognizer by lazy { OfflineRecognizer(assets, ModelConfigs.recognizer(threads)) }

    /** Loads the ASR model eagerly so the first run is not slowed down. */
    fun warmUp() {
        recognizer
    }

    fun process(samples: FloatArray, numSpeakers: Int, listener: ProgressListener): List<Utterance> {
        val audio = normalize(samples)

        listener.onProgress(Stage.VAD, 0f)
        val chunks = detectSpeech(audio)

        val words = mutableListOf<TimedWord>()
        chunks.forEachIndexed { i, chunk ->
            words += recognize(chunk)
            listener.onProgress(Stage.ASR, (i + 1f) / chunks.size)
        }
        if (words.isEmpty()) return emptyList()

        val turns = diarize(audio, numSpeakers) { listener.onProgress(Stage.DIARIZATION, it) }

        listener.onProgress(Stage.ALIGN, 1f)
        return TranscriptAligner.align(words, turns)
    }

    private class Chunk(val start: Float, val end: Float, val samples: FloatArray)

    private fun detectSpeech(audio: FloatArray): List<Chunk> {
        val vad = Vad(assets, ModelConfigs.vad())
        val raw = mutableListOf<Pair<Int, Int>>()
        try {
            val window = ModelConfigs.vad().sileroVadModelConfig.windowSize
            var offset = 0
            while (offset + window <= audio.size) {
                vad.acceptWaveform(audio.copyOfRange(offset, offset + window))
                drain(vad, raw)
                offset += window
            }
            vad.flush()
            drain(vad, raw)
        } finally {
            vad.release()
        }
        return raw.map { (start, length) ->
            val from = maxOf(0, start - PAD_SAMPLES)
            val to = minOf(audio.size, start + length + PAD_SAMPLES)
            Chunk(from.toSec(), to.toSec(), audio.copyOfRange(from, to))
        }
    }

    private fun drain(vad: Vad, into: MutableList<Pair<Int, Int>>) {
        while (!vad.empty()) {
            val segment = vad.front()
            into += segment.start.toInt() to segment.samples.size
            vad.pop()
        }
    }

    private fun recognize(chunk: Chunk): List<TimedWord> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(chunk.samples, ModelConfigs.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return WordBuilder.build(result.tokens.toList(), result.timestamps.toList(), chunk.start, chunk.end)
        } finally {
            stream.release()
        }
    }

    private fun diarize(audio: FloatArray, numSpeakers: Int, onProgress: (Float) -> Unit): List<SpeakerTurn> {
        val diarizer = OfflineSpeakerDiarization(assets, ModelConfigs.diarization(threads, numSpeakers))
        try {
            // sherpa-onnx JNI looks up invoke(IIJ)Ljava/lang/Integer; which only class-based
            // lambdas have; Kotlin 2 compiles plain lambdas via invokedynamic.
            val callback = @JvmSerializableLambda { done: Int, total: Int, _: Long ->
                if (total > 0) onProgress(done.toFloat() / total)
                0
            }
            val segments = diarizer.processWithCallback(audio, callback)
            return segments.map { SpeakerTurn(it.start, it.end, it.speaker) }
        } finally {
            diarizer.release()
        }
    }

    /** Brings quiet recordings up to a usable level without clipping loud ones. */
    private fun normalize(samples: FloatArray): FloatArray {
        val peak = samples.maxOfOrNull { abs(it) } ?: return samples
        if (peak < 1e-4f || peak >= TARGET_PEAK) return samples
        val gain = minOf(TARGET_PEAK / peak, MAX_GAIN)
        return FloatArray(samples.size) { samples[it] * gain }
    }

    private fun Int.toSec(): Float = this.toFloat() / ModelConfigs.SAMPLE_RATE

    private companion object {
        const val PAD_SAMPLES = ModelConfigs.SAMPLE_RATE / 10 // 100 ms around each VAD chunk
        const val TARGET_PEAK = 0.9f
        const val MAX_GAIN = 10f
    }
}
