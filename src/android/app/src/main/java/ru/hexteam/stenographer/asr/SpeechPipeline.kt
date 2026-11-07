package ru.hexteam.stenographer.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.Vad
import ru.hexteam.stenographer.audio.AudioEnhancer
import ru.hexteam.stenographer.audio.DecodedAudio
import ru.hexteam.stenographer.audio.MusicDetector
import ru.hexteam.stenographer.audio.SpooledAudio
import ru.hexteam.stenographer.audio.VocalSeparator
import ru.hexteam.stenographer.model.SpeakerTurn
import ru.hexteam.stenographer.model.TimedWord
import ru.hexteam.stenographer.model.Utterance
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

enum class DenoiseMode { AUTO, ON, OFF }

data class PipelineOptions(
    val profile: Profile = Profile.PODCAST,
    /** Exact speaker count, or 0 to detect automatically (capped by the profile). */
    val numSpeakers: Int = 0,
    val denoise: DenoiseMode = DenoiseMode.AUTO,
    val latinAnglicisms: Boolean = true,
)

data class PipelineResult(
    val utterances: List<Utterance>,
    val denoised: Boolean,
    val music: Boolean,
)

/**
 * Full on-device pipeline, block by block so hours of audio fit in memory:
 * [song → UVR vocal separation | speech → high-pass + level → adaptive DPDFNet] →
 * VAD → GigaAM ASR with token timestamps → pyannote + 3D-Speaker diarization per block →
 * speakers linked across blocks by voice embeddings → profile smoothing → replicas.
 */
class SpeechPipeline(
    private val assets: AssetManager,
    private val threads: Int,
    private val separator: () -> VocalSeparator,
) {

    /** @param fraction overall progress 0..1 of the whole recording. */
    fun interface ProgressListener {
        fun onProgress(title: String, fraction: Float)
    }

    /** Cores are shared between ASR and the diarization running next to it. */
    private val asrThreads = (threads / 2).coerceAtLeast(1)
    private val diarizationThreads = (threads - asrThreads).coerceAtLeast(1)
    private val diarizationExecutor = Executors.newSingleThreadExecutor()

    private val recognizer by lazy { OfflineRecognizer(assets, ModelConfigs.recognizer(asrThreads)) }
    private val embedder by lazy { SpeakerEmbeddingExtractor(assets, ModelConfigs.embedding(threads)) }

    /** Loads the ASR model eagerly so the first run is not slowed down. */
    fun warmUp() {
        recognizer
    }

    fun process(audio: SpooledAudio, options: PipelineOptions, listener: ProgressListener): PipelineResult {
        val speech = audio.speech
        val rate = ModelConfigs.SAMPLE_RATE
        val profile = options.profile
        val music = profile.music || (profile == Profile.PODCAST &&
            MusicDetector.isMusic(speech.read(0, MUSIC_PROBE_SEC * rate), rate))

        val blockSec = if (music) Profile.MUSIC.blockSec else profile.blockSec
        val blocks = BlockPlanner.plan(speech.frames, rate, blockSec) { start, count -> speech.read(start, count) }
        val cap = if (options.numSpeakers > 0) options.numSpeakers else profile.maxSpeakers
        val linker = SpeakerLinker(maxSpeakers = cap)
        val words = mutableListOf<TimedWord>()
        val turns = mutableListOf<SpeakerTurn>()
        var denoisedAny = false

        blocks.forEachIndexed { index, range ->
            val offsetSec = range.first.toFloat() / rate
            val tag = if (blocks.size > 1) " · блок ${index + 1}/${blocks.size}" else ""
            fun report(title: String, from: Float, to: Float, f: Float) =
                listener.onProgress(title + tag, (index + from + (to - from) * f) / blocks.size)

            val count = (range.last - range.first + 1).toInt()
            val asrFrom = if (music) 0.5f else 0f
            val voice = if (music) {
                report("Отделение вокала от музыки", 0f, 0.5f, 0f)
                val decoded = audio.original?.let { original ->
                    val ratio = original.sampleRate.toDouble() / rate
                    original.readDecoded((range.first * ratio).toLong(), (count * ratio).toInt())
                } ?: speech.readDecoded(range.first, count)
                separator().separate(decoded, rate) { report("Отделение вокала от музыки", 0f, 0.5f, it) }
            } else {
                speech.read(range.first, count)
            }

            // Diarization uses the signal before denoising: DPDFNet makes voices more alike
            // (two speakers 0.69 → 0.76 similarity) and they got merged; ASR uses the denoised one.
            val speakerBlock = AudioEnhancer.normalizePeak(AudioEnhancer.highPass(voice, rate))
            var block = speakerBlock
            // Speech denoisers destroy singing (similarity 12% on a song), so never mix them with music.
            val denoise = !music && when (options.denoise) {
                DenoiseMode.ON -> true
                DenoiseMode.OFF -> false
                DenoiseMode.AUTO -> AudioEnhancer.estimateSnrDb(block, rate) < AUTO_DENOISE_SNR_DB
            }
            if (denoise) {
                report("Шумоподавление", asrFrom, asrFrom, 0f)
                block = AudioEnhancer.normalizePeak(denoise(block))
                denoisedAny = true
            }

            // ASR and diarization are independent: run them in parallel on separate cores.
            val asrProgress = AtomicReference(0f)
            val diarProgress = AtomicReference(0f)
            fun reportBoth() =
                report("Распознавание и определение говорящих", asrFrom, 1f, (asrProgress.get() + diarProgress.get()) / 2)
            val singleBlockExact = blocks.size == 1 && options.numSpeakers > 0
            val diarizationJob = diarizationExecutor.submit<List<SpeakerTurn>> {
                diarize(speakerBlock, if (singleBlockExact) options.numSpeakers else 0, minDurationOff(profile, music)) {
                    diarProgress.set(it)
                    reportBoth()
                }
            }
            val chunks = detectSpeech(block, music)
            chunks.forEachIndexed { i, chunk ->
                words += recognize(chunk, offsetSec)
                asrProgress.set((i + 1f) / chunks.size)
                reportBoth()
            }
            val local = diarizationJob.get()
            if (chunks.isEmpty()) return@forEachIndexed
            val mapping = linker.link(localSpeakers(speakerBlock, local))
            Log.i(TAG, "block ${index + 1}/${blocks.size}: ${local.map { it.speaker }.distinct().size} local speakers → $mapping")
            turns += local.map { SpeakerTurn(it.start + offsetSec, it.end + offsetSec, mapping.getValue(it.speaker)) }
        }

        if (words.isEmpty()) return PipelineResult(emptyList(), denoisedAny, music)
        listener.onProgress("Сборка реплик", 1f)
        val final = linker.finalMapping()
        Log.i(TAG, "final speakers: $final")
        var globalTurns = turns.map { it.copy(speaker = final.getValue(it.speaker)) }.sortedBy { it.start }
        if (profile.minTurnSec > 0f) globalTurns = TurnSmoother.smooth(globalTurns, profile.minTurnSec)
        val maxUtterance = if (music) Profile.MUSIC.maxUtteranceSec else profile.maxUtteranceSec
        val utterances = TranscriptAligner.align(words, globalTurns, maxUtteranceSec = maxUtterance).let { list ->
            if (options.latinAnglicisms) list.map { it.copy(text = AnglicismNormalizer.normalize(it.text)) } else list
        }
        return PipelineResult(utterances, denoisedAny, music)
    }

    /** Re-recognizes one fragment (a replica the user asked to redo). */
    fun recognizeFragment(fragment: DecodedAudio, latinAnglicisms: Boolean): String {
        val rate = ModelConfigs.SAMPLE_RATE
        val audio = AudioEnhancer.normalizePeak(AudioEnhancer.highPass(fragment.toMono(rate), rate))
        val chunks = detectSpeech(audio, music = false).ifEmpty { listOf(Chunk(0f, audio.size.toFloat() / rate, audio)) }
        val text = chunks.flatMap { recognize(it, 0f) }.joinToString(" ") { it.text }
        return if (latinAnglicisms) AnglicismNormalizer.normalize(text) else text
    }

    private fun minDurationOff(profile: Profile, music: Boolean) =
        if (music) Profile.MUSIC.minDurationOffSec else profile.minDurationOffSec

    /** Voice print of every local speaker from up to [EMBEDDING_SEC] of its longest turns. */
    private fun localSpeakers(block: FloatArray, turns: List<SpeakerTurn>): List<SpeakerLinker.LocalSpeaker> {
        val rate = ModelConfigs.SAMPLE_RATE
        return turns.groupBy { it.speaker }.map { (speaker, own) ->
            val stream = embedder.createStream()
            try {
                var used = 0f
                for (t in own.sortedByDescending { it.end - it.start }) {
                    if (used >= EMBEDDING_SEC) break
                    // Presentation turns last minutes; the model's memory grows with input length
                    // (a 5-min turn took > 3 GB), so take at most the remaining budget from the middle.
                    val take = minOf(t.end - t.start, EMBEDDING_SEC - used)
                    val mid = (t.start + t.end) / 2
                    val from = ((mid - take / 2) * rate).toInt().coerceIn(0, block.size)
                    val to = ((mid + take / 2) * rate).toInt().coerceIn(from, block.size)
                    stream.acceptWaveform(block.copyOfRange(from, to), rate)
                    used += take
                }
                stream.inputFinished()
                val embedding = if (embedder.isReady(stream)) embedder.compute(stream) else FloatArray(embedder.dim())
                SpeakerLinker.LocalSpeaker(speaker, embedding, own.sumOf { (it.end - it.start).toDouble() }.toFloat())
            } finally {
                stream.release()
            }
        }
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

    private fun recognize(chunk: Chunk, offsetSec: Float): List<TimedWord> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(chunk.samples, ModelConfigs.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return WordBuilder.build(
                result.tokens.toList(), result.timestamps.toList(), chunk.start + offsetSec, chunk.end + offsetSec,
            )
        } finally {
            stream.release()
        }
    }

    private fun diarize(audio: FloatArray, numSpeakers: Int, minDurationOff: Float, onProgress: (Float) -> Unit): List<SpeakerTurn> {
        val diarizer = OfflineSpeakerDiarization(assets, ModelConfigs.diarization(diarizationThreads, numSpeakers, minDurationOff))
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
        const val TAG = "SpeechPipeline"
        const val PAD_SAMPLES = ModelConfigs.SAMPLE_RATE / 10 // 100 ms around each VAD chunk

        /** Below this estimated SNR the DPDFNet denoiser is applied in AUTO mode. */
        const val AUTO_DENOISE_SNR_DB = 20f

        /** Seconds of the start of a podcast checked for music. */
        const val MUSIC_PROBE_SEC = 60

        /** Seconds of speech per local speaker used for its voice print. */
        const val EMBEDDING_SEC = 20f
    }
}
