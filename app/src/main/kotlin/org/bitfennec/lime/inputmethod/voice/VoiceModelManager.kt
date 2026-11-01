package org.bitfennec.lime.inputmethod.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitfennec.lime.inputmethod.ImeDispatchers
import org.bitfennec.lime.core.runtime.AiDownloadService
import org.bitfennec.lime.core.runtime.AiModuleManager
import org.bitfennec.lime.core.runtime.AiPackageInstaller
import org.bitfennec.lime.core.runtime.AiPackageSpec
import org.bitfennec.lime.prefs.AppPrefs

object VoiceModelManager {

    private val packageId = AiPackageSpec.voicePackage.id
    private var activeDownloadJob: Job? = null

    @Volatile
    private var cachedIsReady = false

    fun invalidateCache() {
        AiModuleManager.invalidateCache()
    }

    internal fun invalidateReadyCache() {
        cachedIsReady = false
    }

    fun isModelReady(context: Context): Boolean {
        if (cachedIsReady) return true
        val ready = AiPackageInstaller.isPackageReady(context, packageId)
        if (ready) cachedIsReady = true
        return ready
    }

    fun isDownloading(): Boolean =
        getStatusFlow().value is AiPackageInstaller.PackageStatus.Downloading

    fun getStatusFlow() = AiPackageInstaller.getStatusFlow(packageId)

    @Synchronized
    fun startDownload(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!force && isModelReady(appContext)) {
            return
        }
        AiPackageInstaller.prepareDownload(appContext, packageId)

        if (activeDownloadJob?.isActive != true) {
            val mirrorPref = try {
                AppPrefs.getInstance().voice.mirrorSource.getValue()
            } catch (_: Throwable) {
                0
            }
            AiDownloadService.start(appContext, packageId, mirrorPref)
            activeDownloadJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    AiPackageInstaller.ensure(
                        context = appContext,
                        packageId = packageId,
                        mirrorIndex = mirrorPref,
                        force = force
                    )
                } finally {
                    invalidateCache()
                }
            }
        }
    }

    @Synchronized
    fun cancelDownload(context: Context) {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        AiPackageInstaller.cancel(packageId)
        AiDownloadService.cancel(context, packageId)
    }

    suspend fun deleteModel(context: Context): Boolean {
        synchronized(VoiceRecognitionEngine) {
            if (VoiceRecognitionEngine.hasActiveLease()) return false
            VoiceRecognitionEngine.release()
        }
        val appContext = context.applicationContext
        return withContext(ImeDispatchers.asrDispatcher) {
            synchronized(VoiceRecognitionEngine) {
                if (VoiceRecognitionEngine.hasActiveLease()) return@withContext false
                VoiceRecognitionEngine.release()
                cancelDownload(appContext)
                AiPackageInstaller.uninstallPackage(appContext, packageId).also {
                    invalidateCache()
                }
            }
        }
    }
}
