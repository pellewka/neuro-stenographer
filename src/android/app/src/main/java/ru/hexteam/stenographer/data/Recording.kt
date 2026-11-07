package ru.hexteam.stenographer.data

import ru.hexteam.stenographer.asr.Profile
import ru.hexteam.stenographer.model.Utterance
import java.io.File

enum class RecordingStatus { PROCESSING, DONE, FAILED }

/** One item of the recordings history. Audio lives next to its metadata on disk. */
data class Recording(
    val id: String,
    val title: String,
    val createdAt: Long,
    val audioFile: File,
    val profile: Profile = Profile.PODCAST,
    val durationMs: Long = 0,
    val status: RecordingStatus = RecordingStatus.PROCESSING,
    val utterances: List<Utterance> = emptyList(),
    val processingMs: Long = 0,
    val denoised: Boolean = false,
    val music: Boolean = false,
    val summary: String? = null,
    val error: String? = null,
) {
    val rtf: Float get() = if (durationMs > 0) processingMs.toFloat() / durationMs else 0f
    val speakerCount: Int get() = utterances.map { it.speakerId }.distinct().size
    val plainText: String get() = utterances.joinToString(" ") { it.text }
}
