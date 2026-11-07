package ru.hexteam.stenographer.asr

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Asset paths and tuned parameters of the bundled sherpa-onnx models. */
object ModelConfigs {

    private const val ASR_DIR = "models/gigaam-v3-punct"
    private const val VAD_MODEL = "models/silero_vad.onnx"
    private const val SEGMENTATION_MODEL = "models/pyannote-segmentation-3.0.onnx"
    /** Voice prints for linking/merging speakers: few calls, so the most discriminative model. */
    private const val EMBEDDING_MODEL = "models/3dspeaker-eres2netv2.onnx"

    /** Inside diarization one embedding per window step: CAM++ is 4–5× faster at equal accuracy. */
    private const val DIARIZATION_EMBEDDING_MODEL = "models/3dspeaker-campplus.onnx"
    private const val DENOISER_MODEL = "models/dpdfnet2.onnx"

    const val SAMPLE_RATE = 16_000

    /**
     * Clustering threshold (lower = more speakers). Deliberately low: the diarizer may split one
     * person, and SpeakerLinker merges splits back by clean 20 s voice prints. Calibrated on
     * LibriSpeech conversations of 2–10 real speakers: 88.1% time-weighted speaker accuracy,
     * while two similar synthetic voices are still kept apart.
     */
    const val CLUSTER_THRESHOLD = 0.5f

    fun recognizer(threads: Int) = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 64),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$ASR_DIR/encoder.int8.onnx",
                decoder = "$ASR_DIR/decoder.onnx",
                joiner = "$ASR_DIR/joiner.onnx",
            ),
            tokens = "$ASR_DIR/tokens.txt",
            modelType = "nemo_transducer",
            numThreads = threads,
        ),
        // Beam search recovers words greedy decoding drops (~+35% ASR time, timestamps kept).
        decodingMethod = "modified_beam_search",
        maxActivePaths = 4,
    )

    fun denoiser(threads: Int) = OfflineSpeechDenoiserConfig(
        model = OfflineSpeechDenoiserModelConfig(
            dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model = DENOISER_MODEL),
            numThreads = threads,
        ),
    )

    /**
     * Songs: separated vocals keep faint reverb tails, so a stricter threshold and
     * longer chunks (fewer cuts inside lines) work best — tuned on a test song: 77% → 81%.
     */
    fun vad(music: Boolean = false) = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = VAD_MODEL,
            threshold = if (music) 0.6f else 0.45f,
            minSilenceDuration = 0.3f,
            minSpeechDuration = 0.2f,
            windowSize = 512,
            maxSpeechDuration = if (music) 30f else 20f,
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
    )

    /** @param numSpeakers exact speaker count, or 0 to detect automatically. */
    /**
     * @param windowShift step of the 10 s segmentation window as a fraction of it; each step costs
     *   one voice embedding. 0.25 with CAM++: RTF 0.07 on a Mac core budget of 2 threads.
     */
    fun diarization(threads: Int, numSpeakers: Int, minDurationOff: Float = 0.4f, windowShift: Float = 0.25f) =
        OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SEGMENTATION_MODEL, windowShiftRatio = windowShift),
            numThreads = threads,
        ),
        embedding = SpeakerEmbeddingExtractorConfig(model = DIARIZATION_EMBEDDING_MODEL, numThreads = threads),
        clustering = FastClusteringConfig(
            numClusters = if (numSpeakers > 0) numSpeakers else -1,
            threshold = CLUSTER_THRESHOLD,
        ),
        minDurationOn = 0.2f,
        minDurationOff = minDurationOff,
    )

    fun embedding(threads: Int) = SpeakerEmbeddingExtractorConfig(model = EMBEDDING_MODEL, numThreads = threads)
}
