package ru.hexteam.stenographer.llm

import ru.hexteam.stenographer.model.Utterance

/**
 * Local Qwen3 post-processing:
 *  - [summarize] builds a short meeting protocol from the transcript;
 *  - [polish] fixes punctuation/case of each replica, but only accepts the
 *    result when the word sequence is unchanged, so it can never hurt WER.
 */
class LlmPostProcessor(private val llm: LlamaBridge) {

    fun summarize(utterances: List<Utterance>): String {
        val dialog = QwenPrompts.dialogText(utterances, MAX_SUMMARY_INPUT_CHARS)
        val prompt = QwenPrompts.chat(QwenPrompts.SUMMARY_SYSTEM, dialog)
        return QwenPrompts.clean(llm.generate(prompt, MAX_SUMMARY_TOKENS, SUMMARY_REPEAT_PENALTY))
    }

    fun polish(utterances: List<Utterance>, onProgress: (Float) -> Unit): List<Utterance> =
        utterances.mapIndexed { i, u ->
            val prompt = QwenPrompts.chat(QwenPrompts.POLISH_SYSTEM, u.text)
            val maxTokens = (u.text.length / 2 + 16).coerceAtMost(MAX_POLISH_TOKENS)
            val candidate = QwenPrompts.clean(llm.generate(prompt, maxTokens))
            onProgress((i + 1f) / utterances.size)
            if (WordGuard.sameWords(u.text, candidate)) u.copy(text = candidate) else u
        }

    private companion object {
        const val MAX_SUMMARY_INPUT_CHARS = 7_000
        const val MAX_SUMMARY_TOKENS = 300
        const val SUMMARY_REPEAT_PENALTY = 1.2f
        const val MAX_POLISH_TOKENS = 256
    }
}

object QwenPrompts {

    const val SUMMARY_SYSTEM =
        "Ты секретарь заседания. По расшифровке диалога составь краткий протокол на русском " +
            "строго в формате:\n" +
            "Тема: одно предложение.\n" +
            "Обсуждение:\n- speaker_N: главный тезис (по одному пункту на участника)\n" +
            "Решения:\n- решение\n" +
            "Задачи:\n- кто и что делает\n" +
            "Каждый пункт пиши один раз, без повторов и без markdown. " +
            "Используй только факты из расшифровки, ничего не выдумывай."

    const val POLISH_SYSTEM =
        "Расставь знаки препинания и заглавные буквы в тексте реплики. " +
            "Нельзя менять, добавлять, удалять или переставлять слова. " +
            "Верни только исправленный текст без пояснений."

    /** Qwen3 ChatML prompt in non-thinking mode. */
    fun chat(system: String, user: String): String =
        "<|im_start|>system\n$system<|im_end|>\n" +
            "<|im_start|>user\n$user /no_think<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    fun dialogText(utterances: List<Utterance>, maxChars: Int): String {
        val lines = utterances.map { "${it.speakerId}: ${it.text}" }
        val kept = mutableListOf<String>()
        var total = 0
        for (line in lines) {
            if (total + line.length > maxChars) break
            kept += line
            total += line.length + 1
        }
        return kept.joinToString("\n")
    }

    fun clean(output: String): String =
        output.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace("<|im_end|>", "")
            .replace(Regex("\\*\\*|__|^#+\\s*", RegexOption.MULTILINE), "")
            .trim()
}

object WordGuard {
    private val nonWord = Regex("[^\\p{L}\\p{Nd}]+")

    fun words(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(nonWord).filter { it.isNotEmpty() }

    fun sameWords(original: String, candidate: String): Boolean =
        candidate.isNotBlank() && words(original) == words(candidate)
}
