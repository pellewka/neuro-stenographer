package ru.hexteam.stenographer.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hexteam.stenographer.asr.PipelineOptions
import ru.hexteam.stenographer.asr.SpeechPipeline
import ru.hexteam.stenographer.audio.AudioDecoder
import ru.hexteam.stenographer.audio.AudioRecorder
import ru.hexteam.stenographer.audio.VocalSeparator
import ru.hexteam.stenographer.data.BundledAsset
import ru.hexteam.stenographer.data.Recording
import ru.hexteam.stenographer.data.RecordingRepository
import ru.hexteam.stenographer.data.RecordingStatus
import ru.hexteam.stenographer.data.Settings
import ru.hexteam.stenographer.data.SettingsStore
import ru.hexteam.stenographer.export.TranscriptJson
import ru.hexteam.stenographer.llm.LlmService
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val llmModel = BundledAsset(app, BundledAsset.LLM)
    private val separationModel = BundledAsset(app, BundledAsset.VOCAL_SEPARATION)
    private val pipeline = SpeechPipeline(app.assets, threads) { VocalSeparator(separationModel.file, threads) }
    private val asrDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val decoder = AudioDecoder(app)
    private val recorder = AudioRecorder()
    private val repository = RecordingRepository(app.filesDir)
    private val settingsStore = SettingsStore(app)
    private val llm = LlmService(llmModel, threads)
    private val playback = PlaybackController(
        scope = viewModelScope,
        onState = { p -> _state.update { it.copy(player = p) } },
        onError = ::showMessage,
    )
    private var recordingJob: Job? = null

    init {
        _state.update { it.copy(settings = settingsStore.load()) }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { repository.loadAll() }
            _state.update { it.copy(recordings = saved) }
            prepareModels()
        }
    }

    private suspend fun prepareModels() {
        try {
            withContext(Dispatchers.IO) {
                if (separationModel.isBundled()) {
                    separationModel.prepare { f -> setSetup(SetupState.Preparing("Распаковка моделей (первый запуск)", f)) }
                }
                if (llmModel.isBundled()) {
                    llmModel.prepare { f -> setSetup(SetupState.Preparing("Распаковка LLM (первый запуск)", f)) }
                }
            }
            setSetup(SetupState.Preparing("Загрузка модели распознавания", null))
            withContext(asrDispatcher) { pipeline.warmUp() }
            setSetup(SetupState.Ready)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "model preparation failed", e)
            setSetup(SetupState.Failed(e.message ?: e.toString()))
        }
    }

    private fun setSetup(setup: SetupState) = _state.update { it.copy(setup = setup) }

    // region Navigation

    fun selectTab(tab: Tab) = _state.update {
        it.copy(tab = tab, unseenCount = if (tab == Tab.HISTORY) 0 else it.unseenCount)
    }

    fun acknowledgeDone() = _state.update { it.copy(home = HomeState.Idle) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleExpanded(id: String) = _state.update { it.copy(expandedId = if (it.expandedId == id) null else id) }

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
            _state.update { it.copy(home = HomeState.Recording(0, 0f)) }
            try {
                recorder.record(temp) { level, elapsed ->
                    _state.update { it.copy(home = HomeState.Recording(elapsed, level)) }
                }
            } catch (e: CancellationException) {
                // Normal stop: the WAV header is finalized inside record().
            } catch (e: Throwable) {
                showMessage("Ошибка записи: ${e.message}")
            } finally {
                recordingJob = null
                _state.update { it.copy(home = HomeState.Idle) }
            }
            if (temp.length() > MIN_RECORDING_BYTES) {
                val number = repository.nextRecordingNumber(_state.value.recordings)
                addAndProcess("${RecordingRepository.RECORDING_TITLE}$number", "wav") { target ->
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } else {
                showMessage("Запись слишком короткая")
            }
        }
    }

    fun importAudio(uri: Uri) {
        if (!_state.value.canStart) return
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Импорт"
        val extension = name.substringAfterLast('.', "").ifBlank {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "audio"
        }
        viewModelScope.launch {
            addAndProcess(name.substringBeforeLast('.'), extension) { target ->
                val input = checkNotNull(resolver.openInputStream(uri)) { "Не удалось открыть файл" }
                input.use { src -> target.outputStream().use { src.copyTo(it) } }
            }
        }
    }

    private suspend fun addAndProcess(title: String, extension: String, writeAudio: (File) -> Unit) {
        val created = runCatching {
            withContext(Dispatchers.IO) { repository.create(title, extension, writeAudio) }
        }.getOrElse {
            showMessage("Не удалось сохранить аудио: ${it.message}")
            return
        }
        _state.update { it.copy(recordings = listOf(created) + it.recordings) }
        process(created)
    }

    private suspend fun process(recording: Recording) {
        val settings = _state.value.settings
        setProgress(recording, "Чтение аудио", null)
        val started = SystemClock.elapsedRealtime()
        val updated = try {
            val decoded = withContext(Dispatchers.Default) { decoder.decode(Uri.fromFile(recording.audioFile)) }
            val durationMs = decoded.durationMs
            val options = PipelineOptions(settings.numSpeakers, settings.denoise, settings.latinAnglicisms, settings.audioType)
            val result = withContext(asrDispatcher) {
                pipeline.process(decoded, options) { stage, f -> setProgress(recording, stage.title, f) }
            }
            var utterances = result.utterances
            if (settings.llmPolish && utterances.isNotEmpty()) {
                setProgress(recording, "LLM: пунктуация", null)
                utterances = llm.polish(utterances) { f -> setProgress(recording, "LLM: пунктуация", f) }
            }
            recording.copy(
                status = RecordingStatus.DONE, utterances = utterances, durationMs = durationMs,
                processingMs = SystemClock.elapsedRealtime() - started, denoised = result.denoised, music = result.music,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "processing failed", e)
            recording.copy(status = RecordingStatus.FAILED, error = e.message ?: e.toString())
        }
        persist(updated)
        _state.update {
            it.copy(
                activeJob = null,
                home = HomeState.Done(updated.id),
                unseenCount = if (it.tab == Tab.HISTORY) 0 else it.unseenCount + 1,
            )
        }
        when {
            updated.status == RecordingStatus.FAILED -> showMessage("Ошибка обработки: ${updated.error}")
            updated.utterances.isEmpty() -> showMessage("Речь в записи не найдена")
            settings.llmSummary -> summarize(updated.id)
        }
    }

    private fun setProgress(recording: Recording, title: String, fraction: Float?) = _state.update {
        it.copy(
            activeJob = ActiveJob(recording.id, title, fraction),
            home = HomeState.Processing(title, fraction),
        )
    }

    // endregion

    // region History actions

    fun summarize(id: String) {
        val recording = find(id) ?: return
        if (recording.utterances.isEmpty() || _state.value.summarizingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(summarizingId = id) }
            val result = runCatching { llm.summarize(recording.utterances) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.update { it.copy(summarizingId = null) }
            result.onSuccess { text -> find(id)?.let { persist(it.copy(summary = text)) } }
                .onFailure { showMessage("Ошибка LLM: ${it.message}") }
        }
    }

    fun exportJson(id: String, target: Uri) {
        val recording = find(id) ?: return
        val json = TranscriptJson.encode(recording.utterances)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(target, "wt")
                    checkNotNull(stream) { "Не удалось открыть файл" }.use { it.write(json.toByteArray()) }
                }
            }
            showMessage(result.fold({ "JSON сохранён" }) { "Ошибка экспорта: ${it.message}" })
        }
    }

    fun delete(id: String) {
        val recording = find(id) ?: return
        if (_state.value.activeJob?.recordingId == id) return
        if (_state.value.player.recordingId == id) playback.release()
        _state.update { s -> s.copy(recordings = s.recordings.filterNot { it.id == id }) }
        viewModelScope.launch(Dispatchers.IO) { repository.delete(recording) }
    }

    fun playFrom(id: String, positionMs: Long) = find(id)?.let { playback.playFrom(id, it.audioFile, positionMs) }

    fun togglePlayback(id: String) = find(id)?.let { playback.toggle(id, it.audioFile) }

    // endregion

    fun updateSettings(transform: (Settings) -> Settings) {
        val updated = transform(_state.value.settings)
        settingsStore.save(updated)
        _state.update { it.copy(settings = updated) }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun find(id: String): Recording? = _state.value.recordings.firstOrNull { it.id == id }

    private suspend fun persist(recording: Recording) {
        _state.update { s -> s.copy(recordings = s.recordings.map { if (it.id == recording.id) recording else it }) }
        runCatching {
            withContext(Dispatchers.IO) {
                repository.save(recording)
                if (recording.status == RecordingStatus.DONE) exportCopy(recording)
            }
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    /** Extra copy in app-specific external storage, reachable via adb / file managers. */
    private fun exportCopy(recording: Recording) {
        val dir = getApplication<Application>().getExternalFilesDir("transcripts") ?: return
        val safeName = recording.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        File(dir, "$safeName.json").writeText(TranscriptJson.encode(recording.utterances))
    }

    override fun onCleared() {
        playback.release()
        llm.close()
    }

    private companion object {
        const val TAG = "Stenographer"
        const val MIN_RECORDING_BYTES = 44 + 16_000 // header + 0.5 s of audio
    }
}
