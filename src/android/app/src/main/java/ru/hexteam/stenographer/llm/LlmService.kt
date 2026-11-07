package ru.hexteam.stenographer.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hexteam.stenographer.asr.Profile
import ru.hexteam.stenographer.data.BundledAsset
import ru.hexteam.stenographer.model.Utterance
import java.io.Closeable

/** Owns the lazily loaded Qwen3 model and runs all LLM work on one thread. */
class LlmService(private val model: BundledAsset, private val threads: Int) : Closeable {

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private var bridge: LlamaBridge? = null
    private var processor: LlmPostProcessor? = null

    suspend fun summarize(utterances: List<Utterance>, profile: Profile): String =
        withContext(dispatcher) { processor().summarize(utterances, profile) }

    suspend fun polish(utterances: List<Utterance>, onProgress: (Float) -> Unit): List<Utterance> =
        withContext(dispatcher) { processor().polish(utterances, onProgress) }

    private fun processor(): LlmPostProcessor = processor ?: run {
        check(model.isReady()) { "Модель LLM не найдена в APK" }
        val loaded = LlamaBridge.load(model.file.path, CONTEXT_SIZE, threads)
        bridge = loaded
        LlmPostProcessor(loaded).also { processor = it }
    }

    override fun close() {
        bridge?.close()
    }

    private companion object {
        const val CONTEXT_SIZE = 4096
    }
}
