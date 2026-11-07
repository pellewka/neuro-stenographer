package ru.hexteam.stenographer.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.hexteam.stenographer.export.TranscriptJson
import ru.hexteam.stenographer.model.Utterance
import java.io.File

/**
 * File-based storage of the history: recordings/<id>/{audio.*, meta.json, transcript.json}.
 * transcript.json is exactly the export format, so it doubles as the saved result.
 */
class RecordingRepository(filesDir: File) {

    private val root = File(filesDir, "recordings").apply { mkdirs() }

    fun loadAll(): List<Recording> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { dir -> runCatching { read(dir) }.onFailure { Log.w(TAG, "skip ${dir.name}", it) }.getOrNull() }
            .map { if (it.status == RecordingStatus.PROCESSING) it.copy(status = RecordingStatus.FAILED, error = INTERRUPTED) else it }
            .sortedByDescending { it.createdAt }

    /** Creates a new item; [writeAudio] copies the audio into the item folder. */
    fun create(title: String, extension: String, writeAudio: (File) -> Unit): Recording {
        val id = System.currentTimeMillis().toString()
        val dir = File(root, id).apply { mkdirs() }
        val audio = File(dir, "audio.${extension.ifBlank { "wav" }}")
        writeAudio(audio)
        return Recording(id = id, title = title, createdAt = id.toLong(), audioFile = audio).also(::save)
    }

    fun save(recording: Recording) {
        val dir = recording.audioFile.parentFile ?: return
        val meta = JSONObject()
            .put("id", recording.id)
            .put("title", recording.title)
            .put("createdAt", recording.createdAt)
            .put("audio", recording.audioFile.name)
            .put("durationMs", recording.durationMs)
            .put("status", recording.status.name)
            .put("processingMs", recording.processingMs)
            .put("denoised", recording.denoised)
            .putOpt("summary", recording.summary)
            .putOpt("error", recording.error)
        File(dir, META).writeText(meta.toString())
        File(dir, TRANSCRIPT).writeText(TranscriptJson.encode(recording.utterances))
    }

    fun delete(recording: Recording) {
        recording.audioFile.parentFile?.deleteRecursively()
    }

    fun transcriptFile(recording: Recording): File = File(recording.audioFile.parentFile, TRANSCRIPT)

    /** Next free "Новая запись №N" number. */
    fun nextRecordingNumber(existing: List<Recording>): Int =
        (existing.mapNotNull { NUMBER.find(it.title)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    private fun read(dir: File): Recording {
        val meta = JSONObject(File(dir, META).readText())
        return Recording(
            id = meta.getString("id"),
            title = meta.getString("title"),
            createdAt = meta.getLong("createdAt"),
            audioFile = File(dir, meta.getString("audio")),
            durationMs = meta.optLong("durationMs"),
            status = RecordingStatus.valueOf(meta.getString("status")),
            utterances = readTranscript(File(dir, TRANSCRIPT)),
            processingMs = meta.optLong("processingMs"),
            denoised = meta.optBoolean("denoised"),
            summary = meta.optString("summary").takeIf { meta.has("summary") },
            error = meta.optString("error").takeIf { meta.has("error") },
        )
    }

    private fun readTranscript(file: File): List<Utterance> {
        if (!file.exists()) return emptyList()
        val array: JSONArray = JSONObject(file.readText()).getJSONArray("transcript")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Utterance(o.getString("speaker_id"), o.getLong("start_ms"), o.getLong("end_ms"), o.getString("text"))
        }
    }

    companion object {
        const val RECORDING_TITLE = "Новая запись №"
        private const val META = "meta.json"
        private const val TRANSCRIPT = "transcript.json"
        private const val TAG = "RecordingRepository"
        private const val INTERRUPTED = "Обработка была прервана"
        private val NUMBER = Regex("^${RECORDING_TITLE}(\\d+)$")
    }
}
