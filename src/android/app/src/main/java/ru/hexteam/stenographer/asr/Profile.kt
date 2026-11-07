package ru.hexteam.stenographer.asr

/**
 * Processing profiles. Each one picks the models and parameters for its kind of audio.
 *
 * @param maxSpeakers cap on distinct speakers over the whole recording.
 * @param music separate vocals from accompaniment before recognition.
 * @param minTurnSec shorter diarization turns are treated as noise (sequential speakers).
 * @param maxUtteranceSec long monologues are split at sentence ends after this length.
 * @param blockSec long audio is processed in blocks of this length to bound memory.
 * @param minDurationOffSec diarization: pauses shorter than this do not end a turn.
 */
enum class Profile(
    val title: String,
    val description: String,
    val maxSpeakers: Int,
    val music: Boolean,
    val minTurnSec: Float,
    val maxUtteranceSec: Float,
    val blockSec: Int,
    val minDurationOffSec: Float,
) {
    PODCAST(
        title = "Подкаст",
        description = "Разговор до 5 человек · от 30 мин до 6 ч",
        maxSpeakers = 5,
        music = false,
        minTurnSec = 0f,
        maxUtteranceSec = 60f,
        blockSec = 600,
        minDurationOffSec = 0.4f,
    ),
    MUSIC(
        title = "Музыка",
        description = "Песни и альбомы · вокал отделяется от музыки",
        maxSpeakers = 4,
        music = true,
        minTurnSec = 0f,
        maxUtteranceSec = 30f,
        blockSec = 120,
        minDurationOffSec = 0.5f,
    ),
    PRESENTATION(
        title = "Презентация",
        description = "Спикеры выступают по очереди · от 2 до 8 ч",
        maxSpeakers = 30,
        music = false,
        minTurnSec = 3f,
        maxUtteranceSec = 30f,
        blockSec = 600,
        minDurationOffSec = 1.5f,
    ),
}
