package ru.hexteam.stenographer.engine

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hexteam.stenographer.asr.PipelineOptions
import ru.hexteam.stenographer.asr.Profile
import ru.hexteam.stenographer.asr.SpeechPipeline
import ru.hexteam.stenographer.audio.AudioDecoder
import ru.hexteam.stenographer.audio.AudioSpooler
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

/**
 * Application-wide owner of models, history and the processing queue. It outlives
 * activities, and [ProcessingService] keeps the process alive while it works, so a
 * 6–8 hour recording keeps processing with the screen off.
 */
class ProcessingEngine(private val app: Application) {

    data class Job(val recordingId: String, val title: String, val fraction: Float?)

    data class State(
        val setup: Setup = Setup.Preparing("Подготовка моделей", null),
        val recordings: List<Recording> = emptyList(),
        val activeJob: Job? = null,
        val queued: Int = 0,
        val summarizingId: String? = null,
        /** Replica being re-recognized: recording id to replica index. */
        val reprocessing: Pair<String, Int>? = null,
        val settings: Settings = Settings(),
    ) {
        val isBusy: Boolean get() = activeJob != null || queued > 0
    }

    sealed interface Setup {
        data class Preparing(val title: String, val fraction: Float?) : Setup
        data object Ready : Setup
        data class Failed(val message: String) : Setup
    }

    sealed interface Event {
        data class Finished(val recordingId: String) : Event
        data class Message(val text: String) : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val llmModel = BundledAsset(app, BundledAsset.LLM)
    private val separationModel = BundledAsset(app, BundledAsset.VOCAL_SEPARATION)
    private val pipeline = SpeechPipeline(app.assets, threads) { VocalSeparator(separationModel.file, threads) }
    private val asrDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val decoder = AudioDecoder(app)
    private val spooler = AudioSpooler(decoder)
    private val repository = RecordingRepository(app.filesDir)
    private val settingsStore = SettingsStore(app)
    private val llm = LlmService(llmModel, threads)
    private val queue = Channel<String>(Channel.UNLIMITED)

    init {
        _state.update { it.copy(settings = settingsStore.load()) }
        scope.launch {
            val saved = withContext(Dispatchers.IO) { repository.loadAll() }
            _state.update { it.copy(recordings = saved) }
            prepareModels()
            for (id in queue) {
                // Mark active before leaving the queue so the service never sees an idle gap.
                _state.update { it.copy(queued = (it.queued - 1).coerceAtLeast(0), activeJob = Job(id, "Подготовка", null)) }
                val recording = find(id)
                if (recording != null) process(recording) else _state.update { it.copy(activeJob = null) }
            }
        }
    }

    private suspend fun prepareModels() {
        try {
            withContext(Dispatchers.IO) {
                if (separationModel.isBundled()) {
                    separationModel.prepare { f -> setSetup(Setup.Preparing("Распаковка моделей (первый запуск)", f)) }
                }
                if (llmModel.isBundled()) {
                    llmModel.prepare { f -> setSetup(Setup.Preparing("Распаковка LLM (первый запуск)", f)) }
                }
            }
            setSetup(Setup.Preparing("Загрузка модели распознавания", null))
            withContext(asrDispatcher) { pipeline.warmUp() }
            setSetup(Setup.Ready)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "model preparation failed", e)
            setSetup(Setup.Failed(e.message ?: e.toString()))
        }
    }

    private fun setSetup(setup: Setup) = _state.update { it.copy(setup = setup) }

    // region Queue

    /** Adds a recording to the history and to the processing queue. */
    fun enqueue(title: String, extension: String, writeAudio: (File) -> Unit) {
        val profile = _state.value.settings.profile
        scope.launch {
            val created = runCatching {
                withContext(Dispatchers.IO) { repository.create(title, extension, profile, writeAudio) }
            }.getOrElse {
                emit(Event.Message("Не удалось сохранить аудио: ${it.message}"))
                return@launch
            }
            _state.update { it.copy(recordings = listOf(created) + it.recordings, queued = it.queued + 1) }
            ContextCompat.startForegroundService(app, ProcessingService.intent(app))
            queue.send(created.id)
        }
    }

    fun nextRecordingNumber(): Int = repository.nextRecordingNumber(_state.value.recordings)

    private suspend fun process(recording: Recording) {
        val settings = _state.value.settings
        val spoolDir = File(app.cacheDir, "spool/${recording.id}")
        setProgress(recording, "Чтение аудио", 0f)
        val started = SystemClock.elapsedRealtime()
        val updated = try {
            val uri = Uri.fromFile(recording.audioFile)
            val durationUs = withContext(Dispatchers.IO) { decoder.durationUs(uri) } ?: 0L
            val keepOriginal = recording.profile.music ||
                (recording.profile == Profile.PODCAST && durationUs in 1..KEEP_ORIGINAL_MAX_US)
            val spooled = withContext(Dispatchers.IO) {
                spooler.spool(uri, spoolDir, keepOriginal) { f -> setProgress(recording, "Чтение аудио", f) }
            }
            try {
                val options = PipelineOptions(recording.profile, settings.numSpeakers, settings.denoise, settings.latinAnglicisms)
                val result = withContext(asrDispatcher) {
                    pipeline.process(spooled, options) { title, f -> setProgress(recording, title, f) }
                }
                var utterances = result.utterances
                if (settings.llmPolish && utterances.isNotEmpty() && utterances.size <= MAX_POLISH_UTTERANCES) {
                    utterances = llm.polish(utterances) { f -> setProgress(recording, "LLM: пунктуация", f) }
                }
                recording.copy(
                    status = RecordingStatus.DONE, utterances = utterances, durationMs = spooled.speech.durationMs,
                    processingMs = SystemClock.elapsedRealtime() - started, denoised = result.denoised, music = result.music,
                )
            } finally {
                spooled.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "processing failed", e)
            recording.copy(status = RecordingStatus.FAILED, error = e.message ?: e.toString())
        } finally {
            spoolDir.deleteRecursively()
        }
        persist(updated)
        _state.update { it.copy(activeJob = null) }
        emit(Event.Finished(updated.id))
        when {
            updated.status == RecordingStatus.FAILED -> emit(Event.Message("Ошибка обработки: ${updated.error}"))
            updated.utterances.isEmpty() -> emit(Event.Message("Речь в записи не найдена"))
            settings.llmSummary -> summarize(updated.id)
        }
    }

    private fun setProgress(recording: Recording, title: String, fraction: Float?) =
        _state.update { it.copy(activeJob = Job(recording.id, title, fraction)) }

    // endregion

    // region History actions

    fun summarize(id: String) {
        val recording = find(id) ?: return
        if (recording.utterances.isEmpty() || _state.value.summarizingId != null) return
        scope.launch {
            _state.update { it.copy(summarizingId = id) }
            val result = runCatching { llm.summarize(recording.utterances, recording.profile) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.update { it.copy(summarizingId = null) }
            result.onSuccess { text -> find(id)?.let { persist(it.copy(summary = text)) } }
                .onFailure { emit(Event.Message("Ошибка LLM: ${it.message}")) }
        }
    }

    /** Manual correction of one replica: text and/or speaker. */
    fun editUtterance(id: String, index: Int, text: String, speakerId: String) {
        val recording = find(id) ?: return
        val old = recording.utterances.getOrNull(index) ?: return
        val updated = old.copy(text = text.trim().ifEmpty { old.text }, speakerId = speakerId)
        scope.launch { persist(recording.copy(utterances = recording.utterances.toMutableList().also { it[index] = updated })) }
    }

    /** Runs ASR again on the time range of one replica (e.g. after a misrecognition). */
    fun reprocessUtterance(id: String, index: Int) {
        val recording = find(id) ?: return
        val utterance = recording.utterances.getOrNull(index) ?: return
        if (_state.value.reprocessing != null) return
        scope.launch {
            _state.update { it.copy(reprocessing = id to index) }
            val result = runCatching {
                val fragment = withContext(Dispatchers.IO) {
                    decoder.decodeRange(
                        Uri.fromFile(recording.audioFile),
                        (utterance.startMs - FRAGMENT_PAD_MS).coerceAtLeast(0),
                        utterance.endMs + FRAGMENT_PAD_MS,
                    )
                }
                withContext(asrDispatcher) { pipeline.recognizeFragment(fragment, _state.value.settings.latinAnglicisms) }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.update { it.copy(reprocessing = null) }
            result.onSuccess { text ->
                val current = find(id) ?: return@onSuccess
                if (text.isBlank()) {
                    emit(Event.Message("В фрагменте не найдено речи"))
                    return@onSuccess
                }
                val list = current.utterances.toMutableList()
                if (index in list.indices) list[index] = list[index].copy(text = text)
                persist(current.copy(utterances = list))
                emit(Event.Message("Фрагмент распознан заново"))
            }.onFailure { emit(Event.Message("Ошибка повторного распознавания: ${it.message}")) }
        }
    }

    fun exportJson(id: String, target: Uri) {
        val recording = find(id) ?: return
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openOutputStream(target, "wt")
                    checkNotNull(stream) { "Не удалось открыть файл" }.use {
                        it.write(TranscriptJson.encode(recording.utterances).toByteArray())
                    }
                }
            }
            emit(Event.Message(result.fold({ "JSON сохранён" }) { "Ошибка экспорта: ${it.message}" }))
        }
    }

    fun delete(id: String): Boolean {
        val recording = find(id) ?: return false
        if (recording.status == RecordingStatus.PROCESSING) return false
        _state.update { s -> s.copy(recordings = s.recordings.filterNot { it.id == id }) }
        scope.launch(Dispatchers.IO) { repository.delete(recording) }
        return true
    }

    fun find(id: String): Recording? = _state.value.recordings.firstOrNull { it.id == id }

    // endregion

    fun updateSettings(transform: (Settings) -> Settings) {
        val updated = transform(_state.value.settings)
        settingsStore.save(updated)
        _state.update { it.copy(settings = updated) }
    }

    fun message(text: String) = emit(Event.Message(text))

    private fun emit(event: Event) {
        _events.tryEmit(event)
    }

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
        val dir = app.getExternalFilesDir("transcripts") ?: return
        val safeName = recording.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        File(dir, "$safeName.json").writeText(TranscriptJson.encode(recording.utterances))
    }

    private companion object {
        const val TAG = "Stenographer"

        /** Podcasts up to 20 min keep the original stereo in case they turn out to be music. */
        const val KEEP_ORIGINAL_MAX_US = 20L * 60 * 1_000_000

        /** Context around a replica re-recognized on request, so edge words are not cut. */
        const val FRAGMENT_PAD_MS = 200L

        /** LLM polishing is per replica: skipped for very long transcripts to keep time sane. */
        const val MAX_POLISH_UTTERANCES = 300
    }
}
