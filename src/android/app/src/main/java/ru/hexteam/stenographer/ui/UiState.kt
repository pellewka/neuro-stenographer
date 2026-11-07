package ru.hexteam.stenographer.ui

import android.net.Uri
import ru.hexteam.stenographer.model.Utterance

sealed interface SetupState {
    data class Preparing(val title: String, val fraction: Float?) : SetupState
    data object Ready : SetupState
    data class Failed(val message: String) : SetupState
}

sealed interface SummaryState {
    data object Idle : SummaryState
    data object Running : SummaryState
    data class Done(val text: String) : SummaryState
    data class Failed(val message: String) : SummaryState
}

data class AudioSource(val uri: Uri, val name: String)

data class Processing(val title: String, val fraction: Float?)

data class Stats(val audioMs: Long, val processingMs: Long) {
    val rtf: Float get() = if (audioMs > 0) processingMs.toFloat() / audioMs else 0f
}

data class Settings(
    /** 0 = detect automatically. */
    val numSpeakers: Int = 0,
    val llmSummary: Boolean = true,
    val llmPolish: Boolean = false,
)

data class UiState(
    val setup: SetupState = SetupState.Preparing("Подготовка моделей", null),
    val audio: AudioSource? = null,
    val isRecording: Boolean = false,
    val processing: Processing? = null,
    val utterances: List<Utterance> = emptyList(),
    val stats: Stats? = null,
    val summary: SummaryState = SummaryState.Idle,
    val settings: Settings = Settings(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val message: String? = null,
) {
    val isBusy: Boolean get() = processing != null || isRecording || setup !is SetupState.Ready
}
