package ru.hexteam.stenographer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hexteam.stenographer.data.Recording

private val BOTTOM_BAR_SPACE = 96.dp

@Composable
fun AppScreen(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var exportTarget by remember { mutableStateOf<Recording?>(null) }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importAudio(uris)
    }
    // Progress of hours-long jobs is shown in a notification (Android 13+ asks for permission).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val recording = exportTarget
        if (uri != null && recording != null) viewModel.exportJson(recording.id, uri)
        exportTarget = null
    }
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
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = BOTTOM_BAR_SPACE)) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val listPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BOTTOM_BAR_SPACE + 24.dp)
            when (state.tab) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    onRecord = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted || state.home is HomeState.Recording) viewModel.toggleRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onImport = { pickAudio.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) },
                    onDone = viewModel::acknowledgeDone,
                    onProfile = viewModel::selectProfile,
                )
                Tab.HISTORY -> HistoryScreen(
                    state = state,
                    actions = HistoryActions(
                        onQuery = viewModel::setQuery,
                        onToggleExpand = viewModel::toggleExpanded,
                        onTogglePlay = { viewModel.togglePlayback(it) },
                        onSeek = { id, ms -> viewModel.playFrom(id, ms) },
                        onExport = { recording ->
                            exportTarget = recording
                            exportJson.launch("${recording.title}.json")
                        },
                        onSummarize = viewModel::summarize,
                        onDelete = viewModel::delete,
                    ),
                    contentPadding = listPadding,
                    modifier = Modifier.statusBarsPadding(),
                )
                Tab.SETTINGS -> SettingsScreen(
                    settings = state.settings,
                    enabled = state.activeJob == null,
                    onChange = viewModel::updateSettings,
                    contentPadding = listPadding,
                    modifier = Modifier.statusBarsPadding(),
                )
            }
            BottomBar(
                current = state.tab,
                unseen = state.unseenCount,
                onSelect = viewModel::selectTab,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            )
        }
    }
}

/** Floating pill bar from the sketch: history (search) · home · settings. */
@Composable
private fun BottomBar(current: Tab, unseen: Int, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(32.dp), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarItem(Icons.Default.Search, "История записей", current == Tab.HISTORY, badge = unseen) { onSelect(Tab.HISTORY) }
            BarItem(Icons.Default.Home, "Главный экран", current == Tab.HOME) { onSelect(Tab.HOME) }
            BarItem(Icons.Default.Settings, "Настройки", current == Tab.SETTINGS) { onSelect(Tab.SETTINGS) }
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(horizontal = 8.dp).size(56.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(badge = { if (badge > 0) Badge { Text("+$badge") } }) {
                Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
