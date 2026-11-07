package ru.hexteam.stenographer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val SpeakerColors = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF3949AB),
)

fun speakerColor(speakerId: String): Color {
    val index = speakerId.substringAfterLast('_').toIntOrNull()?.minus(1) ?: 0
    return SpeakerColors[index.mod(SpeakerColors.size)]
}

/** Brand gradient of the home screen (deep blue → azure). */
val HomeGradient = Brush.verticalGradient(listOf(Color(0xFF0B3BFF), Color(0xFF1E88FF), Color(0xFF34B3FF)))

val RecordRed = Color(0xFFFF3B30)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A5BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A52),
    secondaryContainer = Color(0xFFE8EEFB),
    surface = Color(0xFFF7F8FC),
    background = Color(0xFFF7F8FC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FA6FF),
    onPrimary = Color(0xFF00256E),
    primaryContainer = Color(0xFF1B3A80),
    secondaryContainer = Color(0xFF26304A),
)

@Composable
fun StenoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
