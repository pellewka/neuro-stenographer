package ru.hexteam.stenographer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hexteam.stenographer.asr.Profile

private val BIG_BUTTON = 200.dp

@Composable
fun HomeScreen(
    state: UiState,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onDone: () -> Unit,
    onProfile: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(HomeGradient)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfilePicker(state.settings.profile, enabled = state.home == HomeState.Idle, onProfile)
            Spacer(Modifier.height(28.dp))
            Text(headline(state), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            AnimatedContent(
                targetState = state.home is HomeState.Done,
                transitionSpec = { (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn()) togetherWith fadeOut() },
                label = "home-button",
            ) { done ->
                if (done) DoneBadge() else BigButton(state, onRecord)
            }
            Spacer(Modifier.height(32.dp))
            when (val home = state.home) {
                is HomeState.Done -> Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
                ) { Text("ОК", fontWeight = FontWeight.Bold) }
                is HomeState.Recording -> Text(formatTime(home.elapsedMs), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
                is HomeState.Processing -> ProcessingLabel(home, state.queued)
                HomeState.Idle -> ImportButton(enabled = state.canStart, onClick = onImport)
            }
            SetupHint(state.setup)
        }
    }
}

private fun headline(state: UiState): String = when (val home = state.home) {
    HomeState.Idle -> if (state.setup is SetupState.Ready) "Нажмите, чтобы начать запись" else "Подготовка…"
    is HomeState.Recording -> "Идёт запись"
    is HomeState.Processing -> "Расшифровываю…"
    is HomeState.Done -> if (state.recordings.firstOrNull { it.id == home.recordingId }?.error != null) {
        "Не удалось обработать"
    } else {
        "Готово! Запись в истории"
    }
}

@Composable
private fun BigButton(state: UiState, onClick: () -> Unit) {
    val home = state.home
    val recording = home is HomeState.Recording
    val processing = home is HomeState.Processing
    val enabled = recording || state.canStart

    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val spin by pulse.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "spin",
    )
    val level by animateFloatAsState((home as? HomeState.Recording)?.level ?: 0f, tween(120), label = "level")

    Box(Modifier.size(BIG_BUTTON + 80.dp), contentAlignment = Alignment.Center) {
        // Sound-reactive rings while recording, soft breathing rings when idle.
        Canvas(Modifier.fillMaxSize()) {
            val base = BIG_BUTTON.toPx() / 2
            val extra = if (recording) 20.dp.toPx() + level * 40.dp.toPx() else (breathe - 1f) * base * 4
            drawCircle(Color.White.copy(alpha = 0.12f), radius = base + extra)
            drawCircle(Color.White.copy(alpha = 0.08f), radius = base + extra * 1.8f)
            if (processing) {
                val fraction = (home as HomeState.Processing).fraction
                val inset = 6.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(size.width - 2 * inset - 60.dp.toPx(), size.height - 2 * inset - 60.dp.toPx())
                val topLeft = androidx.compose.ui.geometry.Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
                val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                if (fraction != null) {
                    drawArc(Color.White, -90f, 360f * fraction, false, topLeft, arcSize, style = stroke)
                } else {
                    drawArc(Color.White, spin, 90f, false, topLeft, arcSize, style = stroke)
                }
            }
        }
        Box(
            Modifier
                .size(BIG_BUTTON)
                .scale(if (state.home == HomeState.Idle && enabled) breathe else 1f)
                .clip(CircleShape)
                .background(if (recording) RecordRed else Color.White)
                .border(4.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = if (recording) "Остановить запись" else "Начать запись" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (recording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = null,
                tint = if (recording) Color.White else if (enabled) MaterialTheme.colorScheme.primary else Color.LightGray,
                modifier = Modifier.size(96.dp),
            )
        }
    }
}

@Composable
private fun DoneBadge() {
    Box(Modifier.size(BIG_BUTTON + 80.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(BIG_BUTTON).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = "Готово", tint = Color(0xFF34C759), modifier = Modifier.size(120.dp))
        }
    }
}

@Composable
private fun ImportButton(enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) 0.22f else 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = "Импортировать аудиофайл" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.FileDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("Импорт файла", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

@Composable
private fun ProcessingLabel(home: HomeState.Processing, queued: Int) {
    val percent = home.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(home.title + percent, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        if (queued > 0) Text("ещё в очереди: $queued", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Text("Можно свернуть приложение - обработка продолжится", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/** Processing profile: picks models and parameters for the kind of audio. */
@Composable
private fun ProfilePicker(selected: Profile, enabled: Boolean, onSelect: (Profile) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.18f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Profile.entries.forEach { profile ->
                val active = profile == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) Color.White else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(profile) }
                        .semantics { contentDescription = "Профиль: ${profile.title}" }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        profile.title,
                        color = if (active) MaterialTheme.colorScheme.primary else Color.White,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(selected.description, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SetupHint(setup: SetupState) {
    when (setup) {
        SetupState.Ready -> Unit
        is SetupState.Preparing -> Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(setup.title, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            val fraction = setup.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
            }
        }
        is SetupState.Failed -> Text(
            "Ошибка загрузки моделей: ${setup.message}",
            color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 32.dp),
        )
    }
}
