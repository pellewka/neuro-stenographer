package ru.hexteam.stenographer.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** m:ss.t */
fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val tenths = (ms % 1000) / 100
    return String.format(Locale.US, "%d:%02d.%d", totalSec / 60, totalSec % 60, tenths)
}

/** m:ss */
fun formatDuration(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}

fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(Date(epochMs))
