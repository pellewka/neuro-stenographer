package ru.hexteam.stenographer.llm

import ru.hexteam.stenographer.asr.Profile
import ru.hexteam.stenographer.model.Utterance

/**
 * Local Qwen3 post-processing:
 *  - [summarize] builds a short protocol; hours-long transcripts are summarized
 *    part by part first (map-reduce), so the whole recording is covered;
 *  - [polish] fixes punctuation/case of each replica, but only accepts the
 *    result when the word sequence is unchanged, so it can never hurt WER.
 */
class LlmPostProcessor(private val llm: LlamaBridge) {

    fun summarize(utterances: List<Utterance>, profile: Profile = Profile.PODCAST): String {
        val system = QwenPrompts.summarySystem(profile)
        val full = QwenPrompts.dialogText(utterances, Int.MAX_VALUE)
        if (full.length <= MAX_SUMMARY_INPUT_CHARS) {
            return generate(system, full, MAX_SUMMARY_TOKENS)
        }
        val parts = QwenPrompts.split(utterances, MAX_PARTS)
        val notes = parts.mapIndexed { i, part ->
            val text = QwenPrompts.dialogText(part, MAX_SUMMARY_INPUT_CHARS)
            val span = "${QwenPrompts.clock(part.first().startMs)}–${QwenPrompts.clock(part.last().endMs)}"
            "Часть ${i + 1} ($span):\n" + generate(QwenPrompts.PART_SYSTEM, text, PART_TOKENS)
        }
        return generate(system, notes.joinToString("\n\n"), MAX_SUMMARY_TOKENS)
    }

    private fun generate(system: String, user: String, maxTokens: Int) =
        QwenPrompts.clean(llm.generate(QwenPrompts.chat(system, user), maxTokens, SUMMARY_REPEAT_PENALTY))

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
        const val MAX_PARTS = 8
        const val PART_TOKENS = 160
    }
}

object QwenPrompts {

    const val PART_SYSTEM =
        "Кратко перескажи этот фрагмент расшифровки на русском: 3–5 пунктов, кто что сказал " +
            "(speaker_N), без выдумок и без markdown."

    const val PRESENTATION_SYSTEM =
        "Ты секретарь конференции. По расшифровке выступлений составь краткий протокол на русском " +
            "строго в формате:\n" +
            "Тема мероприятия: одно предложение.\n" +
            "Выступления:\n- speaker_N: тема выступления и 1–2 главных тезиса (по одному пункту на спикера)\n" +
            "Вопросы и выводы:\n- вывод\n" +
            "Каждый пункт пиши один раз, без повторов и без markdown. " +
            "Используй только факты из расшифровки, ничего не выдумывай."

    const val MUSIC_SYSTEM =
        "По тексту песни (или нескольких песен) кратко опиши на русском: о чём песня, основные " +
            "образы, повторяющийся припев. 3–5 пунктов, без markdown и без выдумок."

    fun summarySystem(profile: Profile) = when (profile) {
        Profile.PODCAST -> SUMMARY_SYSTEM
        Profile.PRESENTATION -> PRESENTATION_SYSTEM
        Profile.MUSIC -> MUSIC_SYSTEM
    }

    /** Splits into at most [parts] consecutive groups of similar size. */
    fun split(utterances: List<Utterance>, parts: Int): List<List<Utterance>> {
        val size = (utterances.size + parts - 1) / parts
        return utterances.chunked(size.coerceAtLeast(1))
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

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

    /** Whole lines up to [maxChars]; when too long, lines are taken evenly across the span. */
    fun dialogText(utterances: List<Utterance>, maxChars: Int): String {
        val all = utterances.map { "${it.speakerId}: ${it.text}" }
        val totalChars = all.sumOf { it.length + 1L }
        val stride = if (totalChars <= maxChars) 1 else ((totalChars + maxChars - 1) / maxChars).toInt()
        val lines = all.filterIndexed { i, _ -> i % stride == 0 }
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
