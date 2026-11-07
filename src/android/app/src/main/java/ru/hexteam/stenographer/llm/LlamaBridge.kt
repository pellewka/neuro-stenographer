package ru.hexteam.stenographer.llm

import java.io.Closeable

/** Thin Kotlin wrapper over the llama.cpp JNI library. Not thread-safe: use from one thread. */
class LlamaBridge private constructor(private var handle: Long) : Closeable {

    /** @param repeatPenalty 1.0 disables the penalty (plain greedy decoding). */
    fun generate(prompt: String, maxTokens: Int, repeatPenalty: Float = 1.0f): String {
        check(handle != 0L) { "LLM is closed" }
        val bytes = LlamaNative.nativeGenerate(handle, prompt, maxTokens, repeatPenalty)
            ?: error("LLM generation failed")
        return bytes.toString(Charsets.UTF_8)
    }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        fun load(modelPath: String, contextSize: Int, threads: Int): LlamaBridge {
            val handle = LlamaNative.nativeLoad(modelPath, contextSize, threads)
            require(handle != 0L) { "Не удалось загрузить LLM: $modelPath" }
            return LlamaBridge(handle)
        }
    }
}

private object LlamaNative {
    init {
        System.loadLibrary("stenollm")
    }

    external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, repeatPenalty: Float): ByteArray?
    external fun nativeFree(handle: Long)
}
