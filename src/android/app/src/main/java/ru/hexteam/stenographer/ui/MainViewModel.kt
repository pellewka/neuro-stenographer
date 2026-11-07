package ru.hexteam.stenographer.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hexteam.stenographer.asr.SpeechPipeline
import ru.hexteam.stenographer.audio.AudioDecoder
import ru.hexteam.stenographer.audio.AudioRecorder
import ru.hexteam.stenographer.export.TranscriptJson
import ru.hexteam.stenographer.llm.LlamaBridge
import ru.hexteam.stenographer.llm.LlmModelStore
import ru.hexteam.stenographer.llm.LlmPostProcessor
import ru.hexteam.stenographer.model.Utterance
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val pipeline = SpeechPipeline(app.assets, threads)
    private val decoder = AudioDecoder(app)
    private val recorder = AudioRecorder()
    private val modelStore = LlmModelStore(app)

    // Native engines are not thread-safe: each one is used from a single thread.
    private val asrDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val llmDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var llm: LlmPostProcessor? = null
    private var llamaBridge: LlamaBridge? = null

    private var recordingJob: Job? = null
    private var summaryJob: Job? = null
    private var player: MediaPlayer? = null
    private var playerUri: Uri? = null
    private var positionJob: Job? = null

    init {
        viewModelScope.launch { prepareModels() }
    }

    private suspend fun prepareModels() {
        try {
            withContext(Dispatchers.IO) {
                if (modelStore.isBundled()) {
                    modelStore.prepare { f -> setSetup(SetupState.Preparing("Распаковка LLM (первый запуск)", f)) }
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

    fun onAudioPicked(uri: Uri, name: String) {
        if (_state.value.isBusy) return
        transcribe(AudioSource(uri, name))
    }

    fun toggleRecording() {
        val job = recordingJob
        if (job != null) {
            job.cancel()
            return
        }
        if (_state.value.isBusy) return
        val file = File(getApplication<Application>().cacheDir, "recording_${System.currentTimeMillis()}.wav")
        recordingJob = viewModelScope.launch {
            _state.update { it.copy(isRecording = true) }
            try {
                recorder.record(file)
            } catch (e: CancellationException) {
                // Normal stop: the WAV header is finalized inside record().
            } catch (e: Throwable) {
                showMessage("Ошибка записи: ${e.message}")
            } finally {
                _state.update { it.copy(isRecording = false) }
                recordingJob = null
            }
            if (file.length() > MIN_RECORDING_BYTES) transcribe(AudioSource(Uri.fromFile(file), file.name))
        }
    }

    private fun transcribe(source: AudioSource) {
        releasePlayer()
        summaryJob?.cancel()
        _state.update {
            it.copy(
                audio = source, utterances = emptyList(), stats = null, summary = SummaryState.Idle,
                processing = Processing("Чтение аудио", null), positionMs = 0,
            )
        }
        viewModelScope.launch {
            val settings = _state.value.settings
            val started = SystemClock.elapsedRealtime()
            try {
                val samples = withContext(Dispatchers.Default) { decoder.decode(source.uri) }
                val audioMs = samples.size * 1000L / AudioDecoder.TARGET_SAMPLE_RATE
                var utterances = withContext(asrDispatcher) {
                    pipeline.process(samples, settings.numSpeakers) { stage, f -> setProgress(stage.title, f) }
                }
                if (settings.llmPolish && utterances.isNotEmpty()) {
                    utterances = withContext(llmDispatcher) {
                        setProgress("Загрузка LLM", null)
                        postProcessor().polish(utterances) { f -> setProgress("LLM: пунктуация", f) }
                    }
                }
                val stats = Stats(audioMs, SystemClock.elapsedRealtime() - started)
                _state.update { it.copy(utterances = utterances, stats = stats, processing = null) }
                autosave(source.name, utterances)
                if (utterances.isEmpty()) showMessage("Речь в записи не найдена")
                if (settings.llmSummary && utterances.isNotEmpty()) generateSummary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "transcription failed", e)
                _state.update { it.copy(processing = null) }
                showMessage("Ошибка обработки: ${e.message ?: e}")
            }
        }
    }

    private fun setProgress(title: String, fraction: Float?) =
        _state.update { it.copy(processing = Processing(title, fraction)) }

    fun generateSummary() {
        val utterances = _state.value.utterances
        if (utterances.isEmpty() || _state.value.summary == SummaryState.Running) return
        summaryJob = viewModelScope.launch {
            _state.update { it.copy(summary = SummaryState.Running) }
            val result = runCatching { withContext(llmDispatcher) { postProcessor().summarize(utterances) } }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.update {
                it.copy(summary = result.fold({ text -> SummaryState.Done(text) }) { e ->
                    Log.e(TAG, "summary failed", e)
                    SummaryState.Failed(e.message ?: e.toString())
                })
            }
        }
    }

    /** Must be called on [llmDispatcher]. */
    private fun postProcessor(): LlmPostProcessor = llm ?: run {
        check(modelStore.isReady()) { "Модель LLM не найдена в APK" }
        val bridge = LlamaBridge.load(modelStore.modelFile.path, LLM_CONTEXT, threads)
        llamaBridge = bridge
        LlmPostProcessor(bridge).also { llm = it }
    }

    fun updateSettings(transform: (Settings) -> Settings) =
        _state.update { it.copy(settings = transform(it.settings)) }

    fun exportJson(target: Uri) {
        val json = TranscriptJson.encode(_state.value.utterances)
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

    private suspend fun autosave(audioName: String, utterances: List<Utterance>) {
        runCatching {
            withContext(Dispatchers.IO) {
                val dir = getApplication<Application>().getExternalFilesDir("transcripts") ?: return@withContext
                File(dir, audioName.substringBeforeLast('.') + ".json").writeText(TranscriptJson.encode(utterances))
            }
        }.onFailure { Log.w(TAG, "autosave failed", it) }
    }

    fun playFrom(startMs: Long) {
        val uri = _state.value.audio?.uri ?: return
        val mp = player?.takeIf { playerUri == uri } ?: createPlayer(uri) ?: return
        mp.seekTo(startMs.toInt())
        mp.start()
        _state.update { it.copy(isPlaying = true) }
        trackPosition()
    }

    fun togglePlayback() {
        val mp = player
        when {
            mp == null -> playFrom(0)
            mp.isPlaying -> {
                mp.pause()
                _state.update { it.copy(isPlaying = false) }
            }
            else -> {
                mp.start()
                _state.update { it.copy(isPlaying = true) }
                trackPosition()
            }
        }
    }

    private fun createPlayer(uri: Uri): MediaPlayer? {
        releasePlayer()
        return runCatching {
            MediaPlayer().apply {
                setDataSource(getApplication(), uri)
                prepare()
                setOnCompletionListener { _state.update { s -> s.copy(isPlaying = false) } }
            }
        }.onSuccess {
            player = it
            playerUri = uri
        }.onFailure { showMessage("Не удалось воспроизвести аудио") }.getOrNull()
    }

    private fun trackPosition() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                val mp = player ?: break
                _state.update { it.copy(positionMs = mp.currentPosition.toLong()) }
                if (!mp.isPlaying) break
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun releasePlayer() {
        positionJob?.cancel()
        player?.release()
        player = null
        playerUri = null
        _state.update { it.copy(isPlaying = false) }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        releasePlayer()
        llamaBridge?.close()
    }

    private companion object {
        const val TAG = "Stenographer"
        const val LLM_CONTEXT = 4096
        const val MIN_RECORDING_BYTES = 44 + 16_000 // header + 0.5 s of audio
        const val POSITION_POLL_MS = 200L
    }
}
