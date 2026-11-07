package ru.hexteam.stenographer.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hexteam.stenographer.StenoApp
import ru.hexteam.stenographer.asr.Profile
import ru.hexteam.stenographer.audio.AudioRecorder
import ru.hexteam.stenographer.data.RecordingRepository
import ru.hexteam.stenographer.data.Settings
import ru.hexteam.stenographer.engine.ProcessingEngine
import java.io.File

/** UI state on top of the application-wide [ProcessingEngine]. */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = (app as StenoApp).engine
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val recorder = AudioRecorder()
    private val playback = PlaybackController(
        scope = viewModelScope,
        onState = { p -> _state.update { it.copy(player = p) } },
        onError = ::showMessage,
    )
    private var recordingJob: Job? = null

    init {
        viewModelScope.launch {
            engine.state.collect { e ->
                _state.update { s ->
                    s.copy(
                        setup = e.setup.toUi(),
                        recordings = e.recordings,
                        activeJob = e.activeJob?.let { ActiveJob(it.recordingId, it.title, it.fraction) },
                        queued = e.queued,
                        summarizingId = e.summarizingId,
                        reprocessing = e.reprocessing,
                        settings = e.settings,
                    )
                }
            }
        }
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is ProcessingEngine.Event.Finished -> _state.update {
                        it.copy(
                            lastFinishedId = event.recordingId,
                            unseenCount = if (it.tab == Tab.HISTORY) 0 else it.unseenCount + 1,
                        )
                    }
                    is ProcessingEngine.Event.Message -> showMessage(event.text)
                }
            }
        }
    }

    private fun ProcessingEngine.Setup.toUi(): SetupState = when (this) {
        is ProcessingEngine.Setup.Preparing -> SetupState.Preparing(title, fraction)
        ProcessingEngine.Setup.Ready -> SetupState.Ready
        is ProcessingEngine.Setup.Failed -> SetupState.Failed(message)
    }

    // region Navigation

    fun selectTab(tab: Tab) = _state.update {
        it.copy(tab = tab, unseenCount = if (tab == Tab.HISTORY) 0 else it.unseenCount)
    }

    fun acknowledgeDone() = _state.update { it.copy(lastFinishedId = null) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleExpanded(id: String) = _state.update { it.copy(expandedId = if (it.expandedId == id) null else id) }

    fun selectProfile(profile: Profile) = engine.updateSettings { it.copy(profile = profile) }

    // endregion

    // region Recording and import

    fun toggleRecording() {
        recordingJob?.let {
            it.cancel()
            return
        }
        if (!_state.value.canStart) return
        val temp = File(getApplication<Application>().cacheDir, "recording.wav")
        recordingJob = viewModelScope.launch {
            _state.update { it.copy(recording = RecordingState(0, 0f), lastFinishedId = null) }
            try {
                recorder.record(temp) { level, elapsed ->
                    _state.update { it.copy(recording = RecordingState(elapsed, level)) }
                }
            } catch (e: CancellationException) {
                // Normal stop: the WAV header is finalized inside record().
            } catch (e: Throwable) {
                showMessage("Ошибка записи: ${e.message}")
            } finally {
                recordingJob = null
                _state.update { it.copy(recording = null) }
            }
            if (temp.length() > MIN_RECORDING_BYTES) {
                engine.enqueue("${RecordingRepository.RECORDING_TITLE}${engine.nextRecordingNumber()}", "wav") { target ->
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } else {
                showMessage("Запись слишком короткая")
            }
        }
    }

    /** Several files at once (an album) are queued and processed one after another. */
    fun importAudio(uris: List<Uri>) {
        if (_state.value.setup !is SetupState.Ready || uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        _state.update { it.copy(lastFinishedId = null) }
        uris.forEach { uri ->
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Импорт"
            val extension = name.substringAfterLast('.', "").ifBlank {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "audio"
            }
            engine.enqueue(name.substringBeforeLast('.'), extension) { target ->
                val input = checkNotNull(resolver.openInputStream(uri)) { "Не удалось открыть файл" }
                input.use { src -> target.outputStream().use { src.copyTo(it) } }
            }
        }
        if (uris.size > 1) showMessage("В очереди: ${uris.size} файлов")
    }

    // endregion

    // region History actions

    fun summarize(id: String) = engine.summarize(id)

    fun exportJson(id: String, target: Uri) = engine.exportJson(id, target)

    fun editUtterance(id: String, index: Int, text: String, speakerId: String) = engine.editUtterance(id, index, text, speakerId)

    fun reprocessUtterance(id: String, index: Int) = engine.reprocessUtterance(id, index)

    fun delete(id: String) {
        if (_state.value.player.recordingId == id) playback.release()
        if (!engine.delete(id)) showMessage("Запись ещё обрабатывается")
    }

    fun playFrom(id: String, positionMs: Long) = engine.find(id)?.let { playback.playFrom(id, it.audioFile, positionMs) }

    fun togglePlayback(id: String) = engine.find(id)?.let { playback.toggle(id, it.audioFile) }

    // endregion

    fun updateSettings(transform: (Settings) -> Settings) = engine.updateSettings(transform)

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        playback.release()
    }

    private companion object {
        const val MIN_RECORDING_BYTES = 44 + 16_000 // header + 0.5 s of audio
    }
}
