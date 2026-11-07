package ru.hexteam.stenographer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** Records 16 kHz mono PCM into a WAV file until the calling coroutine is cancelled. */
class AudioRecorder {

    /** Called for every buffer with RMS level in 0..1 and elapsed recording time. */
    fun interface LevelListener {
        fun onLevel(level: Float, elapsedMs: Long)
    }

    @SuppressLint("MissingPermission") // checked by the UI before starting
    suspend fun record(target: File, listener: LevelListener) = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 10 * 2) // ~100 ms per read
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Микрофон недоступен" }

        RandomAccessFile(target, "rw").use { file ->
            file.setLength(0)
            file.write(ByteArray(WAV_HEADER_SIZE))
            val buffer = ByteArray(bufferSize)
            var dataBytes = 0L
            recorder.startRecording()
            try {
                while (coroutineContext.isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        file.write(buffer, 0, read)
                        dataBytes += read
                        listener.onLevel(rms(buffer, read), dataBytes * 1000 / BYTES_PER_SECOND)
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                file.seek(0)
                file.write(wavHeader(dataBytes))
            }
        }
    }

    private fun rms(buffer: ByteArray, length: Int): Float {
        val samples = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val n = samples.remaining()
        while (samples.hasRemaining()) {
            val s = samples.get() / 32768.0
            sum += s * s
        }
        return if (n == 0) 0f else (sqrt(sum / n) * LEVEL_GAIN).toFloat().coerceIn(0f, 1f)
    }

    private fun wavHeader(dataBytes: Long): ByteArray =
        ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(BYTES_PER_SECOND); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val WAV_HEADER_SIZE = 44
        const val LEVEL_GAIN = 4.0
    }
}
