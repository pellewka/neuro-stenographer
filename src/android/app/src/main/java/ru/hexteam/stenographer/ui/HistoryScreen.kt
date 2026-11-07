package ru.hexteam.stenographer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.hexteam.stenographer.data.Recording
import ru.hexteam.stenographer.data.RecordingStatus
import ru.hexteam.stenographer.model.Utterance
import java.util.Locale

class HistoryActions(
    val onQuery: (String) -> Unit,
    val onToggleExpand: (String) -> Unit,
    val onTogglePlay: (String) -> Unit,
    val onSeek: (String, Long) -> Unit,
    val onExport: (Recording) -> Unit,
    val onSummarize: (String) -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
fun HistoryScreen(state: UiState, actions: HistoryActions, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "История записей",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск по названию и тексту") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }
        val items = state.filteredRecordings
        if (items.isEmpty()) {
            item {
                Text(
                    if (state.recordings.isEmpty()) "Пока нет записей. Нажмите большую кнопку на главном экране." else "Ничего не найдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
        items(items, key = { it.id }) { recording ->
            RecordingCard(
                recording = recording,
                expanded = state.expandedId == recording.id,
                job = state.activeJob?.takeIf { it.recordingId == recording.id },
                player = state.player.takeIf { it.recordingId == recording.id },
                summarizing = state.summarizingId == recording.id,
                actions = actions,
                onDelete = { pendingDelete = recording },
            )
        }
    }

    pendingDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text("«${recording.title}» и её расшифровка будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(recording.id)
                    pendingDelete = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RecordingCard(
    recording: Recording,
    expanded: Boolean,
    job: ActiveJob?,
    player: PlayerState?,
    summarizing: Boolean,
    actions: HistoryActions,
    onDelete: () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { actions.onToggleExpand(recording.id) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(recording.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle(recording, job),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recording.status == RecordingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ExpandMore, contentDescription = if (expanded) "Свернуть" else "Показать текст", modifier = Modifier.rotate(rotation))
        }
        job?.let {
            val fraction = it.fraction
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.size(12.dp))
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            ExpandedContent(recording, player, summarizing, processing = job != null, actions, onDelete)
        }
    }
}

private fun subtitle(recording: Recording, job: ActiveJob?): String = when {
    job != null -> job.title + (job.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
    recording.status == RecordingStatus.FAILED -> recording.error ?: "Ошибка"
    else -> buildString {
        append(formatDate(recording.createdAt))
        append(" · ").append(formatDuration(recording.durationMs))
        if (recording.speakerCount > 0) append(" · говорящих: ").append(recording.speakerCount)
    }
}

@Composable
private fun ExpandedContent(
    recording: Recording,
    player: PlayerState?,
    summarizing: Boolean,
    processing: Boolean,
    actions: HistoryActions,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { actions.onTogglePlay(recording.id) }) {
                val playing = player?.isPlaying == true
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Пауза" else "Воспроизвести")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatTime(player?.positionMs ?: 0)} / ${formatDuration(recording.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete, enabled = !processing) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить запись")
            }
        }
        if (recording.status == RecordingStatus.DONE) {
            Text(
                String.format(Locale.US, "Обработка %s · RTF %.2f%s", formatTime(recording.processingMs), recording.rtf,
                    if (recording.denoised) " · шумоподавление" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SummaryBlock(recording, summarizing) { actions.onSummarize(recording.id) }
            recording.utterances.forEach { u ->
                val active = player?.isPlaying == true && player.positionMs in u.startMs..u.endMs
                UtteranceRow(u, active) { actions.onSeek(recording.id, u.startMs) }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FilledIconButton(onClick = { actions.onExport(recording) }, enabled = recording.utterances.isNotEmpty()) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Скачать JSON")
                }
            }
        }
    }
}

@Composable
private fun SummaryBlock(recording: Recording, summarizing: Boolean, onSummarize: () -> Unit) {
    if (recording.utterances.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Протокол · Qwen3 локально", style = MaterialTheme.typography.labelLarge)
        }
        val summary = recording.summary
        when {
            summarizing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("LLM составляет протокол…", style = MaterialTheme.typography.bodySmall)
            }
            summary != null -> Text(summary, style = MaterialTheme.typography.bodyMedium)
            else -> TextButton(onClick = onSummarize) { Text("Сформировать протокол") }
        }
    }
}

@Composable
private fun UtteranceRow(u: Utterance, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(speakerColor(u.speakerId)))
        Spacer(Modifier.width(8.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.speakerId, color = speakerColor(u.speakerId), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text("${formatTime(u.startMs)} – ${formatTime(u.endMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(u.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
