package ru.hexteam.stenographer.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** m:ss.t, or h:mm:ss.t for recordings longer than an hour. */
fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val tenths = (ms % 1000) / 100
    val h = totalSec / 3600
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d.%d", h, totalSec / 60 % 60, totalSec % 60, tenths)
    } else {
        String.format(Locale.US, "%d:%02d.%d", totalSec / 60, totalSec % 60, tenths)
    }
}

/** m:ss, or h:mm:ss. */
fun formatDuration(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    val h = totalSec / 3600
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, totalSec / 60 % 60, totalSec % 60)
    } else {
        String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }
}

fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(Date(epochMs))
