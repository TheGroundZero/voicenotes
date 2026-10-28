// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface DownloadState {
    data class Downloading(val done: Long, val total: Long) : DownloadState
    data object Verifying : DownloadState
    /** [openSettings]: the fix is in the system's app settings (e.g. the network permission is off). */
    data class Failed(val message: String, val openSettings: Boolean = false) : DownloadState
}

/** Progress of model downloads, by model id; an absent entry means nothing is happening. */
object ModelDownloads {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    val isActive: Boolean get() = _states.value.values.any { it !is DownloadState.Failed }

    internal fun set(id: String, state: DownloadState?) = _states.update { if (state == null) it - id else it + (id to state) }
}

/** Keeps a download running when the app is in the background. One download at a time. */
class ModelDownloadService : Service() {
    @Volatile private var cancelled = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelled = true
            ACTION_START -> start(intent.getStringExtra(EXTRA_MODEL))
        }
        return START_NOT_STICKY
    }

    private fun start(modelId: String?) {
        val spec = modelId?.let(ModelCatalog::downloadable)
        // startForegroundService() obliges us to call startForeground() even when we refuse the job.
        ServiceCompat.startForeground(this, NOTIF_ID, notification(spec?.title ?: "model", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (spec == null || worker?.isAlive == true) {
            if (worker?.isAlive != true) shutDown()
            return
        }
        refusal(spec)?.let {
            ModelDownloads.set(spec.id, it)
            shutDown()
            return
        }
        cancelled = false
        ModelDownloads.set(spec.id, DownloadState.Downloading(0, spec.sizeBytes))
        worker = Thread {
            val result = downloadOne(spec)
            // The speech check is small and every model needs it: fetch it right after a model, unless it is there already.
            if (result == ModelDownloader.Result.Done && spec.id != ModelCatalog.speechCheck.id && !SpeechGate.isInstalled(this)) {
                ModelDownloads.set(ModelCatalog.speechCheck.id, DownloadState.Downloading(0, ModelCatalog.speechCheck.sizeBytes))
                downloadOne(ModelCatalog.speechCheck)
            }
            shutDown()
        }.also { it.start() }
    }

    /** Downloads [spec] and reports its progress under its own id. */
    private fun downloadOne(spec: ModelSpec): ModelDownloader.Result {
        var lastUpdate = 0L
        val result = ModelDownloader(this).download(
            spec,
            onProgress = { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 500) {
                    lastUpdate = now
                    if (done < total) {
                        ModelDownloads.set(spec.id, DownloadState.Downloading(done, total))
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(spec.title, done, total))
                    } else {
                        ModelDownloads.set(spec.id, DownloadState.Verifying)
                    }
                }
            },
            isCancelled = { cancelled },
        )
        ModelDownloads.set(
            spec.id,
            when (result) {
                ModelDownloader.Result.Done, ModelDownloader.Result.Cancelled -> null
                is ModelDownloader.Result.Failed -> DownloadState.Failed(result.message)
            },
        )
        return result
    }

    /** A reason not to download right now, or null. Large files (10 MB and up) stay off mobile data unless the user allows it. */
    private fun refusal(spec: ModelSpec): DownloadState.Failed? {
        val cm = getSystemService(ConnectivityManager::class.java)
        // With the network permission switched off per app (GrapheneOS), the app sees no network at
        // all, and checkSelfPermission() still says granted, so "offline" and "blocked" look the same.
        if (cm.activeNetwork == null || checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            return DownloadState.Failed(
                "No network connection, or ${getString(R.string.app_name)} is not allowed to use the network. If you are online, turn on Permissions → Network in the app settings, then retry.",
                openSettings = true,
            )
        }
        if (cm.isActiveNetworkMetered && spec.sizeBytes >= SMALL_DOWNLOAD_BYTES && !Settings.allowMobileData(this)) {
            return DownloadState.Failed("Not on Wi-Fi. Connect to Wi-Fi, or allow downloads over mobile data on this screen")
        }
        return null
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(title: String, done: Long, total: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_downloading, title))
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total == 0L)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_cancel), cancel).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_START = "be.sequr.voicenotes.DOWNLOAD_MODEL"
        private const val ACTION_CANCEL = "be.sequr.voicenotes.CANCEL_DOWNLOAD"
        private const val EXTRA_MODEL = "model"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 2
        private const val SMALL_DOWNLOAD_BYTES = 10_000_000L // below this (the speech check) mobile data is fine

        fun start(context: Context, modelId: String) {
            ModelDownloads.set(modelId, DownloadState.Downloading(0, ModelCatalog.downloadable(modelId)?.sizeBytes ?: 0))
            context.startForegroundService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_MODEL, modelId))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
