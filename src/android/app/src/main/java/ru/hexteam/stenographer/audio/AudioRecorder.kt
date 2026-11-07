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

/** Records 16 kHz mono PCM into a WAV file until the calling coroutine is cancelled. */
class AudioRecorder {

    @SuppressLint("MissingPermission") // checked by the UI before starting
    suspend fun record(target: File) = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
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

    private fun wavHeader(dataBytes: Long): ByteArray {
        val byteRate = SAMPLE_RATE * 2
        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(byteRate); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val WAV_HEADER_SIZE = 44
    }
}
