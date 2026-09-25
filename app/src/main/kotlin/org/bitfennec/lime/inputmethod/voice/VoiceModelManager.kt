package org.bitfennec.lime.inputmethod.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bitfennec.lime.core.runtime.AiBlobStore
import org.bitfennec.lime.core.runtime.AiDownloadService
import org.bitfennec.lime.core.runtime.AiModuleManager
import org.bitfennec.lime.core.runtime.AiPackageInstaller
import org.bitfennec.lime.core.runtime.AiPackageSpec
import org.bitfennec.lime.prefs.AppPrefs
import java.io.File

/**
 * Offline SenseVoice model management and downloader.
 * Unified on top of AiPackageInstaller and AiBlobStore.
 */
object VoiceModelManager {

    private const val MODEL_FILENAME = AiPackageSpec.VOICE_MODEL_NAME
    private const val TOKENS_FILENAME = AiPackageSpec.VOICE_TOKENS_NAME

    private var activeDownloadJob: Job? = null

    @Volatile
    private var cachedIsReady: Boolean? = null

    fun invalidateCache() {
        cachedIsReady = null
        AiModuleManager.invalidateCache()
    }

    fun getModelFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.voiceModelArtifact.sha256, MODEL_FILENAME)

    fun getTokensFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.voiceTokensArtifact.sha256, TOKENS_FILENAME)

    /** Checks local model integrity (cached in memory for 0ms response). */
    fun isModelReady(context: Context): Boolean {
        cachedIsReady?.let { return it }
        val ready = AiPackageInstaller.isPackageReady(context, "voice")
        // Missing files can become ready before the download caller invalidates caches.
        if (ready) cachedIsReady = true
        return ready
    }

    fun isDownloading(): Boolean =
        AiPackageInstaller.getStatusFlow("voice").value is AiPackageInstaller.PackageStatus.Downloading

    fun getStatusFlow() = AiPackageInstaller.getStatusFlow("voice")

    /** Asynchronously downloads model with unified installer orchestration and foreground protection. */
    @Synchronized
    fun startDownload(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!force && isModelReady(appContext)) {
            return
        }
        AiPackageInstaller.prepareDownload(appContext, "voice")

        // Start background download job if not already active
        if (activeDownloadJob == null || !activeDownloadJob!!.isActive) {
            val mirrorPref = try {
                AppPrefs.getInstance().voice.mirrorSource.getValue()
            } catch (_: Throwable) {
                0
            }
            AiDownloadService.start(appContext, "voice", mirrorPref)
            activeDownloadJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    AiPackageInstaller.ensure(
                        context = appContext,
                        packageId = "voice",
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
        AiPackageInstaller.cancel("voice")
        AiDownloadService.cancel(context, "voice")
    }

    fun deleteModel(context: Context): Boolean {
        cancelDownload(context)
        return AiPackageInstaller.uninstallPackage(context, "voice").also {
            invalidateCache()
        }
    }
}
