package ru.hexteam.stenographer.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.hexteam.stenographer.MainActivity
import ru.hexteam.stenographer.StenoApp

/**
 * Foreground service that keeps the process (and CPU, via a wake lock) alive while
 * the queue is processed, showing progress in a notification. Hours-long recordings
 * would otherwise be killed as soon as the user leaves the app.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // ServiceCompat masks out types it does not know (mediaProcessing), so call the platform API.
        startForeground(NOTIFICATION_ID, notification("Подготовка…", null), type)
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stenographer:processing")
                .apply { acquire(MAX_WAKE_MS) }
        }
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val engine = (application as StenoApp).engine
        val manager = getSystemService(NotificationManager::class.java)
        engine.state.map { Triple(it.activeJob, it.queued, it.isBusy) }.distinctUntilChanged().collectLatest { (job, queued, busy) ->
            if (!busy) {
                stopSelf()
                return@collectLatest
            }
            val text = buildString {
                append(job?.title ?: "В очереди")
                job?.fraction?.let { append(" · ${(it * 100).toInt()}%") }
                if (queued > 0) append(" · ещё в очереди: $queued")
            }
            manager.notify(NOTIFICATION_ID, notification(text, job?.fraction))
        }
    }

    private fun notification(text: String, fraction: Float?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Нейро-стенографист: расшифровка")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Обработка записей", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 1

        /** Upper bound for the wake lock: longest supported recording plus margin. */
        private const val MAX_WAKE_MS = 12L * 60 * 60 * 1000

        fun intent(context: Context) = Intent(context, ProcessingService::class.java)
    }
}
