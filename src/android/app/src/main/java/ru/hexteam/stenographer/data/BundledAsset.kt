package ru.hexteam.stenographer.data

import android.content.Context
import java.io.File

/**
 * Some native engines (llama.cpp, sherpa-onnx C API) need a real file path,
 * so a bundled model is copied once from the APK assets into internal storage.
 */
class BundledAsset(private val context: Context, private val assetPath: String) {

    val file: File get() = File(context.filesDir, assetPath)

    fun isBundled(): Boolean = runCatching { context.assets.openFd(assetPath).close() }.isSuccess

    fun isReady(): Boolean = file.exists() && file.length() == bundledSize()

    /** Copies the asset if needed; [onProgress] receives 0..1. */
    fun prepare(onProgress: (Float) -> Unit = {}): File {
        val expected = bundledSize()
        if (file.exists() && file.length() == expected) return file

        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        context.assets.open(assetPath).use { input ->
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
        check(tmp.renameTo(file)) { "Не удалось сохранить $assetPath" }
        return file
    }

    private fun bundledSize(): Long =
        runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrDefault(-1L)

    companion object {
        const val LLM = "models/qwen3-1.7b-q4_k_m.gguf"
        const val VOCAL_SEPARATION = "models/uvr-mdx-voc-ft.onnx"
        private const val BUFFER_SIZE = 1 shl 20
    }
}
