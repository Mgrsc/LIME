package org.bitfennec.lime.core.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiPackageInstallerTest {

    private fun createMockContext(tempDir: File): android.content.Context =
        object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): android.content.Context = this
        }

    @After
    fun tearDown() {
        (AiPackageSpec.ALL_PACKAGES.keys + "test_unready_pkg").forEach {
            AiPackageInstaller.cancel(it)
        }
    }

    @Test
    fun testPackageActivationAndCurrentPointer() {
        val tempAiDir = File.createTempFile("test_ai_inst", "").apply {
            delete()
            mkdirs()
        }
        try {
            // Mock Context.filesDir
            val mockContext = createMockContext(tempAiDir)

            val pkg = AiPackageSpec.ortPackage
            AiPackageInstaller.activatePackage(mockContext, pkg)

            val activeVer = AiBlobStore.getActiveVersion(mockContext, pkg.id)
            assertEquals("Active version must match package version", pkg.version, activeVer)

            val manifest = File(AiBlobStore.getPackageVersionDir(mockContext, pkg.id, pkg.version), AiBlobStore.MANIFEST_FILE)
            assertTrue("Manifest must exist", manifest.isFile)
            val lines = manifest.readLines()
            assertTrue("Manifest must contain artifact entry", lines.any { it.startsWith(pkg.artifacts.first().fileName) })
        } finally {
            tempAiDir.deleteRecursively()
        }
    }

    @Test
    fun testPackageStatusFlowInitialMissing() {
        val flow = AiPackageInstaller.getStatusFlow("voice")
        assertNotNull(flow)
        assertTrue("Initial status must be Missing or Ready", flow.value is AiPackageInstaller.PackageStatus.Missing || flow.value is AiPackageInstaller.PackageStatus.Ready)
    }

    @Test
    fun testEnsureReturnsTrueIfAlreadyReady() = kotlinx.coroutines.runBlocking {
        val tempAiDir = File.createTempFile("test_ai_inst2", "").apply {
            delete()
            mkdirs()
        }
        try {
            val mockContext = createMockContext(tempAiDir)

            // Create fake blob for ort satisfying minExpectedBytes gate
            val ortArtifact = AiPackageSpec.ortArtifact
            val blob = AiBlobStore.getBlobFile(mockContext, ortArtifact.sha256, ortArtifact.fileName)
            blob.parentFile?.mkdirs()
            java.io.RandomAccessFile(blob, "rw").use { raf ->
                val targetSize = if (ortArtifact.expectedBytes > 0) ortArtifact.expectedBytes else (ortArtifact.minExpectedBytes + 1024L)
                raf.setLength(targetSize)
            }

            // Activate ort package
            AiPackageInstaller.activatePackage(mockContext, AiPackageSpec.ortPackage)

            val success = AiPackageInstaller.ensure(mockContext, "ort")
            assertTrue("Ensure should return true when blobs and manifest are intact", success)
        } finally {
            tempAiDir.deleteRecursively()
        }
    }

    @Test
    fun testUninstallPackageAndCleanup() {
        val tempAiDir = File.createTempFile("test_ai_inst3", "").apply {
            delete()
            mkdirs()
        }
        try {
            val mockContext = createMockContext(tempAiDir)

            val pkg = AiPackageSpec.voicePackage
            AiPackageInstaller.activatePackage(mockContext, pkg)

            // Create fake blob for voiceModelArtifact
            val modelArtifact = AiPackageSpec.voiceModelArtifact
            val blob = AiBlobStore.getBlobFile(mockContext, modelArtifact.sha256, modelArtifact.fileName)
            blob.parentFile?.mkdirs()
            blob.writeText("fake-voice-model")
            assertTrue("Blob file must exist before uninstall", blob.isFile)

            val activeVerBefore = AiBlobStore.getActiveVersion(mockContext, pkg.id)
            assertEquals(pkg.version, activeVerBefore)

            // Perform uninstall
            val uninstalled = AiPackageInstaller.uninstallPackage(mockContext, pkg.id)
            assertTrue("uninstallPackage must return true", uninstalled)

            // Verify active version is removed and blob is deleted
            val activeVerAfter = AiBlobStore.getActiveVersion(mockContext, pkg.id)
            org.junit.Assert.assertNull("Active version pointer must be deleted after uninstall", activeVerAfter)
            assertFalse("Blob file must be deleted after uninstall", blob.exists())
            assertFalse("isPackageReady must be false after uninstall", AiPackageInstaller.isPackageReady(mockContext, pkg.id))
        } finally {
            tempAiDir.deleteRecursively()
        }
    }

    @Test
    fun testCancelReturnsFalseWhenNoJob() {
        val cancelled = AiPackageInstaller.cancel("non_existent_pkg")
        assertFalse("Cancel should return false when no active job", cancelled)
    }

    @Test
    fun testCancelStatusRemainsMissing() {
        AiPackageInstaller.cancel("handwriting")
        val status = AiPackageInstaller.getStatusFlow("handwriting").value
        assertTrue("Status after cancel must be Missing", status is AiPackageInstaller.PackageStatus.Missing)
    }

    @Test
    fun testPrepareDownloadTransitionsToDownloadingWhenNotReady() {
        val tempAiDir = File.createTempFile("test_ai_inst_prep", "").apply {
            delete()
            mkdirs()
        }
        try {
            val mockContext = createMockContext(tempAiDir)

            val pkgId = "test_unready_pkg"
            assertFalse(AiPackageInstaller.isPackageReady(mockContext, pkgId))
            AiPackageInstaller.prepareDownload(mockContext, pkgId)
            val status = AiPackageInstaller.getStatusFlow(pkgId).value
            assertTrue("Status must be Downloading after prepareDownload on unready package", status is AiPackageInstaller.PackageStatus.Downloading)
        } finally {
            tempAiDir.deleteRecursively()
        }
    }

    @Test
    fun testPrepareDownloadSchedulesValidationWhenAlreadyReady() {
        val tempAiDir = File.createTempFile("test_ai_inst_ready", "").apply {
            delete()
            mkdirs()
        }
        try {
            val mockContext = createMockContext(tempAiDir)

            val ortArtifact = AiPackageSpec.ortArtifact
            val blob = AiBlobStore.getBlobFile(mockContext, ortArtifact.sha256, ortArtifact.fileName)
            blob.parentFile?.mkdirs()
            java.io.RandomAccessFile(blob, "rw").use { raf ->
                val targetSize = if (ortArtifact.expectedBytes > 0) ortArtifact.expectedBytes else (ortArtifact.minExpectedBytes + 1024L)
                raf.setLength(targetSize)
            }
            AiPackageInstaller.activatePackage(mockContext, AiPackageSpec.ortPackage)
            assertTrue(AiPackageInstaller.isPackageReady(mockContext, "ort"))

            AiPackageInstaller.prepareDownload(mockContext, "ort")
            val status = AiPackageInstaller.getStatusFlow("ort").value
            assertTrue("Ready memory state must still schedule validation", status is AiPackageInstaller.PackageStatus.Downloading)

            // Repeated preparation must preserve the pending download state
            AiPackageInstaller.prepareDownload(mockContext, "ort")
            val statusForce = AiPackageInstaller.getStatusFlow("ort").value
            assertTrue("Status must remain Downloading after repeated preparation", statusForce is AiPackageInstaller.PackageStatus.Downloading)
        } finally {
            tempAiDir.deleteRecursively()
        }
    }

    @Test
    fun testPassiveReadyCheckDoesNotOverwriteInFlightDownloadingStatus() {
        val tempAiDir = File.createTempFile("test_ai_m1", "").apply {
            delete()
            mkdirs()
        }
        try {
            val mockContext = createMockContext(tempAiDir)

            val ortArtifact = AiPackageSpec.ortArtifact
            val blob = AiBlobStore.getBlobFile(mockContext, ortArtifact.sha256, ortArtifact.fileName)
            blob.parentFile?.mkdirs()
            java.io.RandomAccessFile(blob, "rw").use { raf ->
                val targetSize = if (ortArtifact.expectedBytes > 0) ortArtifact.expectedBytes else (ortArtifact.minExpectedBytes + 1024L)
                raf.setLength(targetSize)
            }
            AiPackageInstaller.activatePackage(mockContext, AiPackageSpec.ortPackage)
            assertTrue(AiPackageInstaller.isPackageReady(mockContext, "ort"))

            // Prepare download to set state to Downloading
            AiPackageInstaller.prepareDownload(mockContext, "ort")
            val downloadingStatus = AiPackageInstaller.getStatusFlow("ort").value
            assertTrue("Status must be Downloading", downloadingStatus is AiPackageInstaller.PackageStatus.Downloading)

            // Calling isPackageReady while downloading should still return true (files exist)
            // but must NOT overwrite the Downloading status in statusFlow
            val readyCheck = AiPackageInstaller.isPackageReady(mockContext, "ort")
            assertTrue("isPackageReady returns true since blobs exist", readyCheck)
            val statusAfterCheck = AiPackageInstaller.getStatusFlow("ort").value
            assertTrue("Status must remain Downloading and not be overwritten", statusAfterCheck is AiPackageInstaller.PackageStatus.Downloading)
        } finally {
            tempAiDir.deleteRecursively()
        }
    }

    @Test
    fun testPrepareDownloadWhenStatusIsReadyButDiskFilesAreMissing() {
        val tempAiDir = File.createTempFile("test_ai_l1", "").apply {
            delete()
            mkdirs()
        }
        try {
            val mockContext = createMockContext(tempAiDir)

            val ortArtifact = AiPackageSpec.ortArtifact
            val blob = AiBlobStore.getBlobFile(mockContext, ortArtifact.sha256, ortArtifact.fileName)
            blob.parentFile?.mkdirs()
            java.io.RandomAccessFile(blob, "rw").use { raf ->
                val targetSize = if (ortArtifact.expectedBytes > 0) ortArtifact.expectedBytes else (ortArtifact.minExpectedBytes + 1024L)
                raf.setLength(targetSize)
            }
            AiPackageInstaller.activatePackage(mockContext, AiPackageSpec.ortPackage)
            assertTrue(AiPackageInstaller.isPackageReady(mockContext, "ort"))
            assertTrue(AiPackageInstaller.getStatusFlow("ort").value is AiPackageInstaller.PackageStatus.Ready)

            // Now simulate disk file missing
            blob.delete()
            assertFalse(AiPackageInstaller.isPackageReady(mockContext, "ort"))

            // Preparation must schedule validation regardless of stale Ready memory state.
            AiPackageInstaller.prepareDownload(mockContext, "ort")
            val status = AiPackageInstaller.getStatusFlow("ort").value
            assertTrue("Status must be Downloading", status is AiPackageInstaller.PackageStatus.Downloading)
        } finally {
            tempAiDir.deleteRecursively()
        }
    }
    @Test
    fun testPassiveProbePreservesFailureAndRepeatedPreparationPreservesProgress() {
        val tempAiDir = File.createTempFile("test_ai_probe", "").apply {
            delete()
            mkdirs()
        }
        try {
            val context = createMockContext(tempAiDir)
            val artifact = AiPackageSpec.ortArtifact
            val blob = AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName)
            blob.parentFile?.mkdirs()
            java.io.RandomAccessFile(blob, "rw").use {
                val targetSize = if (artifact.expectedBytes > 0) artifact.expectedBytes else (artifact.minExpectedBytes + 1024L)
                it.setLength(targetSize)
            }
            AiPackageInstaller.activatePackage(context, AiPackageSpec.ortPackage)

            // Inject attempt states without network access or a production-only testing API.
            val failure = AiPackageInstaller.PackageStatus.Failed("Storage error")
            AiPackageInstaller.updateStatus("ort", failure)
            assertTrue(AiPackageInstaller.isPackageReady(context, "ort"))
            assertEquals(failure, AiPackageInstaller.getStatusFlow("ort").value)

            AiPackageInstaller.prepareDownload(context, "ort")
            val progress = AiPackageInstaller.PackageStatus.Downloading("ORT", 50, 100, 10, 50)
            AiPackageInstaller.updateStatus("ort", progress)
            AiPackageInstaller.prepareDownload(context, "ort")
            assertTrue(AiPackageInstaller.isPackageReady(context, "ort"))
            assertEquals(progress, AiPackageInstaller.getStatusFlow("ort").value)
        } finally {
            tempAiDir.deleteRecursively()
        }
    }

    @Test
    fun testPrepareDownloadDoesNotReadDiskFromReadyState() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = error("Preparation must not access filesDir")
        }
        AiPackageInstaller.updateStatus("ort", AiPackageInstaller.PackageStatus.Ready)
        AiPackageInstaller.prepareDownload(context, "ort")
        assertTrue(AiPackageInstaller.getStatusFlow("ort").value is AiPackageInstaller.PackageStatus.Downloading)
    }

    @Test
    fun testCancellingWaiterPreservesSharedDownloadAndStatus() = kotlinx.coroutines.runBlocking {
        val tempDir = File.createTempFile("ai_waiter", "").apply { delete(); mkdirs() }
        val context = createMockContext(tempDir)
        val packageId = "test_unready_pkg"
        val shared = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val field = AiPackageInstaller::class.java.getDeclaredField("activePackageJobs").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(AiPackageInstaller) as java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Boolean>>
        try {
            AiPackageInstaller.prepareDownload(context, packageId)
            jobs[packageId] = shared
            val observed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val waiter = async {
                AiPackageInstaller.ensure(context, packageId, onProgress = { _, _, _ -> observed.complete(Unit) })
            }
            kotlinx.coroutines.withTimeout(5000) { observed.await() }
            waiter.cancelAndJoin()
            assertTrue("Cancelling a waiter must preserve the shared job", shared.isActive)
            org.junit.Assert.assertSame(shared, jobs[packageId])
            assertTrue(AiPackageInstaller.getStatusFlow(packageId).value is AiPackageInstaller.PackageStatus.Downloading)

            val nextObserved = kotlinx.coroutines.CompletableDeferred<Unit>()
            val nextWaiter = async {
                AiPackageInstaller.ensure(context, packageId, onProgress = { _, _, _ -> nextObserved.complete(Unit) })
            }
            kotlinx.coroutines.withTimeout(5000) { nextObserved.await() }
            org.junit.Assert.assertSame(shared, jobs[packageId])
            shared.complete(true)
            assertTrue(kotlinx.coroutines.withTimeout(5000) { nextWaiter.await() })
        } finally {
            jobs.remove(packageId, shared)
            shared.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCancellingBlobJobWaitsBeforeNewDownloadStarts() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(10000) {
            val tempDir = File.createTempFile("ai_cancelling_blob", "").apply { delete(); mkdirs() }
            val targetFile = File(tempDir, "target.bin")
            val partialFile = File(tempDir, "target.bin.part")
            val artifact = AiPackageSpec.ortArtifact

            val field = AiPackageInstaller::class.java.getDeclaredField("activeBlobJobs").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val blobJobs = field.get(AiPackageInstaller) as java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Boolean>>

            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val suspendedInCleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
            val canFinishCleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cancellingJob = scope.async {
                try {
                    started.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        suspendedInCleanup.complete(Unit)
                        canFinishCleanup.await()
                    }
                }
            }
            started.await()
            cancellingJob.cancel()
            suspendedInCleanup.await()
            assertTrue("Job must be cancelling", !cancellingJob.isActive && !cancellingJob.isCompleted)

            blobJobs[artifact.sha256] = cancellingJob

            try {
                val attempt = scope.async {
                    AiPackageInstaller.downloadBlobSingleFlight(
                        packageId = "test_pkg",
                        artifact = artifact,
                        targetBlobFile = targetFile,
                        partialBlobFile = partialFile,
                        mirrorIndex = 0,
                        modelRepoDirectory = null,
                        onProgress = null
                    )
                }
                kotlinx.coroutines.delay(100)
                assertTrue("Attempt must still be suspended waiting for cancelling job to finish", attempt.isActive && !attempt.isCompleted)
                org.junit.Assert.assertSame("Old cancelling job must still be registered", cancellingJob, blobJobs[artifact.sha256])

                // Cancel attempt while still waiting so it never initiates a real network download
                attempt.cancelAndJoin()
                canFinishCleanup.complete(Unit)
                cancellingJob.join()
            } finally {
                canFinishCleanup.complete(Unit)
                cancellingJob.cancelAndJoin()
                blobJobs.remove(artifact.sha256)
                tempDir.deleteRecursively()
                scope.cancel()
            }
        }
    }

}
