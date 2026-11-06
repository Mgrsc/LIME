package org.bitfennec.lime.core.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Atomic download and installation manager for shared AI runtimes (ORT, Sherpa JNI) and offline models.
 * Delegates orchestration and package lifecycle to [AiPackageInstaller].
 */
object AiDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeHwJob: Job? = null

    /**
     * Asynchronously downloads handwriting bundle (shared ORT + PP-OCRv6 trio).
     * Delegates to [AiPackageInstaller.ensure] for transactional safety and single-flight orchestration.
     */
    @Synchronized
    fun downloadHandwriting(
        context: Context,
        force: Boolean = false,
    ) {
        if (activeHwJob?.isActive == true) return
        val appContext = context.applicationContext
        AiPackageInstaller.prepareDownload(appContext, "handwriting")
        val mirrorPref = try {
            org.bitfennec.lime.prefs.AppPrefs.getInstance().handwriting.mirrorSource.getValue()
        } catch (_: Throwable) {
            0
        }
        AiDownloadService.start(appContext, "handwriting", mirrorPref)
        activeHwJob = scope.launch {
            try {
                AiPackageInstaller.ensure(
                    context = appContext,
                    packageId = "handwriting",
                    mirrorIndex = mirrorPref,
                    force = force,
                )
            } finally {
                AiModuleManager.invalidateCache()
            }
        }
    }

    /**
     * Cancels an ongoing handwriting download.
     */
    @Synchronized
    fun cancelDownload(context: Context) {
        activeHwJob?.cancel()
        activeHwJob = null
        AiPackageInstaller.cancel("handwriting")
        AiDownloadService.cancel(context, "handwriting")
    }

    fun isDownloading(packageId: String = "handwriting"): Boolean =
        AiPackageInstaller.getStatusFlow(packageId).value is AiPackageInstaller.PackageStatus.Downloading

    fun getStatusFlow(packageId: String = "handwriting") =
        AiPackageInstaller.getStatusFlow(packageId)
}
