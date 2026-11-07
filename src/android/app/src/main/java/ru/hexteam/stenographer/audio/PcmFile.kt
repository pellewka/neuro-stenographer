package ru.hexteam.stenographer.audio

import android.net.Uri
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Raw 16-bit little-endian interleaved PCM on disk: random access to any block of hours-long audio. */
class PcmFile(val file: File, val sampleRate: Int, val channels: Int) {

    val frames: Long get() = file.length() / (BYTES_PER_SAMPLE * channels)

    val durationMs: Long get() = frames * 1000 / sampleRate

    /** Reads [count] frames from [startFrame] as interleaved floats. */
    fun read(startFrame: Long, count: Int): FloatArray {
        val available = (frames - startFrame).coerceAtLeast(0).toInt()
        val n = minOf(count, available)
        val bytes = ByteArray(n * channels * BYTES_PER_SAMPLE)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startFrame * channels * BYTES_PER_SAMPLE)
            raf.readFully(bytes)
        }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get() / 32768f }
    }

    fun readDecoded(startFrame: Long, count: Int) = DecodedAudio(read(startFrame, count), sampleRate, channels)

    class Writer(private val file: File) : Closeable {
        private val out = BufferedOutputStream(file.outputStream(), 1 shl 20)

        fun write(samples: FloatArray) {
            val buffer = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buffer.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.write(buffer.array())
        }

        override fun close() = out.close()
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
    }
}

/** Result of spooling: speech-ready 16 kHz mono, plus the original stereo when separation may need it. */
class SpooledAudio(val speech: PcmFile, val original: PcmFile?) {
    fun delete() {
        speech.file.delete()
        original?.file?.delete()
    }
}

/** Streams a file through the decoder into PCM files without keeping it in memory. */
class AudioSpooler(private val decoder: AudioDecoder) {

    fun spool(uri: Uri, dir: File, keepOriginal: Boolean, onProgress: (Float) -> Unit): SpooledAudio {
        dir.mkdirs()
        val speechFile = File(dir, "speech16k.pcm")
        val originalFile = File(dir, "original.pcm")
        var resampler: StreamingResampler? = null
        var format: Pair<Int, Int>? = null

        PcmFile.Writer(speechFile).use { speech ->
            val original = if (keepOriginal) PcmFile.Writer(originalFile) else null
            try {
                decoder.decodeStreaming(uri, onProgress) { samples, rate, channels ->
                    if (format == null) format = rate to channels
                    original?.write(samples)
                    val mono = DecodedAudio.downmix(samples, channels)
                    val r = resampler ?: StreamingResampler(rate, SPEECH_RATE).also { resampler = it }
                    r.push(mono, speech::write)
                }
                resampler?.finish(speech::write)
            } finally {
                original?.close()
            }
        }
        val (rate, channels) = format ?: throw AudioDecoder.DecodeException("Не удалось декодировать аудио")
        return SpooledAudio(
            speech = PcmFile(speechFile, SPEECH_RATE, 1),
            original = if (keepOriginal) PcmFile(originalFile, rate, channels) else null,
        )
    }

    companion object {
        const val SPEECH_RATE = 16_000
    }
}
