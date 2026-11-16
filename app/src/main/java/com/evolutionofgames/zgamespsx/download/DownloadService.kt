package com.evolutionofgames.zgamespsx.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.evolutionofgames.zgamespsx.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps downloads alive while the app is in the background and shows
 * progress in the notification shade. It watches the process-wide
 * [DownloadEngine] and stops itself as soon as nothing is running.
 * Also posts a small "finished" / "failed" notification per download.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val lastStatus = HashMap<Long, DownloadStatus>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = DownloadEngine.get(this)
        createChannels()
        // startForegroundService() obliges us to call startForeground() within seconds,
        // for EVERY start command (including the Pause action). If we don't, Android
        // kills the whole app with "did not then call Service.startForeground()".
        val running0 = engine.items.value.filter { it.status == DownloadStatus.RUNNING }
        if (!enterForeground(progressNotification(running0))) {
            // Could not become a foreground service (permission / OS restriction).
            // Downloads keep running inside the app process while it is open; we only
            // lose the background notification. NEVER crash because of it.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE_ALL) {
            running0.forEach { engine.pauseDownload(it.id) }
        }
        if (job == null) {
            job = scope.launch {
                engine.items.collect { items ->
                    for (it in items) {
                        val prev = lastStatus.put(it.id, it.status)
                        if (prev != null && prev != DownloadStatus.COMPLETED && it.status == DownloadStatus.COMPLETED) {
                            com.evolutionofgames.zgamespsx.analytics.UsageAnalytics.event("download_completed")
                            notifyResult(it, ok = true)
                        } else if (prev == DownloadStatus.RUNNING && it.status == DownloadStatus.FAILED) {
                            notifyResult(it, ok = false)
                        }
                    }
                    val running = items.filter { it.status == DownloadStatus.RUNNING }
                    if (running.isEmpty()) {
                        job = null
                        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
                        stopSelf()
                    } else {
                        try { nm().notify(PROGRESS_ID, progressNotification(running)) } catch (_: Throwable) { }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 limits dataSync foreground services to ~6 h per day and then calls this.
     * We must stop within a few seconds or the system raises a fatal exception. Downloads
     * are paused (their .part files are kept) so the user can resume them.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        try {
            val engine = DownloadEngine.get(this)
            engine.items.value.filter { it.status == DownloadStatus.RUNNING }.forEach { engine.pauseDownload(it.id) }
        } catch (_: Throwable) { }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        stopSelf()
    }

    /** Goes foreground with the dataSync type; falls back to the untyped call. True on success. */
    private fun enterForeground(notification: android.app.Notification): Boolean {
        try {
            ServiceCompat.startForeground(
                this, PROGRESS_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            return true
        } catch (_: Throwable) { }
        return try {
            startForeground(PROGRESS_ID, notification)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val m = nm()
        m.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        m.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(running: List<DownloadItem>): android.app.Notification {
        val first = running.firstOrNull()
        val title = when {
            first == null -> "Downloads"
            running.size == 1 -> first.title
            else -> "${running.size} downloads"
        }
        val pct = if (running.size == 1) first?.progressPercent ?: 0
        else {
            val totals = running.filter { it.totalBytes > 0 }
            val t = totals.sumOf { it.totalBytes }
            if (t > 0) ((totals.sumOf { it.bytesDownloaded } * 100) / t).toInt().coerceIn(0, 100) else 0
        }
        val speed = running.sumOf { it.speedBytesPerSec }
        val text = buildString {
            append(if (first != null && first.totalBytes <= 0 && running.size == 1) "Downloading…" else "$pct%")
            if (speed > 0) append(" · ").append(formatSpeed(speed))
        }
        val pause = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct, first != null && first.totalBytes <= 0 && running.size == 1)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Pause", pause)
            .build()
    }

    private fun notifyResult(item: DownloadItem, ok: Boolean) {
        try {
            val n = NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "Download finished" else "Download failed")
                .setContentText(if (ok) item.title else "${item.title} — open the app to retry")
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            nm().notify(1000 + (item.id % 100000).toInt(), n)
        } catch (_: Throwable) {
        }
    }

    private fun formatSpeed(bps: Long): String =
        if (bps >= 1_048_576) String.format("%.1f MB/s", bps / 1_048_576.0) else "${bps / 1024} KB/s"

    companion object {
        private const val CH_PROGRESS = "downloads_progress"
        private const val CH_DONE = "downloads_done"
        private const val PROGRESS_ID = 4242
        private const val ACTION_PAUSE_ALL = "com.evolutionofgames.zgamespsx.PAUSE_ALL"

        /** No-op: foreground dataSync service removed for Play compliance; downloads run in-app. */
        fun start(context: Context) {
        }
    }
}
