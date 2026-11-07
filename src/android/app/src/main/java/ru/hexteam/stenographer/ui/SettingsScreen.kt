package ru.hexteam.stenographer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.hexteam.stenographer.asr.AudioType
import ru.hexteam.stenographer.asr.DenoiseMode
import ru.hexteam.stenographer.data.Settings

private val SPEAKER_OPTIONS = listOf(0, 2, 3, 4, 5)

@Composable
fun SettingsScreen(
    settings: Settings,
    enabled: Boolean,
    onChange: ((Settings) -> Settings) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        Section("Распознавание") {
            Text("Число говорящих", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SPEAKER_OPTIONS.forEach { n ->
                    FilterChip(
                        selected = settings.numSpeakers == n,
                        onClick = { onChange { it.copy(numSpeakers = n) } },
                        label = { Text(if (n == 0) "Авто" else n.toString()) },
                        enabled = enabled,
                    )
                }
            }
            Text("Тип записи", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioType.entries.forEach { type ->
                    FilterChip(
                        selected = settings.audioType == type,
                        onClick = { onChange { it.copy(audioType = type) } },
                        label = { Text(audioTypeLabel(type)) },
                        enabled = enabled,
                    )
                }
            }
            Hint("Для песен вокал отделяется от музыки (UVR MDX-Net). «Авто» распознаёт музыку по басам.")
            Text("Шумоподавление (DPDFNet)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DenoiseMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.denoise == mode,
                        onClick = { onChange { it.copy(denoise = mode) } },
                        label = { Text(denoiseLabel(mode)) },
                        enabled = enabled,
                    )
                }
            }
            Hint("«Авто» включает подавление шума только на зашумлённых записях, чистые не трогает.")
            SettingSwitch("Английские термины латиницей", settings.latinAnglicisms, enabled) { v ->
                onChange { it.copy(latinAnglicisms = v) }
            }
            Hint("«гитхаб» → GitHub, «пул-реквест» → pull request, «зум» → Zoom")
        }

        Section("Локальная LLM · Qwen3-1.7B") {
            SettingSwitch("Протокол заседания после расшифровки", settings.llmSummary, enabled = true) { v ->
                onChange { it.copy(llmSummary = v) }
            }
            SettingSwitch("Редактура пунктуации (медленнее)", settings.llmPolish, enabled) { v ->
                onChange { it.copy(llmPolish = v) }
            }
            Hint("Редактура принимается, только если слова не изменились.")
        }

        Hint("Вся обработка выполняется на устройстве. У приложения нет доступа к интернету.")
    }
}

private fun audioTypeLabel(type: AudioType) = when (type) {
    AudioType.AUTO -> "Авто"
    AudioType.SPEECH -> "Речь"
    AudioType.MUSIC -> "Песня"
}

private fun denoiseLabel(mode: DenoiseMode) = when (mode) {
    DenoiseMode.AUTO -> "Авто"
    DenoiseMode.ON -> "Всегда"
    DenoiseMode.OFF -> "Выкл"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
