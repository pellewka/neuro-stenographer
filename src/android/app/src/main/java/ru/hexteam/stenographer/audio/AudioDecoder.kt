package ru.hexteam.stenographer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decoded PCM in [-1, 1] at the file's own rate, channels interleaved. */
class DecodedAudio(val samples: FloatArray, val sampleRate: Int, val channels: Int) {

    val durationMs: Long get() = samples.size / channels * 1000L / sampleRate

    /** Mono at [targetRate]: what the speech models consume. */
    fun toMono(targetRate: Int): FloatArray = Resampler.resample(downmix(samples, channels), sampleRate, targetRate)

    companion object {
        fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
            if (channels <= 1) return interleaved
            val frames = interleaved.size / channels
            return FloatArray(frames) { f ->
                var sum = 0f
                for (c in 0 until channels) sum += interleaved[f * channels + c]
                sum / channels
            }
        }
    }
}

/**
 * Decodes any audio container supported by Android (wav, mp3, m4a, ogg, flac, ...).
 * The original rate and channels are kept: vocal separation needs full-band stereo.
 */
class AudioDecoder(private val context: Context) {

    class DecodeException(message: String) : Exception(message)

    /** Receives decoded PCM chunk by chunk: interleaved samples with their format. */
    fun interface PcmSink {
        fun onPcm(samples: FloatArray, sampleRate: Int, channels: Int)
    }

    /** Whole file in memory: only for short audio (tests, recordings). */
    fun decode(uri: Uri): DecodedAudio {
        val out = FloatArrayBuilder()
        var rate = 0
        var channels = 1
        decodeStreaming(uri) { samples, r, c ->
            out.addAll(samples)
            rate = r
            channels = c
        }
        if (out.size == 0) throw DecodeException("Не удалось декодировать аудио")
        return DecodedAudio(out.toArray(), rate, channels)
    }

    /** Track duration from the container, or null if unknown. */
    fun durationUs(uri: Uri): Long? = withTrack(uri) { _, format ->
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
    }

    /** Decodes without holding the whole file: hours of audio stream through [sink]. */
    fun decodeStreaming(uri: Uri, onProgress: (Float) -> Unit = {}, sink: PcmSink) {
        withTrack(uri) { extractor, format -> decodeTrack(extractor, format, onProgress, sink) }
    }

    /** Decodes only [startMs, endMs): used to re-recognize one replica of a long recording. */
    fun decodeRange(uri: Uri, startMs: Long, endMs: Long): DecodedAudio {
        val out = FloatArrayBuilder(1 shl 14)
        var rate = 0
        var channels = 1
        withTrack(uri) { extractor, format ->
            decodeTrack(extractor, format, {}, { samples, r, c ->
                out.addAll(samples)
                rate = r
                channels = c
            }, startMs * 1000, endMs * 1000)
        }
        if (out.size == 0) throw DecodeException("Фрагмент пуст")
        return DecodedAudio(out.toArray(), rate, channels)
    }

    private fun <T> withTrack(uri: Uri, block: (MediaExtractor, MediaFormat) -> T): T {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            return block(extractor, extractor.getTrackFormat(track))
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        onProgress: (Float) -> Unit,
        sink: PcmSink,
        startUs: Long = 0,
        endUs: Long = Long.MAX_VALUE,
    ) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw DecodeException("Неизвестный формат")
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime)
        var decodedAny = false
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: throw DecodeException("Ошибка декодера")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val chunk = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = buffer.asFloatBuffer()
                                FloatArray(fb.remaining()) { fb.get() }
                            } else {
                                val sb = buffer.asShortBuffer()
                                FloatArray(sb.remaining()) { sb.get() / 32768f }
                            }
                            val trimmed = trim(chunk, info.presentationTimeUs, sampleRate, channels, startUs, endUs)
                            if (trimmed.isNotEmpty()) {
                                sink.onPcm(trimmed, sampleRate, channels)
                                decodedAny = true
                            }
                            if (info.presentationTimeUs > endUs) outputDone = true
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        if (!decodedAny) throw DecodeException("Не удалось декодировать аудио")
    }

    /** Keeps only the part of a decoded chunk (starting at [ptsUs]) that lies in [startUs, endUs). */
    private fun trim(chunk: FloatArray, ptsUs: Long, rate: Int, channels: Int, startUs: Long, endUs: Long): FloatArray {
        if (startUs <= 0 && endUs == Long.MAX_VALUE) return chunk
        val frames = chunk.size / channels
        val from = ((startUs - ptsUs) * rate / 1_000_000).coerceIn(0, frames.toLong()).toInt()
        val to = if (endUs == Long.MAX_VALUE) frames else ((endUs - ptsUs) * rate / 1_000_000).coerceIn(from.toLong(), frames.toLong()).toInt()
        return chunk.copyOfRange(from * channels, to * channels)
    }


    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val TIMEOUT_US = 10_000L
    }
}

/** Growable float buffer without boxing. */
class FloatArrayBuilder(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun addAll(values: FloatArray) {
        if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
        System.arraycopy(values, 0, data, size, values.size)
        size += values.size
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
