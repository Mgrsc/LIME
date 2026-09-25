package org.bitfennec.lime.core.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bitfennec.lime.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service (dataSync) that owns and protects AI model downloads from process death
 * when IME keyboard is hidden or switched.
 */
class AiDownloadService : Service() {

    companion object {
        private const val TAG = "AiDownloadService"
        const val CHANNEL_ID = "ai_model_download_channel"
        const val NOTIFICATION_ID_BASE = 20260

        const val ACTION_START = "org.bitfennec.lime.action.START_AI_DOWNLOAD"
        const val ACTION_CANCEL = "org.bitfennec.lime.action.CANCEL_AI_DOWNLOAD"

        const val EXTRA_PACKAGE_ID = "extra_package_id"
        const val EXTRA_MIRROR_INDEX = "extra_mirror_index"

        fun getNotificationId(packageId: String): Int =
            NOTIFICATION_ID_BASE + (packageId.hashCode() and 0x7FFF) % 1000

        /**
         * Starts the foreground download service.
         * Automatically ensures [AiPackageInstaller.prepareDownload] is invoked.
         * If status remains Missing for 10s, the service stops automatically.
         */
        fun start(context: Context, packageId: String, mirrorIndex: Int = 0) {
            runCatching {
                AiPackageInstaller.prepareDownload(context, packageId)
                val intent = Intent(context, AiDownloadService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PACKAGE_ID, packageId)
                    putExtra(EXTRA_MIRROR_INDEX, mirrorIndex)
                }
                context.startForegroundService(intent)
            }.onFailure { e ->
                Log.w(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun cancel(context: Context, packageId: String) {
            runCatching {
                val intent = Intent(context, AiDownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_PACKAGE_ID, packageId)
                }
                context.startService(intent)
            }.onFailure { e ->
                Log.w(TAG, "Failed to send cancel to service: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activeTasks = ConcurrentHashMap<String, Job>()
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val packageId = intent?.getStringExtra(EXTRA_PACKAGE_ID) ?: "voice"
        val notifId = getNotificationId(packageId)

        if (action == ACTION_CANCEL) {
            AiPackageInstaller.cancel(packageId)
            activeTasks.remove(packageId)?.cancel()
            notificationManager.cancel(notifId)
            if (activeTasks.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification(
            packageId = packageId,
            content = getString(R.string.ai_download_notif_preparing),
            percent = 0,
            isFinished = false
        )
        val startedFg = startForegroundCompat(notifId, initialNotification)
        if (!startedFg) {
            stopSelf()
            return START_NOT_STICKY
        }

        activeTasks[packageId]?.cancel()
        val job = serviceScope.launch {
            val initialStatus = AiPackageInstaller.getStatusFlow(packageId).value
            if (initialStatus is AiPackageInstaller.PackageStatus.Missing) {
                val started = withTimeoutOrNull(10000L) {
                    AiPackageInstaller.getStatusFlow(packageId)
                        .filter { it !is AiPackageInstaller.PackageStatus.Missing }
                        .first()
                }
                if (started == null) {
                    Log.w(TAG, "Package $packageId remained in Missing state; stopping service")
                    activeTasks.remove(packageId)
                    notificationManager.cancel(notifId)
                    if (activeTasks.isEmpty()) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return@launch
                }
            }

            AiPackageInstaller.getStatusFlow(packageId)
                .collectLatest { status ->
                    when (status) {
                        is AiPackageInstaller.PackageStatus.Downloading -> {
                            val progressText = if (status.stageDesc.isNotEmpty()) {
                                status.stageDesc
                            } else if (status.speedBps > 0) {
                                val speedStr = AiBlobDownloader.formatSpeed(status.speedBps)
                                "${status.artifactName} ($speedStr)"
                            } else {
                                status.artifactName
                            }
                            val notification = buildNotification(packageId, progressText, status.percent, false)
                            notificationManager.notify(notifId, notification)
                        }
                        is AiPackageInstaller.PackageStatus.Ready -> {
                            val notification = buildNotification(
                                packageId = packageId,
                                content = getString(R.string.ai_download_notif_completed),
                                percent = 100,
                                isFinished = true
                            )
                            notificationManager.notify(notifId, notification)
                            activeTasks.remove(packageId)
                            if (activeTasks.isEmpty()) {
                                stopForegroundCompat(detachNotification = true)
                                stopSelf()
                            }
                        }
                        is AiPackageInstaller.PackageStatus.Failed -> {
                            val notification = buildNotification(
                                packageId = packageId,
                                content = status.error,
                                percent = 0,
                                isFinished = true
                            )
                            notificationManager.notify(notifId, notification)
                            activeTasks.remove(packageId)
                            if (activeTasks.isEmpty()) {
                                stopForegroundCompat(detachNotification = true)
                                stopSelf()
                            }
                        }
                        is AiPackageInstaller.PackageStatus.Missing -> {
                            // Do not self-terminate on Missing emission; Missing is the initial unstarted state.
                            // Explicit cancellation is handled via ACTION_CANCEL.
                        }
                    }
                }
        }
        activeTasks[packageId] = job

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(id: Int, notification: Notification): Boolean {
        return runCatching {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            true
        }.getOrElse { e ->
            Log.w(TAG, "Failed to start foreground service: ${e.message}")
            false
        }
    }

    private fun stopForegroundCompat(detachNotification: Boolean = false) {
        runCatching {
            stopForeground(if (detachNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        }.onFailure { e ->
            Log.w(TAG, "Failed to stop foreground service: ${e.message}")
        }
    }

    private fun buildNotification(
        packageId: String,
        content: String,
        percent: Int,
        isFinished: Boolean
    ): Notification {
        val title = if (packageId == "voice") {
            getString(R.string.ai_download_notif_title_voice)
        } else {
            getString(R.string.ai_download_notif_title_handwriting)
        }
        val cancelTitle = getString(R.string.ai_download_notif_cancel)

        val cancelIntent = Intent(this, AiDownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_PACKAGE_ID, packageId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            packageId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_lime_launcher)
            .setOngoing(!isFinished)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!isFinished) {
            if (percent > 0) {
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(100, 0, true)
            }
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelTitle, cancelPendingIntent)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ai_download_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.ai_download_notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
