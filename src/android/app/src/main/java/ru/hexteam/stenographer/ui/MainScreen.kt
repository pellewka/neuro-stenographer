package ru.hexteam.stenographer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hexteam.stenographer.model.Utterance
import java.util.Locale

private val SPEAKER_OPTIONS = listOf(0, 2, 3, 4, 5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onAudioPicked(it, displayName(context, it)) }
    }
    val exportJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportJson) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.toggleRecording() else viewModel.showMessage("Нет доступа к микрофону")
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Нейро-стенографист") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SetupBanner(state.setup) }
            item {
                ControlsCard(
                    state = state,
                    onImport = { pickAudio.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) },
                    onRecord = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted || state.isRecording) viewModel.toggleRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSettings = viewModel::updateSettings,
                )
            }
            state.processing?.let { item { ProcessingCard(it) } }
            if (state.utterances.isNotEmpty()) {
                item {
                    ResultHeader(
                        state = state,
                        onExport = {
                            val base = state.audio?.name?.substringBeforeLast('.') ?: "transcript"
                            exportJson.launch("$base.json")
                        },
                        onTogglePlay = viewModel::togglePlayback,
                    )
                }
                item { SummaryCard(state.summary, onGenerate = viewModel::generateSummary) }
                itemsIndexed(state.utterances, key = { i, _ -> i }) { _, u ->
                    val active = state.isPlaying && state.positionMs in u.startMs..u.endMs
                    UtteranceRow(u, active) { viewModel.playFrom(u.startMs) }
                }
            }
        }
    }
}

@Composable
private fun SetupBanner(setup: SetupState) {
    when (setup) {
        SetupState.Ready -> Unit
        is SetupState.Preparing -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(setup.title, style = MaterialTheme.typography.titleSmall)
                val fraction = setup.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is SetupState.Failed -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text("Ошибка загрузки моделей: ${setup.message}", Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun ControlsCard(
    state: UiState,
    onImport: () -> Unit,
    onRecord: () -> Unit,
    onSettings: ((Settings) -> Settings) -> Unit,
) {
    val ready = state.setup is SetupState.Ready && state.processing == null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = ready && !state.isRecording, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Импорт аудио")
                }
                FilledTonalButton(
                    onClick = onRecord,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                    colors = if (state.isRecording) ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ) else ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(if (state.isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isRecording) "Стоп" else "Записать")
                }
            }

            Text("Число говорящих", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SPEAKER_OPTIONS.forEach { n ->
                    FilterChip(
                        selected = state.settings.numSpeakers == n,
                        onClick = { onSettings { it.copy(numSpeakers = n) } },
                        label = { Text(if (n == 0) "Авто" else n.toString()) },
                        enabled = state.processing == null,
                    )
                }
            }
            SettingSwitch("Протокол заседания (LLM)", state.settings.llmSummary) { v ->
                onSettings { it.copy(llmSummary = v) }
            }
            SettingSwitch("Редактура пунктуации (LLM, медленнее)", state.settings.llmPolish) { v ->
                onSettings { it.copy(llmPolish = v) }
            }
            Text(
                "Вся обработка выполняется на устройстве, без интернета.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProcessingCard(processing: Processing) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val fraction = processing.fraction
            val percent = fraction?.let { " — ${(it * 100).toInt()}%" } ?: ""
            Text(processing.title + percent, style = MaterialTheme.typography.titleSmall)
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultHeader(state: UiState, onExport: () -> Unit, onTogglePlay: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.audio?.name ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            state.stats?.let { s ->
                val speakers = state.utterances.map { it.speakerId }.distinct().size
                Text(
                    "Длительность ${formatTime(s.audioMs)} · обработка ${formatTime(s.processingMs)} · " +
                        "RTF ${String.format(Locale.US, "%.2f", s.rtf)} · говорящих: $speakers · реплик: ${state.utterances.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onExport) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Экспорт JSON")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Пауза" else "Воспроизвести",
                    )
                }
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: SummaryState, onGenerate: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Протокол (Qwen3-1.7B, локально)", style = MaterialTheme.typography.titleSmall)
            when (summary) {
                SummaryState.Idle -> TextButton(onClick = onGenerate) { Text("Сформировать протокол") }
                SummaryState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).width(20.dp), strokeWidth = 2.dp)
                    Text("LLM составляет протокол…", style = MaterialTheme.typography.bodyMedium)
                }
                is SummaryState.Done -> Text(summary.text, style = MaterialTheme.typography.bodyMedium)
                is SummaryState.Failed -> {
                    Text("Ошибка LLM: ${summary.message}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onGenerate) { Text("Повторить") }
                }
            }
        }
    }
}

@Composable
private fun UtteranceRow(u: Utterance, active: Boolean, onClick: () -> Unit) {
    val color = speakerColor(u.speakerId)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.speakerId, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${formatTime(u.startMs)} – ${formatTime(u.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(u.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val tenths = (ms % 1000) / 100
    return String.format(Locale.US, "%d:%02d.%d", totalSec / 60, totalSec % 60, tenths)
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "audio"
