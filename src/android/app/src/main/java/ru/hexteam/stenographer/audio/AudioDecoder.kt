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

    fun decode(uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            return decodeTrack(extractor, format)
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrack(extractor: MediaExtractor, format: MediaFormat): DecodedAudio {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw DecodeException("Неизвестный формат")
        val codec = MediaCodec.createDecoderByType(mime)
        val out = FloatArrayBuilder()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

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
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = buffer.asFloatBuffer()
                                while (fb.hasRemaining()) out.add(fb.get())
                            } else {
                                val sb = buffer.asShortBuffer()
                                while (sb.hasRemaining()) out.add(sb.get() / 32768f)
                            }
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
        if (out.size == 0) throw DecodeException("Не удалось декодировать аудио")
        return DecodedAudio(out.toArray(), sampleRate, channels)
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

    fun toArray(): FloatArray = data.copyOf(size)
}
