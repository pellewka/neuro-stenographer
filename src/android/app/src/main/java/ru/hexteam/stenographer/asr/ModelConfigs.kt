package ru.hexteam.stenographer.asr

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Asset paths and tuned parameters of the bundled sherpa-onnx models. */
object ModelConfigs {

    private const val ASR_DIR = "models/gigaam-v3-punct"
    private const val VAD_MODEL = "models/silero_vad.onnx"
    private const val SEGMENTATION_MODEL = "models/pyannote-segmentation-3.0.onnx"
    private const val EMBEDDING_MODEL = "models/3dspeaker-eres2netv2.onnx"

    const val SAMPLE_RATE = 16_000

    /** Default cosine-distance threshold for speaker clustering (lower = more speakers). */
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
        decodingMethod = "greedy_search",
    )

    fun vad() = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = VAD_MODEL,
            threshold = 0.45f,
            minSilenceDuration = 0.3f,
            minSpeechDuration = 0.2f,
            windowSize = 512,
            maxSpeechDuration = 20f,
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
    )

    /** @param numSpeakers exact speaker count, or 0 to detect automatically. */
    fun diarization(threads: Int, numSpeakers: Int) = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SEGMENTATION_MODEL),
            numThreads = threads,
        ),
        embedding = SpeakerEmbeddingExtractorConfig(model = EMBEDDING_MODEL, numThreads = threads),
        clustering = FastClusteringConfig(
            numClusters = if (numSpeakers > 0) numSpeakers else -1,
            threshold = CLUSTER_THRESHOLD,
        ),
        minDurationOn = 0.2f,
        minDurationOff = 0.4f,
    )
}
