package org.bitfennec.lime.core.runtime

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitfennec.lime.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified package installer and lifecycle manager.
 *
 * Enforces:
 * - D1: Single package graph (AiPackageSpec), DAG dependency resolution.
 * - D2: Immutable blob storage, atomic version activation via MANIFEST + current.
 * - Single-flight downloads: Concurrently requested packages/blobs share in-flight tasks.
 * - Uninstall: Safely cleans both version pointer and package-exclusive blobs.
 * - Complete cancellation: aborts network transfers and stops active background tasks.
 */
object AiPackageInstaller {

    private const val TAG = "AiPackageInstaller"

    sealed class PackageStatus {
        object Missing : PackageStatus()
        data class Downloading(
            val artifactName: String,
            val bytesRead: Long,
            val totalBytes: Long,
            val speedBps: Long,
            val percent: Int,
            val stageDesc: String = ""
        ) : PackageStatus()
        object Ready : PackageStatus()
        /** Complete user-facing message; consumers must display it without another failure prefix. */
        data class Failed(val error: String) : PackageStatus()
    }

    private val installerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packageStatusMap = ConcurrentHashMap<String, MutableStateFlow<PackageStatus>>()
    private val activePackageJobs = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val activeBlobJobs = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val blobUsers = ConcurrentHashMap<String, MutableSet<String>>()

    internal class DownloadProgress(artifacts: List<AiPackageSpec.Artifact>) {
        private val expectedBytes = artifacts.map { it.downloadArtifact }.distinctBy { it.sha256 }
            .associate { it.sha256 to if (it.expectedBytes > 0) it.expectedBytes else it.minExpectedBytes }
        private val pendingArtifacts = artifacts.groupBy { it.downloadArtifact.sha256 }
            .mapValues { (_, consumers) -> consumers.map { it.sha256 }.toMutableSet() }
        private val sourceBytes = HashMap<String, Long>()

        val totalBytes: Long = expectedBytes.values.sum()
        val bytesRead: Long
            get() = sourceBytes.values.sum()
        val percent: Int
            get() = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 99) else 0

        fun update(artifact: AiPackageSpec.Artifact, bytes: Long) {
            val source = artifact.downloadArtifact.sha256
            val received = bytes.coerceIn(0, expectedBytes.getValue(source))
            sourceBytes[source] = maxOf(sourceBytes[source] ?: 0L, received)
        }

        fun complete(artifact: AiPackageSpec.Artifact) {
            val source = artifact.downloadArtifact.sha256
            val pending = pendingArtifacts.getValue(source)
            pending.remove(artifact.sha256)
            if (pending.isEmpty()) sourceBytes[source] = expectedBytes.getValue(source)
        }
    }

    fun getStatusFlow(packageId: String): StateFlow<PackageStatus> {
        return packageStatusMap.computeIfAbsent(packageId) {
            MutableStateFlow(PackageStatus.Missing)
        }.asStateFlow()
    }

    @VisibleForTesting
    internal fun updateStatus(packageId: String, status: PackageStatus) {
        packageStatusMap.computeIfAbsent(packageId) {
            MutableStateFlow(status)
        }.value = status
    }

    private fun getStringSafe(context: Context, @StringRes resId: Int, fallback: String = ""): String = try {
        context.getString(resId)
    } catch (_: Exception) {
        fallback
    }

    private fun resolveErrorMessage(context: Context, e: Exception): String {
        val isNetwork = e is java.net.SocketException ||
                e is java.net.UnknownHostException ||
                e is java.net.SocketTimeoutException ||
                e is java.io.InterruptedIOException ||
                (e.cause is java.net.SocketException) ||
                (e.cause is java.net.UnknownHostException) ||
                (e.cause is java.net.SocketTimeoutException) ||
                (e.cause is java.io.InterruptedIOException)
        return if (isNetwork) {
            getStringSafe(context, R.string.ai_download_error_network, fallback = "Network error, please retry")
        } else if (e is java.io.IOException) {
            getStringSafe(context, R.string.ai_download_error_storage, fallback = "Storage error, please retry")
        } else {
            getStringSafe(context, R.string.ai_download_error_generic, fallback = "Download failed, please retry")
        }
    }

    /**
     * Synchronously prepares memory state for a package download before asynchronous tasks start,
     * preventing UI observers from reading stale terminal states (e.g. Failed) during race windows.
     *
     * Performs no disk readiness checks. The caller must continue to [ensure] on IO,
     * even when the previous memory state was Ready.
     */
    fun prepareDownload(context: Context, packageId: String) =
        prepareDownload(context, packageId, AiPackageSpec.ALL_PACKAGES)

    private fun prepareDownload(context: Context, packageId: String, packages: Map<String, AiPackageSpec.Package>) {
        val appContext = context.applicationContext
        // Repeated preparation must preserve progress already published by this attempt.
        if (getStatusFlow(packageId).value is PackageStatus.Downloading) return
        val existing = activePackageJobs[packageId]
        if (existing != null && existing.isActive) return

        val pkgSpec = packages[packageId]
        val initialName = pkgSpec?.getDisplayName(appContext) ?: packageId
        val stageDesc = getStringSafe(appContext, R.string.ai_download_notif_preparing)
        updateStatus(packageId, PackageStatus.Downloading(
            artifactName = initialName,
            bytesRead = 0L,
            totalBytes = 0L,
            speedBps = 0L,
            percent = 0,
            stageDesc = stageDesc
        ))
    }

    /**
     * Cancels an ongoing package download, aborting all package-exclusive active network transfers.
     */
    fun cancel(packageId: String): Boolean = synchronized(activePackageJobs) {
        // Retain the cancelled job until completion so a retry can join its cleanup.
        val packageJob = activePackageJobs[packageId]
        val wasActive = packageJob?.isActive == true
        packageJob?.cancel()
        synchronized(activeBlobJobs) {
            for ((sha, users) in blobUsers) {
                users.remove(packageId)
                if (users.isEmpty()) {
                    activeBlobJobs[sha]?.cancel()
                }
            }
        }
        updateStatus(packageId, PackageStatus.Missing)
        wasActive
    }

    /**
     * Uninstalls package, deleting its active pointer and package-exclusive blob files.
     */
    fun uninstallPackage(context: Context, packageId: String): Boolean {
        cancel(packageId)
        val appContext = context.applicationContext
        val pkg = AiPackageSpec.getPackage(packageId)
        if (pkg != null) {
            val otherActivePackages = AiPackageSpec.ALL_PACKAGES.values.filter { other ->
                other.id != packageId && isPackageReady(appContext, other.id)
            }
            val otherArtifactSha = otherActivePackages.flatMap { other ->
                AiPackageSpec.resolveDependencies(other.id).flatMap { p -> p.artifacts }
            }.flatMap { listOfNotNull(it, it.archiveSource?.archive) }.map { it.sha256 }.toSet()

            for (artifact in pkg.artifacts.flatMap { listOfNotNull(it, it.archiveSource?.archive) }.distinctBy { it.sha256 }) {
                synchronized(activeBlobJobs) {
                    val usedByDownload = blobUsers[artifact.sha256]?.any { it != packageId } == true
                    if (!otherArtifactSha.contains(artifact.sha256) && !usedByDownload) {
                        AiBlobStore.deleteBlob(appContext, artifact.sha256, artifact.fileName)
                    }
                }
            }
        }
        AiBlobStore.deletePackage(appContext, packageId)
        updateStatus(packageId, PackageStatus.Missing)
        return true
    }

    /**
     * Checks active versions, manifests and blob sizes for a package and its dependencies.
     * Does not verify hashes. Only initializes Missing state; preserves download outcomes.
     */
    fun isPackageReady(context: Context, packageId: String): Boolean =
        isPackageReady(context, packageId, AiPackageSpec.ALL_PACKAGES)

    private fun isPackageReady(context: Context, packageId: String, packages: Map<String, AiPackageSpec.Package>): Boolean {
        val pkg = packages[packageId] ?: return false

        // Check dependencies first: a package cannot be ready if any dependency is not ready
        val allDepsReady = pkg.dependencies.all { isPackageReady(context, it, packages) }
        if (!allDepsReady) {
            return false
        }

        // Check active version pointer in AiBlobStore
        val activeVersion = AiBlobStore.getActiveVersion(context, packageId)
        if (activeVersion == pkg.version) {
            val versionDir = AiBlobStore.getPackageVersionDir(context, packageId, pkg.version)
            val manifestFile = File(versionDir, AiBlobStore.MANIFEST_FILE)
            if (manifestFile.isFile) {
                val allBlobsPresent = pkg.artifacts.all { artifact ->
                    AiBlobStore.hasBlob(
                        context = context,
                        sha256 = artifact.sha256,
                        expectedBytes = artifact.expectedBytes,
                        minBytes = artifact.minExpectedBytes,
                        fileName = artifact.fileName
                    )
                }
                if (allBlobsPresent) {
                    // A passive disk probe must not overwrite an attempt started since the probe began.
                    packageStatusMap.computeIfAbsent(packageId) {
                        MutableStateFlow(PackageStatus.Missing)
                    }.compareAndSet(PackageStatus.Missing, PackageStatus.Ready)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Ensures package and all its dependencies are downloaded, verified, and activated.
     * Guaranteed single-flight with shared progress observation.
     */
    suspend fun ensure(
        context: Context,
        packageId: String,
        mirrorIndex: Int = 0,
        force: Boolean = false,
        onProgress: ((percent: Int, speedDesc: String, statusDesc: String) -> Unit)? = null,
    ): Boolean = ensure(context, packageId, AiPackageSpec.ALL_PACKAGES, mirrorIndex, force, onProgress)

    @VisibleForTesting
    internal suspend fun ensure(
        context: Context,
        packageId: String,
        packages: Map<String, AiPackageSpec.Package>,
        mirrorIndex: Int = 0,
        force: Boolean = false,
        onProgress: ((percent: Int, speedDesc: String, statusDesc: String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val packageSnapshot = packages.toMap()

        val deferred: Deferred<Boolean>?
        try {
            deferred = synchronized(activePackageJobs) {
                val existing = activePackageJobs[packageId]
                if (existing != null && !existing.isCompleted) {
                    existing
                } else if (existing == null && !force && isPackageReady(appContext, packageId, packageSnapshot)) {
                    updateStatus(packageId, PackageStatus.Ready)
                    null
                } else {
                    prepareDownload(appContext, packageId, packageSnapshot)
                    val newJob = installerScope.async(start = CoroutineStart.LAZY) {
                        currentCoroutineContext().ensureActive()
                        executePackageDownload(appContext, packageId, mirrorIndex, packageSnapshot)
                    }
                    activePackageJobs[packageId] = newJob
                    newJob.invokeOnCompletion {
                        synchronized(activePackageJobs) {
                            activePackageJobs.remove(packageId, newJob)
                        }
                    }
                    newJob.start()
                    newJob
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize package download for $packageId: ${e.message}", e)
            updateStatus(packageId, PackageStatus.Failed(resolveErrorMessage(appContext, e)))
            return@withContext false
        }

        if (deferred == null) {
            onProgress?.invoke(100, "", appContext.getString(R.string.voice_status_ready))
            return@withContext true
        }

        if (deferred.isCancelled) {
            // Do not register a replacement until cancelled work has finished blob cleanup.
            deferred.join()
            return@withContext ensure(appContext, packageId, packageSnapshot, mirrorIndex, force, onProgress)
        }

        // Forward StateFlow progress to caller's callback so multiple callers don't lose progress
        val progressJob = if (onProgress != null) {
            installerScope.launch {
                getStatusFlow(packageId).collect { status ->
                    when (status) {
                        is PackageStatus.Downloading -> {
                            val speedDesc = if (status.stageDesc.isNotEmpty()) {
                                ""
                            } else {
                                AiBlobDownloader.formatSpeed(status.speedBps)
                            }
                            val statusText = if (status.stageDesc.isNotEmpty()) status.stageDesc else status.artifactName
                            onProgress.invoke(status.percent, speedDesc, statusText)
                        }
                        is PackageStatus.Ready -> onProgress.invoke(100, "", appContext.getString(R.string.voice_status_ready))
                        else -> Unit
                    }
                }
            }
        } else null

        return@withContext try {
            deferred.await()
        } finally {
            // A caller owns only its progress subscription, not the shared download.
            progressJob?.cancel()
        }
    }

    private suspend fun executePackageDownload(
        appContext: Context,
        packageId: String,
        mirrorIndex: Int,
        packages: Map<String, AiPackageSpec.Package>,
    ): Boolean {
        val owner = currentCoroutineContext()[Job]!!
        fun publish(status: PackageStatus) {
            synchronized(activePackageJobs) {
                if (activePackageJobs[packageId] === owner && owner.isActive) {
                    updateStatus(packageId, status)
                }
            }
        }
        try {
            val pkgSpec = packages[packageId]
            val initialName = pkgSpec?.getDisplayName(appContext) ?: packageId
            publish(PackageStatus.Downloading(
                artifactName = initialName,
                bytesRead = 0L,
                totalBytes = 0L,
                speedBps = 0L,
                percent = 0,
                stageDesc = getStringSafe(appContext, R.string.ai_download_notif_preparing)
            ))

            val topoPackages = AiPackageSpec.resolveDependencies(packageId, packages)
            val progress = DownloadProgress(topoPackages.flatMap { it.artifacts })

            fun publishProgress(name: String, stageDesc: String = "", speedBps: Long = 0L) {
                publish(PackageStatus.Downloading(
                    artifactName = name,
                    bytesRead = progress.bytesRead,
                    totalBytes = progress.totalBytes,
                    speedBps = speedBps,
                    percent = progress.percent,
                    stageDesc = stageDesc,
                ))
            }

            for (pkg in topoPackages) {
                for (artifact in pkg.artifacts) {
                    val targetBlobFile = AiBlobStore.getBlobFile(appContext, artifact.sha256, artifact.fileName)
                    val partialBlobFile = AiBlobStore.getPartialFile(appContext, artifact.sha256)

                    targetBlobFile.parentFile?.mkdirs()
                    partialBlobFile.parentFile?.mkdirs()

                    val artifactDisplayName = artifact.getDisplayName(appContext)
                    val downloadDisplayName = artifact.downloadArtifact.getDisplayName(appContext)

                    val effectiveMirrorIndex = AiPackageSpec.resolveMirrorIndex(packageId, pkg.id, mirrorIndex)
                    val blobSuccess = downloadBlobSingleFlight(
                        packageId = packageId,
                        artifact = artifact,
                        targetBlobFile = targetBlobFile,
                        partialBlobFile = partialBlobFile,
                        mirrorIndex = effectiveMirrorIndex,
                        modelRepoDirectory = if (AiPackageSpec.isVoicePackage(pkg.id)) pkg.version else null,
                        blobsDir = AiBlobStore.getBlobsDir(appContext),
                        onStage = { stage, host ->
                            val stageDesc = when (stage) {
                                AiBlobDownloader.Stage.PROBING -> appContext.getString(R.string.ai_download_probing)
                                AiBlobDownloader.Stage.CONNECTING -> {
                                    if (host.isNotEmpty()) {
                                        "${appContext.getString(R.string.ai_download_connecting)} ($host)"
                                    } else {
                                        appContext.getString(R.string.ai_download_connecting)
                                    }
                                }
                            }
                            publishProgress(downloadDisplayName, stageDesc = stageDesc)
                        }
                    ) { bytesRead, _, speedBps ->
                        progress.update(artifact, bytesRead)
                        publishProgress(downloadDisplayName, speedBps = speedBps)
                    }

                    if (!blobSuccess) {
                        Log.e(TAG, "Failed downloading artifact ${artifact.fileName}")
                        val template = getStringSafe(appContext, R.string.ai_download_error_artifact, fallback = "Unable to download %s")
                        val failMsg = try {
                            template.format(artifactDisplayName)
                        } catch (_: Exception) {
                            "$template $artifactDisplayName"
                        }
                        publish(PackageStatus.Failed(failMsg))
                        return false
                    }

                    progress.complete(artifact)
                    publishProgress(artifactDisplayName)
                }

                // Activate package atomically once its dependencies and artifacts are ready
                synchronized(activePackageJobs) {
                    owner.ensureActive()
                    activatePackage(appContext, pkg)
                }
            }

            publish(PackageStatus.Ready)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring package $packageId: ${e.message}", e)
            publish(PackageStatus.Failed(resolveErrorMessage(appContext, e)))
            return false
        }
    }

    @VisibleForTesting
    internal suspend fun downloadBlobSingleFlight(
        packageId: String,
        artifact: AiPackageSpec.Artifact,
        targetBlobFile: File,
        partialBlobFile: File,
        mirrorIndex: Int,
        modelRepoDirectory: String?,
        blobsDir: File? = null,
        onStage: ((stage: AiBlobDownloader.Stage, detail: String) -> Unit)? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long, speedBps: Long) -> Unit)?
    ): Boolean {
        if (targetBlobFile.isFile &&
            (artifact.expectedBytes <= 0 || targetBlobFile.length() == artifact.expectedBytes) &&
            AiDownloadGuard.verifySha256(targetBlobFile, artifact.sha256) &&
            (artifact.kind != AiPackageSpec.ArtifactKind.ELF_SO || AiRuntimeLoader.isElf16KbAligned(targetBlobFile))
        ) {
            return true
        }

        lateinit var deferred: Deferred<Boolean>
        while (true) {
            currentCoroutineContext().ensureActive()
            var jobToJoin: Deferred<Boolean>? = null
            synchronized(activeBlobJobs) {
                val existing = activeBlobJobs[artifact.sha256]
                if (existing != null && existing.isActive) {
                    blobUsers.computeIfAbsent(artifact.sha256) { ConcurrentHashMap.newKeySet() }.add(packageId)
                    deferred = existing
                } else if (existing != null && !existing.isCompleted) {
                    jobToJoin = existing
                } else {
                    blobUsers.computeIfAbsent(artifact.sha256) { ConcurrentHashMap.newKeySet() }.add(packageId)
                    val newJob = installerScope.async {
                        val source = artifact.archiveSource
                        if (source == null) {
                            AiBlobDownloader.downloadBlob(
                                artifact = artifact,
                                targetFile = targetBlobFile,
                                partialFile = partialBlobFile,
                                allowedPrefixes = artifact.pinnedUrls,
                                mirrorIndex = mirrorIndex,
                                modelRepoDirectory = modelRepoDirectory,
                                onProgress = onProgress,
                                onStage = onStage
                            )
                        } else {
                            val directory = requireNotNull(blobsDir)
                            val archiveFile = AiBlobStore.getBlobFile(directory, source.archive.sha256, source.archive.fileName)
                            val downloaded = downloadBlobSingleFlight(
                                packageId = "artifact:${artifact.sha256}",
                                artifact = source.archive,
                                targetBlobFile = archiveFile,
                                partialBlobFile = AiBlobStore.getPartialFile(directory, source.archive.sha256),
                                mirrorIndex = mirrorIndex,
                                modelRepoDirectory = null,
                                blobsDir = directory,
                                onProgress = onProgress,
                                onStage = onStage,
                            )
                            downloaded && AiRuntimeArchive.extract(artifact, archiveFile, targetBlobFile, partialBlobFile)
                        }
                    }
                    activeBlobJobs[artifact.sha256] = newJob
                    deferred = newJob
                }
            }
            if (jobToJoin != null) {
                jobToJoin.join()
            } else {
                break
            }
        }

        return try {
            deferred.await()
        } finally {
            val lastUser = synchronized(activeBlobJobs) {
                val users = blobUsers[artifact.sha256]
                users?.remove(packageId)
                if (users.isNullOrEmpty()) {
                    blobUsers.remove(artifact.sha256)
                    if (!deferred.isCompleted) deferred.cancel()
                    true
                } else {
                    false
                }
            }
            if (lastUser) {
                // The package must not finish cancellation while its blob writer is still exiting.
                withContext(NonCancellable) { deferred.join() }
                synchronized(activeBlobJobs) {
                    activeBlobJobs.remove(artifact.sha256, deferred)
                }
            }
        }
    }

    /**
     * Atomically activates a package by writing MANIFEST and swapping the current version pointer.
     */
    fun activatePackage(context: Context, pkg: AiPackageSpec.Package) {
        val versionDir = AiBlobStore.getPackageVersionDir(context, pkg.id, pkg.version)
        if (!versionDir.exists()) versionDir.mkdirs()
        val manifestFile = File(versionDir, AiBlobStore.MANIFEST_FILE)

        // Write MANIFEST (artifactName sha256)
        val manifestContent = pkg.artifacts.joinToString("\n") { "${it.fileName} ${it.sha256}" }
        FileOutputStream(manifestFile).use { out ->
            out.write(manifestContent.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }

        // Atomic swap of current version pointer
        val pkgDir = AiBlobStore.getPackageDir(context, pkg.id)
        if (!pkgDir.exists()) pkgDir.mkdirs()
        val currentFile = AiBlobStore.getPackageCurrentFile(context, pkg.id)
        val tmpCurrentFile = File(pkgDir, "${AiBlobStore.CURRENT_FILE}.tmp")
        FileOutputStream(tmpCurrentFile).use { out ->
            out.write(pkg.version.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }

        if (currentFile.exists()) currentFile.delete()
        if (!tmpCurrentFile.renameTo(currentFile)) {
            tmpCurrentFile.copyTo(currentFile, overwrite = true)
            tmpCurrentFile.delete()
        }
        Log.i(TAG, "Activated package ${pkg.id} at version ${pkg.version}")
    }
}
