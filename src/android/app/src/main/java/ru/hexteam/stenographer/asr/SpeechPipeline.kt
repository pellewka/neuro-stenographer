package ru.hexteam.stenographer.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.Vad
import ru.hexteam.stenographer.audio.AudioEnhancer
import ru.hexteam.stenographer.audio.DecodedAudio
import ru.hexteam.stenographer.audio.MusicDetector
import ru.hexteam.stenographer.audio.VocalSeparator
import ru.hexteam.stenographer.model.SpeakerTurn
import ru.hexteam.stenographer.model.TimedWord
import ru.hexteam.stenographer.model.Utterance

enum class DenoiseMode { AUTO, ON, OFF }

/** SPEECH/MUSIC force the path; AUTO picks MUSIC when [MusicDetector] hears a song. */
enum class AudioType { AUTO, SPEECH, MUSIC }

data class PipelineOptions(
    /** Exact speaker count, or 0 to detect automatically. */
    val numSpeakers: Int = 0,
    val denoise: DenoiseMode = DenoiseMode.AUTO,
    val latinAnglicisms: Boolean = true,
    val audioType: AudioType = AudioType.AUTO,
)

data class PipelineResult(
    val utterances: List<Utterance>,
    val snrDb: Float,
    val denoised: Boolean,
    val music: Boolean,
)

/**
 * Full on-device pipeline: song? → UVR vocal separation, otherwise high-pass + level →
 * (adaptive) DPDFNet denoising → VAD → GigaAM ASR with token timestamps →
 * pyannote + 3D-Speaker diarization → word/speaker alignment → Latin anglicisms.
 */
class SpeechPipeline(
    private val assets: AssetManager,
    private val threads: Int,
    private val separator: () -> VocalSeparator,
) {

    enum class Stage(val title: String) {
        SEPARATION("Отделение вокала от музыки"),
        DENOISE("Шумоподавление"),
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

    fun process(decoded: DecodedAudio, options: PipelineOptions, listener: ProgressListener): PipelineResult {
        val mono = decoded.toMono(ModelConfigs.SAMPLE_RATE)
        val music = when (options.audioType) {
            AudioType.SPEECH -> false
            AudioType.MUSIC -> true
            AudioType.AUTO -> MusicDetector.isMusic(mono, ModelConfigs.SAMPLE_RATE)
        }
        val voice = if (music) {
            listener.onProgress(Stage.SEPARATION, 0f)
            separator().separate(decoded, ModelConfigs.SAMPLE_RATE) { listener.onProgress(Stage.SEPARATION, it) }
        } else {
            mono
        }

        var audio = AudioEnhancer.normalizePeak(AudioEnhancer.highPass(voice, ModelConfigs.SAMPLE_RATE))
        val snr = AudioEnhancer.estimateSnrDb(audio, ModelConfigs.SAMPLE_RATE)
        // Speech denoisers destroy singing (similarity 12% on a song), so never mix them with music.
        val denoise = !music && when (options.denoise) {
            DenoiseMode.ON -> true
            DenoiseMode.OFF -> false
            DenoiseMode.AUTO -> snr < AUTO_DENOISE_SNR_DB
        }
        if (denoise) {
            listener.onProgress(Stage.DENOISE, 0f)
            audio = AudioEnhancer.normalizePeak(denoise(audio))
        }

        listener.onProgress(Stage.VAD, 0f)
        val chunks = detectSpeech(audio, music)

        val words = mutableListOf<TimedWord>()
        chunks.forEachIndexed { i, chunk ->
            words += recognize(chunk)
            listener.onProgress(Stage.ASR, (i + 1f) / chunks.size)
        }
        if (words.isEmpty()) return PipelineResult(emptyList(), snr, denoise, music)

        val turns = diarize(audio, options.numSpeakers) { listener.onProgress(Stage.DIARIZATION, it) }

        listener.onProgress(Stage.ALIGN, 1f)
        val utterances = TranscriptAligner.align(words, turns).let { list ->
            if (options.latinAnglicisms) list.map { it.copy(text = AnglicismNormalizer.normalize(it.text)) } else list
        }
        return PipelineResult(utterances, snr, denoise, music)
    }

    private fun denoise(audio: FloatArray): FloatArray {
        val denoiser = OfflineSpeechDenoiser(assets, ModelConfigs.denoiser(threads))
        try {
            val result = denoiser.run(audio, ModelConfigs.SAMPLE_RATE)
            check(result.sampleRate == ModelConfigs.SAMPLE_RATE) { "Unexpected denoiser rate ${result.sampleRate}" }
            return result.samples
        } finally {
            denoiser.release()
        }
    }

    private class Chunk(val start: Float, val end: Float, val samples: FloatArray)

    private fun detectSpeech(audio: FloatArray, music: Boolean): List<Chunk> {
        val config = ModelConfigs.vad(music)
        val vad = Vad(assets, config)
        val raw = mutableListOf<Pair<Int, Int>>()
        try {
            val window = config.sileroVadModelConfig.windowSize
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

    private fun Int.toSec(): Float = this.toFloat() / ModelConfigs.SAMPLE_RATE

    private companion object {
        const val PAD_SAMPLES = ModelConfigs.SAMPLE_RATE / 10 // 100 ms around each VAD chunk

        /** Below this estimated SNR the DPDFNet denoiser is applied in AUTO mode. */
        const val AUTO_DENOISE_SNR_DB = 20f
    }
}
