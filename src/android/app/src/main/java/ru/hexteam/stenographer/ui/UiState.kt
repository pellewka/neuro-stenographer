package ru.hexteam.stenographer.ui

import ru.hexteam.stenographer.data.Recording
import ru.hexteam.stenographer.data.Settings

enum class Tab { HISTORY, HOME, SETTINGS }

sealed interface SetupState {
    data class Preparing(val title: String, val fraction: Float?) : SetupState
    data object Ready : SetupState
    data class Failed(val message: String) : SetupState
}

/** State of the big button on the home screen. */
sealed interface HomeState {
    data object Idle : HomeState
    data class Recording(val elapsedMs: Long, val level: Float) : HomeState
    data class Processing(val title: String, val fraction: Float?) : HomeState
    data class Done(val recordingId: String) : HomeState
}

/** Progress of the recording currently being processed, shown in its history card. */
data class ActiveJob(val recordingId: String, val title: String, val fraction: Float?)

data class PlayerState(
    val recordingId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
)

data class UiState(
    val setup: SetupState = SetupState.Preparing("Подготовка моделей", null),
    val tab: Tab = Tab.HOME,
    val home: HomeState = HomeState.Idle,
    val recordings: List<Recording> = emptyList(),
    val activeJob: ActiveJob? = null,
    val summarizingId: String? = null,
    val query: String = "",
    val expandedId: String? = null,
    val player: PlayerState = PlayerState(),
    val unseenCount: Int = 0,
    val settings: Settings = Settings(),
    val message: String? = null,
) {
    val canStart: Boolean get() = setup is SetupState.Ready && activeJob == null && home !is HomeState.Recording

    val filteredRecordings: List<Recording>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return recordings
            return recordings.filter { it.title.contains(q, ignoreCase = true) || it.plainText.contains(q, ignoreCase = true) }
        }
}
