package ru.hexteam.stenographer.data

import android.content.Context
import ru.hexteam.stenographer.asr.AudioType
import ru.hexteam.stenographer.asr.DenoiseMode

data class Settings(
    /** 0 = detect automatically. */
    val numSpeakers: Int = 0,
    val audioType: AudioType = AudioType.AUTO,
    val denoise: DenoiseMode = DenoiseMode.AUTO,
    val latinAnglicisms: Boolean = true,
    val llmSummary: Boolean = true,
    val llmPolish: Boolean = false,
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        numSpeakers = prefs.getInt(SPEAKERS, 0),
        audioType = runCatching { AudioType.valueOf(prefs.getString(AUDIO_TYPE, null) ?: "") }.getOrDefault(AudioType.AUTO),
        denoise = runCatching { DenoiseMode.valueOf(prefs.getString(DENOISE, null) ?: "") }.getOrDefault(DenoiseMode.AUTO),
        latinAnglicisms = prefs.getBoolean(ANGLICISMS, true),
        llmSummary = prefs.getBoolean(SUMMARY, true),
        llmPolish = prefs.getBoolean(POLISH, false),
    )

    fun save(settings: Settings) = prefs.edit()
        .putInt(SPEAKERS, settings.numSpeakers)
        .putString(AUDIO_TYPE, settings.audioType.name)
        .putString(DENOISE, settings.denoise.name)
        .putBoolean(ANGLICISMS, settings.latinAnglicisms)
        .putBoolean(SUMMARY, settings.llmSummary)
        .putBoolean(POLISH, settings.llmPolish)
        .apply()

    private companion object {
        const val SPEAKERS = "speakers"
        const val AUDIO_TYPE = "audio_type"
        const val DENOISE = "denoise"
        const val ANGLICISMS = "anglicisms"
        const val SUMMARY = "summary"
        const val POLISH = "polish"
    }
}
