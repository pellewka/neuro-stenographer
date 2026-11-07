package ru.hexteam.stenographer.ui

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Single MediaPlayer for the history screen, reporting state through [onState]. */
class PlaybackController(
    private val scope: CoroutineScope,
    private val onState: (PlayerState) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var player: MediaPlayer? = null
    private var recordingId: String? = null
    private var ticker: Job? = null

    fun playFrom(id: String, audio: File, positionMs: Long) {
        val mp = player?.takeIf { recordingId == id } ?: open(id, audio) ?: return
        mp.seekTo(positionMs.toInt())
        mp.start()
        startTicker()
    }

    fun toggle(id: String, audio: File) {
        val mp = player
        when {
            mp == null || recordingId != id -> playFrom(id, audio, 0)
            mp.isPlaying -> {
                mp.pause()
                publish()
            }
            else -> {
                mp.start()
                startTicker()
            }
        }
    }

    fun release() {
        ticker?.cancel()
        player?.release()
        player = null
        recordingId = null
        onState(PlayerState())
    }

    private fun open(id: String, audio: File): MediaPlayer? {
        release()
        return runCatching {
            MediaPlayer().apply {
                setDataSource(audio.path)
                prepare()
                setOnCompletionListener { publish() }
            }
        }.onSuccess {
            player = it
            recordingId = id
        }.onFailure { onError("Не удалось воспроизвести аудио") }.getOrNull()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                publish()
                if (player?.isPlaying != true) break
                delay(POLL_MS)
            }
        }
    }

    private fun publish() {
        val mp = player ?: return
        onState(PlayerState(recordingId, mp.isPlaying, mp.currentPosition.toLong()))
    }

    private companion object {
        const val POLL_MS = 200L
    }
}
