package org.bitfennec.lime.core.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitfennec.lime.R

/**
 * Atomic download and installation manager for shared AI runtimes (ORT, Sherpa JNI) and offline models.
 * Delegates orchestration and package lifecycle to [AiPackageInstaller].
 */
object AiDownloadManager {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeHwJob: Job? = null

    /**
     * Asynchronously downloads handwriting bundle (shared ORT + PP-OCRv6 trio).
     * Delegates to [AiPackageInstaller.ensure] for transactional safety and single-flight orchestration.
     */
    fun downloadHandwriting(
        context: Context,
        force: Boolean = false,
        onProgress: (percent: Int, speedDesc: String, stepDesc: String) -> Unit,
        onComplete: (success: Boolean, errorMsg: String?) -> Unit,
    ) {
        val appContext = context.applicationContext
        activeHwJob?.cancel()
        activeHwJob = scope.launch {
            val mirrorPref = try {
                org.bitfennec.lime.prefs.AppPrefs.getInstance().handwriting.mirrorSource.getValue()
            } catch (_: Throwable) {
                0
            }

            if (!force && AiPackageInstaller.isPackageReady(appContext, "handwriting")) {
                withContext(Dispatchers.Main) {
                    onProgress(100, "", appContext.getString(R.string.ai_download_hw_ready))
                    onComplete(true, null)
                }
                return@launch
            }

            AiPackageInstaller.prepareDownload(appContext, "handwriting")
            AiDownloadService.start(appContext, "handwriting", mirrorPref)
            val success = AiPackageInstaller.ensure(
                context = appContext,
                packageId = "handwriting",
                mirrorIndex = mirrorPref,
                force = force,
                onProgress = { percent, speedDesc, statusDesc ->
                    scope.launch(Dispatchers.Main) {
                        onProgress(percent, speedDesc, statusDesc)
                    }
                }
            )

            val errorMsg = (AiPackageInstaller.getStatusFlow("handwriting").value
                as? AiPackageInstaller.PackageStatus.Failed)?.error
            AiModuleManager.invalidateCache()
            withContext(Dispatchers.Main) {
                if (success) {
                    onProgress(100, "", appContext.getString(R.string.ai_download_hw_ready))
                    onComplete(true, null)
                } else {
                    onComplete(false, errorMsg ?: appContext.getString(R.string.ai_download_hw_error))
                }
            }
        }
    }

    /**
     * Cancels an ongoing handwriting download.
     */
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
