package ru.hexteam.stenographer.llm

import android.content.Context
import java.io.File

/**
 * llama.cpp needs a real file path, so the bundled GGUF is copied once from
 * the APK assets into internal storage.
 */
class LlmModelStore(private val context: Context) {

    val modelFile: File get() = File(context.filesDir, "models/$MODEL_NAME")

    fun isBundled(): Boolean = runCatching { context.assets.openFd(ASSET_PATH).close() }.isSuccess

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() == bundledSize()

    /** Copies the model if needed; [onProgress] receives 0..1. */
    fun prepare(onProgress: (Float) -> Unit): File {
        val expected = bundledSize()
        if (modelFile.exists() && modelFile.length() == expected) return modelFile

        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.path + ".part")
        context.assets.open(ASSET_PATH).use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (expected > 0) onProgress(copied.toFloat() / expected)
                }
            }
        }
        check(tmp.renameTo(modelFile)) { "Не удалось сохранить модель LLM" }
        return modelFile
    }

    private fun bundledSize(): Long =
        runCatching { context.assets.openFd(ASSET_PATH).use { it.length } }.getOrDefault(-1L)

    companion object {
        const val MODEL_NAME = "qwen3-1.7b-q4_k_m.gguf"
        private const val ASSET_PATH = "models/$MODEL_NAME"
        private const val BUFFER_SIZE = 1 shl 20
    }
}
