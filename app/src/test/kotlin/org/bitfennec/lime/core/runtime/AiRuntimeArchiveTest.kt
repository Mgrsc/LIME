package org.bitfennec.lime.core.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiRuntimeArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val target: File get() = File(temporaryFolder.root, "output/libfixture.so")
    private val partial: File get() = File(temporaryFolder.root, "fixture.partial")

    private fun elf(alignment: Long = 0x4000): ByteArray =
        ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1))
            putLong(32, 64)
            putShort(54, 56)
            putShort(56, 1)
            putInt(64, 1)
            putLong(112, alignment)
        }.array()

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun fixture(
        data: ByteArray = elf(),
        entryName: String = "jni/arm64-v8a/libfixture.so",
        extraEntry: Pair<String, ByteArray>? = null,
        additionalEntries: List<Pair<String, ByteArray>> = emptyList(),
    ): Pair<AiPackageSpec.Artifact, File> {
        val archiveFile = temporaryFolder.newFile("runtime.aar")
        ZipOutputStream(archiveFile.outputStream()).use { zip ->
            for ((name, content) in listOfNotNull(entryName to data, extraEntry) + additionalEntries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        val archive = AiPackageSpec.Artifact(
            id = "archive-fixture",
            fileName = archiveFile.name,
            sha256 = sha256(archiveFile.readBytes()),
            expectedBytes = archiveFile.length(),
            minExpectedBytes = archiveFile.length(),
            kind = AiPackageSpec.ArtifactKind.ASSET,
            pinnedUrls = listOf("https://github.com/example/runtime.aar"),
            displayNameRes = 1,
        )
        val artifact = AiPackageSpec.Artifact(
            id = "runtime-fixture",
            fileName = "libfixture.so",
            sha256 = sha256(data),
            expectedBytes = data.size.toLong(),
            minExpectedBytes = data.size.toLong(),
            kind = AiPackageSpec.ArtifactKind.ELF_SO,
            pinnedUrls = archive.pinnedUrls,
            displayNameRes = 1,
            archiveSource = AiPackageSpec.ArchiveSource(archive, "jni/arm64-v8a/libfixture.so"),
        )
        return artifact to archiveFile
    }

    @Test
    fun extractsOnlyThePinnedEntry() = runBlocking {
        val (artifact, archive) = fixture(extraEntry = "../outside.so" to byteArrayOf(1))

        assertTrue(AiRuntimeArchive.extract(artifact, archive, target, partial))

        assertEquals(artifact.sha256, AiDownloadGuard.computeSha256(target))
        assertTrue(AiRuntimeLoader.isElf16KbAligned(target))
        assertFalse(partial.exists())
        assertFalse(File(temporaryFolder.root, "outside.so").exists())
    }

    @Test
    fun replacesDamagedTargetAfterVerification() = runBlocking {
        val (artifact, archive) = fixture()
        target.parentFile!!.mkdirs()
        target.writeBytes(ByteArray(artifact.expectedBytes.toInt()))

        assertTrue(AiRuntimeArchive.extract(artifact, archive, target, partial))
        assertEquals(artifact.sha256, AiDownloadGuard.computeSha256(target))
    }

    @Test
    fun rejectsDamagedArchiveAndPreservesTarget() = runBlocking {
        val (artifact, archive) = fixture()
        val bytes = archive.readBytes()
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
        archive.writeBytes(bytes)
        target.parentFile!!.mkdirs()
        target.writeText("retained")
        partial.writeText("stale")

        assertFalse(AiRuntimeArchive.extract(artifact, archive, target, partial))
        assertEquals("retained", target.readText())
        assertFalse(partial.exists())
    }

    @Test
    fun rejectsMissingEntry() = runBlocking {
        val (artifact, archive) = fixture(entryName = "jni/x86_64/libfixture.so")

        assertFalse(AiRuntimeArchive.extract(artifact, archive, target, partial))
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun rejectsWrongExtractedDigest() = runBlocking {
        val (artifact, archive) = fixture()

        assertFalse(AiRuntimeArchive.extract(artifact.copy(sha256 = "0".repeat(64)), archive, target, partial))
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun rejectsOversizedEntry() = runBlocking {
        val (artifact, archive) = fixture()

        assertFalse(AiRuntimeArchive.extract(artifact.copy(expectedBytes = artifact.expectedBytes - 1), archive, target, partial))
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun rejectsUnalignedElf() = runBlocking {
        val (artifact, archive) = fixture(data = elf(0x1000))

        assertFalse(AiRuntimeArchive.extract(artifact, archive, target, partial))
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun rejectsNonElfPayload() = runBlocking {
        val (artifact, archive) = fixture(data = "not an ELF library".toByteArray())

        assertFalse(AiRuntimeArchive.extract(artifact, archive, target, partial))
        assertFalse(target.exists())
    }

    @Test
    fun installerReusesCachedArchiveWithoutNetwork() = runBlocking {
        val (artifact, source) = fixture()
        val directory = temporaryFolder.newFolder("blobs")
        val archive = artifact.downloadArtifact
        val cached = AiBlobStore.getBlobFile(directory, archive.sha256, archive.fileName)
        source.copyTo(cached)

        assertTrue(AiPackageInstaller.downloadBlobSingleFlight(
            packageId = "runtime-cache-fixture",
            artifact = artifact,
            targetBlobFile = target,
            partialBlobFile = partial,
            mirrorIndex = 0,
            modelRepoDirectory = null,
            blobsDir = directory,
            onProgress = null,
        ))
        assertEquals(artifact.sha256, AiDownloadGuard.computeSha256(target))
        assertTrue(cached.isFile)
    }

    @Test
    fun cachedFirstLibraryDoesNotCompleteMissingArchiveDuringInstallation() =
        assertInstallerProgress(cacheFirst = true)

    @Test
    fun missingArchiveDoesNotActivateFreshInstallation() = assertInstallerProgress()

    @Test
    fun cachedArchiveExtractsMissingLibrariesBeforeReady() = assertInstallerProgress(cacheArchive = true)

    @Test
    fun cachedLibrariesBecomeReadyWithoutArchiveTransfer() =
        assertInstallerProgress(cacheFirst = true, cacheSecond = true)

    private fun assertInstallerProgress(
        cacheFirst: Boolean = false,
        cacheSecond: Boolean = false,
        cacheArchive: Boolean = false,
    ) = runBlocking {
        val secondData = elf(0x8000)
        val (original, source) = fixture(extraEntry = "jni/arm64-v8a/libsecond.so" to secondData)
        val archive = original.downloadArtifact.copy(pinnedUrls = listOf("https://github.com:444/example/runtime.aar"))
        val first = original.copy(
            pinnedUrls = archive.pinnedUrls,
            archiveSource = AiPackageSpec.ArchiveSource(archive, "jni/arm64-v8a/libfixture.so"),
        )
        val second = first.copy(
            id = "second-runtime-fixture",
            fileName = "libsecond.so",
            sha256 = sha256(secondData),
            archiveSource = AiPackageSpec.ArchiveSource(archive, "jni/arm64-v8a/libsecond.so"),
        )
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = temporaryFolder.root
            override fun getApplicationContext(): android.content.Context = this
        }
        val pkg = AiPackageSpec.Package(
            id = "runtime-progress-${temporaryFolder.root.name}",
            version = "fixture",
            dependencies = emptyList(),
            artifacts = listOf(first, second),
            displayNameRes = 1,
        )
        for ((artifact, data, cached) in listOf(
            Triple(first, elf(), cacheFirst),
            Triple(second, secondData, cacheSecond),
            Triple(archive, source.readBytes(), cacheArchive),
        )) {
            if (cached) {
                val blob = AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName)
                blob.parentFile!!.mkdirs()
                blob.writeBytes(data)
            }
        }
        val packages = mapOf(pkg.id to pkg)
        val registeredPackages = AiPackageSpec.ALL_PACKAGES.toMap()
        val observedPackages = CopyOnWriteArrayList<Map<String, AiPackageSpec.Package>>()
        val statuses = CopyOnWriteArrayList<AiPackageInstaller.PackageStatus>()
        AiPackageInstaller.cancel(pkg.id)
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            AiPackageInstaller.getStatusFlow(pkg.id).collect {
                statuses.add(it)
                observedPackages.add(AiPackageSpec.ALL_PACKAGES.toMap())
            }
        }
        try {
            val success = withTimeout(5_000) {
                AiPackageInstaller.ensure(context, pkg.id, packages, mirrorIndex = 1, force = true)
            }
            assertTrue(observedPackages.isNotEmpty())
            assertTrue(observedPackages.all { it == registeredPackages })
            assertEquals(registeredPackages, AiPackageSpec.ALL_PACKAGES)
            assertEquals(null, AiPackageSpec.getPackage(pkg.id))
            val downloading = statuses.filterIsInstance<AiPackageInstaller.PackageStatus.Downloading>()
            assertTrue(downloading.isNotEmpty())
            assertTrue(downloading.all { it.percent in 0..99 })
            assertTrue(downloading.all { it.bytesRead in 0..archive.expectedBytes })
            if (cacheArchive || (cacheFirst && cacheSecond)) {
                assertTrue(success)
                assertTrue(statuses.last() is AiPackageInstaller.PackageStatus.Ready)
                assertEquals(pkg.version, AiBlobStore.getActiveVersion(context, pkg.id))
                assertEquals(archive.expectedBytes, downloading.last().totalBytes)
                assertEquals(archive.expectedBytes, downloading.last().bytesRead)
                assertEquals(99, downloading.last().percent)
                for (artifact in pkg.artifacts) {
                    assertEquals(artifact.sha256, AiDownloadGuard.computeSha256(
                        AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName),
                    ))
                }
            } else {
                assertFalse(success)
                assertTrue(statuses.last() is AiPackageInstaller.PackageStatus.Failed)
                assertFalse(statuses.any { it is AiPackageInstaller.PackageStatus.Ready })
                assertEquals(null, AiBlobStore.getActiveVersion(context, pkg.id))
                val connecting = downloading.filter { it.stageDesc.contains("github.com") }
                assertTrue(connecting.isNotEmpty())
                assertTrue(connecting.all { it.artifactName == archive.fileName })
                assertTrue(connecting.all { it.totalBytes == archive.expectedBytes })
                assertTrue(downloading.all { it.bytesRead == 0L && it.percent == 0 })
            }
            assertEquals(cacheArchive, AiBlobStore.getBlobFile(context, archive.sha256).isFile)
        } finally {
            observer.cancelAndJoin()
            AiPackageInstaller.cancel(pkg.id)
        }
    }

    @Test
    fun uninstallKeepsArchiveUntilLastRuntimeConsumerIsRemoved() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = temporaryFolder.root
            override fun getApplicationContext(): android.content.Context = this
        }
        val runtime = AiPackageSpec.ortArtifact
        val jni = AiPackageSpec.ortJniArtifact
        for (artifact in listOf(runtime, jni)) {
            val blob = AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName)
            blob.parentFile!!.mkdirs()
            RandomAccessFile(blob, "rw").use { it.setLength(artifact.expectedBytes) }
        }
        AiPackageInstaller.activatePackage(context, AiPackageSpec.ortPackage)
        AiPackageInstaller.activatePackage(context, AiPackageSpec.ortJniPackage)
        val archive = AiBlobStore.getBlobFile(context, runtime.downloadArtifact.sha256)
        archive.writeText("retained archive")

        assertTrue(AiPackageInstaller.uninstallPackage(context, "ort-jni"))
        assertTrue(archive.isFile)
        assertTrue(AiBlobStore.getBlobFile(context, runtime.sha256, runtime.fileName).isFile)
        assertFalse(AiBlobStore.getBlobFile(context, jni.sha256, jni.fileName).exists())

        assertTrue(AiPackageInstaller.uninstallPackage(context, "ort"))
        assertFalse(archive.exists())
    }

    @Test
    fun runtimeUpgradeKeepsExistingModelsAndPreviousRuntimeFiles() = runBlocking {
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = temporaryFolder.root
            override fun getApplicationContext(): android.content.Context = this
        }
        val packages = AiPackageSpec.resolveDependencies("voice")
        for (pkg in packages) {
            for (artifact in pkg.artifacts) {
                val blob = AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName)
                blob.parentFile!!.mkdirs()
                RandomAccessFile(blob, "rw").use { it.setLength(artifact.expectedBytes) }
            }
            val version = if (pkg.id == "voice") pkg.version else "previous-runtime"
            AiPackageInstaller.activatePackage(context, pkg.copy(version = version))
        }
        val previous = File(AiBlobStore.getBlobsDir(context), "previous/libonnxruntime.so")
        previous.parentFile!!.mkdirs()
        previous.writeText("previous runtime")
        val model = AiBlobStore.getBlobFile(context, AiPackageSpec.voiceModelArtifact.sha256, AiPackageSpec.voiceModelArtifact.fileName)

        assertFalse(AiPackageInstaller.isPackageReady(context, "voice"))
        for (pkg in packages.filter { it.id != "voice" }) {
            AiPackageInstaller.activatePackage(context, pkg)
        }

        assertTrue(AiPackageInstaller.isPackageReady(context, "voice"))
        assertTrue(AiPackageInstaller.ensure(context, "voice"))
        assertEquals(AiPackageSpec.voiceModelArtifact.expectedBytes, model.length())
        assertEquals("previous runtime", previous.readText())
        assertFalse(AiBlobStore.getBlobFile(context, AiPackageSpec.runtimeArchiveArtifact.sha256).exists())
    }

    @Test
    fun cancellingOneConsumerPreservesSharedArchiveDownload() =
        assertSharedArchiveSurvivesConsumerCancellation(sharedArtifact = false)

    @Test
    fun cancellingOriginalConsumerPreservesSharedRuntimeDownload() =
        assertSharedArchiveSurvivesConsumerCancellation(sharedArtifact = true)

    @Test
    fun cancellingVoiceDuringSharedRuntimeDownloadKeepsHandwritingReady() =
        assertPackageCancellationPreservesSharedArchive(cachedOrt = false, cancelledPackageId = "voice")

    @Test
    fun cancellingHandwritingDuringSharedRuntimeDownloadKeepsVoiceReady() =
        assertPackageCancellationPreservesSharedArchive(cachedOrt = false, cancelledPackageId = "handwriting")

    @Test
    fun cancellingVoiceDuringSeparateJniDownloadsKeepsHandwritingReady() =
        assertPackageCancellationPreservesSharedArchive(cachedOrt = true, cancelledPackageId = "voice")

    @Test
    fun cancellingHandwritingDuringSeparateJniDownloadsKeepsVoiceReady() =
        assertPackageCancellationPreservesSharedArchive(cachedOrt = true, cancelledPackageId = "handwriting")

    private fun assertPackageCancellationPreservesSharedArchive(
        cachedOrt: Boolean,
        cancelledPackageId: String,
    ) = runBlocking {
        val voiceJniData = elf(0x8000)
        val handwritingJniData = elf(0x10000)
        val (ortArtifact, source) = fixture(
            extraEntry = "jni/arm64-v8a/libvoice.so" to voiceJniData,
            additionalEntries = listOf("jni/arm64-v8a/libhandwriting.so" to handwritingJniData),
        )
        val archive = ortArtifact.downloadArtifact
        val voiceJni = ortArtifact.copy(
            id = "voice-jni-fixture",
            fileName = "libvoice.so",
            sha256 = sha256(voiceJniData),
            archiveSource = AiPackageSpec.ArchiveSource(archive, "jni/arm64-v8a/libvoice.so"),
        )
        val handwritingJni = ortArtifact.copy(
            id = "handwriting-jni-fixture",
            fileName = "libhandwriting.so",
            sha256 = sha256(handwritingJniData),
            archiveSource = AiPackageSpec.ArchiveSource(archive, "jni/arm64-v8a/libhandwriting.so"),
        )
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = temporaryFolder.root
            override fun getApplicationContext(): android.content.Context = this
        }
        val packages = listOf(
            AiPackageSpec.Package("ort", "package-fixture", emptyList(), listOf(ortArtifact), 1),
            AiPackageSpec.Package("sherpa-jni", "package-fixture", listOf("ort"), listOf(voiceJni), 1),
            AiPackageSpec.Package("ort-jni", "package-fixture", listOf("ort"), listOf(handwritingJni), 1),
            AiPackageSpec.Package("voice", "package-fixture", listOf("sherpa-jni"), emptyList(), 1),
            AiPackageSpec.Package("handwriting", "package-fixture", listOf("ort-jni"), emptyList(), 1),
        ).associateBy { it.id }
        if (cachedOrt) {
            val ort = AiBlobStore.getBlobFile(context, ortArtifact.sha256, ortArtifact.fileName)
            ort.parentFile!!.mkdirs()
            ort.writeBytes(elf())
            AiPackageInstaller.activatePackage(context, packages.getValue("ort"))
        }
        val jobsField = AiPackageInstaller::class.java.getDeclaredField("activeBlobJobs").apply { isAccessible = true }
        val usersField = AiPackageInstaller::class.java.getDeclaredField("blobUsers").apply { isAccessible = true }
        val progressField = AiPackageInstaller::class.java.getDeclaredField("activeBlobProgress").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = jobsField.get(AiPackageInstaller) as MutableMap<String, kotlinx.coroutines.Deferred<Boolean>>
        @Suppress("UNCHECKED_CAST")
        val users = usersField.get(AiPackageInstaller) as Map<String, Set<String>>
        @Suppress("UNCHECKED_CAST")
        val progressFlows = progressField.get(AiPackageInstaller) as MutableMap<String, MutableStateFlow<AiPackageInstaller.BlobStatus?>>
        val transfer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val progress = MutableStateFlow<AiPackageInstaller.BlobStatus?>(null)
        synchronized(jobs) {
            jobs[archive.sha256] = transfer
            progressFlows[archive.sha256] = progress
        }
        val voice = async(Dispatchers.Default) { AiPackageInstaller.ensure(context, "voice", packages) }
        val handwriting = async(Dispatchers.Default) { AiPackageInstaller.ensure(context, "handwriting", packages) }
        try {
            val expectedArchiveUsers = if (cachedOrt) {
                setOf("artifact:${voiceJni.sha256}", "artifact:${handwritingJni.sha256}")
            } else {
                setOf("artifact:${ortArtifact.sha256}")
            }
            withTimeout(5_000) {
                while (
                    users[archive.sha256] != expectedArchiveUsers ||
                    (!cachedOrt && users[ortArtifact.sha256] != setOf("voice", "handwriting")) ||
                    (cachedOrt && users[voiceJni.sha256] != setOf("voice")) ||
                    (cachedOrt && users[handwritingJni.sha256] != setOf("handwriting"))
                ) delay(10)
            }
            assertTrue(AiPackageInstaller.getStatusFlow("voice").value is AiPackageInstaller.PackageStatus.Downloading)
            assertTrue(AiPackageInstaller.getStatusFlow("handwriting").value is AiPackageInstaller.PackageStatus.Downloading)
            val cancelledCaller = if (cancelledPackageId == "voice") voice else handwriting
            val survivingCaller = if (cancelledPackageId == "voice") handwriting else voice
            val survivingPackageId = if (cancelledPackageId == "voice") "handwriting" else "voice"
            val survivingArtifact = if (survivingPackageId == "voice") voiceJni else handwritingJni

            assertTrue(AiPackageInstaller.cancel(cancelledPackageId))
            withTimeout(5_000) { cancelledCaller.join() }
            assertTrue(cancelledCaller.isCancelled)
            assertTrue(transfer.isActive)
            assertTrue(jobs[archive.sha256] === transfer)
            assertEquals(
                if (cachedOrt) setOf("artifact:${survivingArtifact.sha256}") else expectedArchiveUsers,
                users[archive.sha256],
            )
            val cachedArchive = AiBlobStore.getBlobFile(context, archive.sha256, archive.fileName)
            source.copyTo(cachedArchive)
            transfer.complete(true)

            assertTrue(withTimeout(5_000) { survivingCaller.await() })
            assertEquals(AiPackageInstaller.PackageStatus.Ready, AiPackageInstaller.getStatusFlow(survivingPackageId).value)
            assertEquals(AiPackageInstaller.PackageStatus.Missing, AiPackageInstaller.getStatusFlow(cancelledPackageId).value)
            assertEquals("package-fixture", AiBlobStore.getActiveVersion(context, survivingPackageId))
            assertEquals(survivingArtifact.sha256, AiDownloadGuard.computeSha256(
                AiBlobStore.getBlobFile(context, survivingArtifact.sha256, survivingArtifact.fileName),
            ))
            assertTrue(AiPackageInstaller.ensure(context, survivingPackageId, packages))
            assertEquals(0, progress.subscriptionCount.value)
            for (artifact in listOf(archive, ortArtifact, voiceJni, handwritingJni)) {
                assertFalse(jobs.containsKey(artifact.sha256))
                assertFalse(progressFlows.containsKey(artifact.sha256))
                assertFalse(users.containsKey(artifact.sha256))
            }
        } finally {
            AiPackageInstaller.cancel("voice")
            AiPackageInstaller.cancel("handwriting")
            voice.cancelAndJoin()
            handwriting.cancelAndJoin()
            transfer.cancelAndJoin()
            synchronized(jobs) {
                jobs.remove(archive.sha256, transfer)
                progressFlows.remove(archive.sha256, progress)
            }
        }
    }

    @Test
    fun sharedRuntimeReplaysProgressAndSurvivesOriginalObserverCancellation() =
        assertSharedProgressSurvivesCancellation(sharedArtifact = true)

    @Test
    fun sharedArchiveBroadcastsProgressAcrossDifferentLibraries() =
        assertSharedProgressSurvivesCancellation(sharedArtifact = false)

    @Test
    fun sharedRuntimeFailureReleasesEveryProgressObserver() =
        assertSharedProgressSurvivesCancellation(sharedArtifact = true, succeeds = false)

    @Test
    fun retryFinishingBeforePreviousObserverCleanupReleasesTheCurrentTransfer() = runBlocking {
        val (artifact, source) = fixture()
        val directory = temporaryFolder.newFolder("retry-progress-blobs")
        val archive = artifact.downloadArtifact
        source.copyTo(AiBlobStore.getBlobFile(directory, archive.sha256, archive.fileName))
        val jobsField = AiPackageInstaller::class.java.getDeclaredField("activeBlobJobs").apply { isAccessible = true }
        val progressField = AiPackageInstaller::class.java.getDeclaredField("activeBlobProgress").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = jobsField.get(AiPackageInstaller) as MutableMap<String, kotlinx.coroutines.Deferred<Boolean>>
        @Suppress("UNCHECKED_CAST")
        val progressFlows = progressField.get(AiPackageInstaller) as MutableMap<String, MutableStateFlow<AiPackageInstaller.BlobStatus?>>
        val previous = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val previousProgress = MutableStateFlow<AiPackageInstaller.BlobStatus?>(
            AiPackageInstaller.BlobStatus.Stage(AiBlobDownloader.Stage.CONNECTING, "runtime.example"),
        )
        jobs[artifact.sha256] = previous
        progressFlows[artifact.sha256] = previousProgress
        val callbackStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseCallback = CountDownLatch(1)
        val first = async(Dispatchers.Default) {
            AiPackageInstaller.downloadBlobSingleFlight(
                packageId = "previous-progress-consumer",
                artifact = artifact,
                targetBlobFile = target,
                partialBlobFile = partial,
                mirrorIndex = 0,
                modelRepoDirectory = null,
                blobsDir = directory,
                onStage = { _, _ ->
                    callbackStarted.complete(Unit)
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                },
                onProgress = null,
            )
        }
        var retry: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            withTimeout(5_000) { callbackStarted.await() }
            previous.complete(false)
            retry = async(Dispatchers.Default) {
                AiPackageInstaller.downloadBlobSingleFlight(
                    packageId = "retry-progress-consumer",
                    artifact = artifact,
                    targetBlobFile = target,
                    partialBlobFile = partial,
                    mirrorIndex = 0,
                    modelRepoDirectory = null,
                    blobsDir = directory,
                    onProgress = null,
                )
            }
            assertTrue(withTimeout(5_000) { retry.await() })
            releaseCallback.countDown()
            assertFalse(withTimeout(5_000) { first.await() })

            assertEquals(0, previousProgress.subscriptionCount.value)
            assertFalse(jobs.containsKey(artifact.sha256))
            assertFalse(progressFlows.containsKey(artifact.sha256))
            assertEquals(artifact.sha256, AiDownloadGuard.computeSha256(target))
        } finally {
            releaseCallback.countDown()
            first.cancelAndJoin()
            retry?.cancelAndJoin()
            previous.cancelAndJoin()
            jobs.remove(artifact.sha256)
            progressFlows.remove(artifact.sha256)
        }
    }

    private fun assertSharedProgressSurvivesCancellation(
        sharedArtifact: Boolean,
        succeeds: Boolean = true,
    ) = runBlocking {
        val secondData = elf(0x8000)
        val (firstArtifact, source) = fixture(extraEntry = "jni/arm64-v8a/libsecond.so" to secondData)
        val secondArtifact = if (sharedArtifact) firstArtifact else firstArtifact.copy(
            id = "second-progress-fixture",
            fileName = "libsecond.so",
            sha256 = sha256(secondData),
            archiveSource = AiPackageSpec.ArchiveSource(firstArtifact.downloadArtifact, "jni/arm64-v8a/libsecond.so"),
        )
        val directory = temporaryFolder.newFolder("progress-blobs")
        val archive = firstArtifact.downloadArtifact
        val jobsField = AiPackageInstaller::class.java.getDeclaredField("activeBlobJobs").apply { isAccessible = true }
        val progressField = AiPackageInstaller::class.java.getDeclaredField("activeBlobProgress").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = jobsField.get(AiPackageInstaller) as MutableMap<String, kotlinx.coroutines.Deferred<Boolean>>
        @Suppress("UNCHECKED_CAST")
        val progressFlows = progressField.get(AiPackageInstaller) as MutableMap<String, MutableStateFlow<AiPackageInstaller.BlobStatus?>>
        val transfer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val progress = MutableStateFlow<AiPackageInstaller.BlobStatus?>(null)
        jobs[archive.sha256] = transfer
        progressFlows[archive.sha256] = progress
        val firstEvents = Channel<AiPackageInstaller.BlobStatus>(Channel.UNLIMITED)
        val secondEvents = Channel<AiPackageInstaller.BlobStatus>(Channel.UNLIMITED)
        val thirdEvents = Channel<AiPackageInstaller.BlobStatus>(Channel.UNLIMITED)
        suspend fun install(
            packageId: String,
            artifact: AiPackageSpec.Artifact,
            events: Channel<AiPackageInstaller.BlobStatus>,
        ) = AiPackageInstaller.downloadBlobSingleFlight(
            packageId = packageId,
            artifact = artifact,
            targetBlobFile = AiBlobStore.getBlobFile(directory, artifact.sha256, artifact.fileName),
            partialBlobFile = AiBlobStore.getPartialFile(directory, artifact.sha256),
            mirrorIndex = 0,
            modelRepoDirectory = null,
            blobsDir = directory,
            onStage = { stage, detail -> events.trySend(AiPackageInstaller.BlobStatus.Stage(stage, detail)) },
            onProgress = { bytes, total, speed -> events.trySend(AiPackageInstaller.BlobStatus.Progress(bytes, total, speed)) },
        )
        suspend fun next(events: Channel<AiPackageInstaller.BlobStatus>) = withTimeout(5_000) { events.receive() }
        val first = async(Dispatchers.Default) { install("progress-consumer-a", firstArtifact, firstEvents) }
        var second: kotlinx.coroutines.Deferred<Boolean>? = null
        var third: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            val connecting = AiPackageInstaller.BlobStatus.Stage(AiBlobDownloader.Stage.CONNECTING, "runtime.example")
            progress.value = connecting
            assertEquals(connecting, next(firstEvents))
            second = async(Dispatchers.Default) { install("progress-consumer-b", secondArtifact, secondEvents) }
            assertEquals(connecting, next(secondEvents))
            val initialProgress = AiPackageInstaller.BlobStatus.Progress(1, archive.expectedBytes, 1_024)
            progress.value = initialProgress
            assertEquals(initialProgress, next(firstEvents))
            assertEquals(initialProgress, next(secondEvents))
            third = async(Dispatchers.Default) { install("progress-consumer-c", secondArtifact, thirdEvents) }
            assertEquals(initialProgress, next(thirdEvents))

            AiPackageInstaller.cancel("progress-consumer-a")
            first.cancelAndJoin()
            assertTrue(transfer.isActive)
            val resumedProgress = initialProgress.copy(bytesRead = 2, speedBps = 2_048)
            progress.value = resumedProgress
            assertEquals(resumedProgress, next(secondEvents))
            assertEquals(resumedProgress, next(thirdEvents))
            assertTrue(firstEvents.tryReceive().isFailure)

            AiPackageInstaller.cancel("progress-consumer-c")
            third.cancelAndJoin()
            assertTrue(transfer.isActive)
            val reconnecting = connecting.copy(detail = "mirror.example")
            progress.value = reconnecting
            assertEquals(reconnecting, next(secondEvents))
            assertTrue(thirdEvents.tryReceive().isFailure)
            assertTrue(jobs[archive.sha256] === transfer)

            if (succeeds) source.copyTo(AiBlobStore.getBlobFile(directory, archive.sha256, archive.fileName))
            transfer.complete(succeeds)
            assertEquals(succeeds, withTimeout(5_000) { second.await() })
            assertEquals(0, progress.subscriptionCount.value)
            assertFalse(progressFlows.containsKey(archive.sha256))
            assertFalse(jobs.containsKey(archive.sha256))
            assertFalse(progressFlows.containsKey(firstArtifact.sha256))
            assertFalse(progressFlows.containsKey(secondArtifact.sha256))
            if (succeeds) {
                assertEquals(secondArtifact.sha256, AiDownloadGuard.computeSha256(
                    AiBlobStore.getBlobFile(directory, secondArtifact.sha256, secondArtifact.fileName),
                ))
            }
        } finally {
            first.cancelAndJoin()
            second?.cancelAndJoin()
            third?.cancelAndJoin()
            transfer.cancelAndJoin()
            jobs.remove(archive.sha256, transfer)
            progressFlows.remove(archive.sha256, progress)
            firstEvents.close()
            secondEvents.close()
            thirdEvents.close()
        }
    }

    private fun assertSharedArchiveSurvivesConsumerCancellation(sharedArtifact: Boolean) = runBlocking {
        val secondData = elf(0x8000)
        val (firstArtifact, source) = fixture(extraEntry = "jni/arm64-v8a/libsecond.so" to secondData)
        val secondArtifact = if (sharedArtifact) firstArtifact else firstArtifact.copy(
            id = "second-runtime-fixture",
            fileName = "libsecond.so",
            sha256 = sha256(secondData),
            archiveSource = AiPackageSpec.ArchiveSource(firstArtifact.downloadArtifact, "jni/arm64-v8a/libsecond.so"),
        )
        val directory = temporaryFolder.newFolder("shared-blobs")
        val archive = firstArtifact.downloadArtifact
        val field = AiPackageInstaller::class.java.getDeclaredField("activeBlobJobs").apply { isAccessible = true }
        val usersField = AiPackageInstaller::class.java.getDeclaredField("blobUsers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(AiPackageInstaller) as MutableMap<String, kotlinx.coroutines.Deferred<Boolean>>
        @Suppress("UNCHECKED_CAST")
        val users = usersField.get(AiPackageInstaller) as Map<String, Set<String>>
        val transfer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        jobs[archive.sha256] = transfer
        suspend fun install(packageId: String, artifact: AiPackageSpec.Artifact): Boolean =
            AiPackageInstaller.downloadBlobSingleFlight(
                packageId = packageId,
                artifact = artifact,
                targetBlobFile = AiBlobStore.getBlobFile(directory, artifact.sha256, artifact.fileName),
                partialBlobFile = AiBlobStore.getPartialFile(directory, artifact.sha256),
                mirrorIndex = 0,
                modelRepoDirectory = null,
                blobsDir = directory,
                onProgress = null,
            )
        val first = async(Dispatchers.Default) { install("runtime-consumer-a", firstArtifact) }
        val second = async(Dispatchers.Default) {
            withTimeout(5_000) {
                while (
                    users[firstArtifact.sha256]?.contains("runtime-consumer-a") != true ||
                    users[archive.sha256].isNullOrEmpty()
                ) delay(10)
            }
            install("runtime-consumer-b", secondArtifact)
        }
        try {
            withTimeout(5_000) {
                while (
                    users[firstArtifact.sha256]?.contains("runtime-consumer-a") != true ||
                    users[secondArtifact.sha256]?.contains("runtime-consumer-b") != true ||
                    (users[archive.sha256]?.size ?: 0) < if (sharedArtifact) 1 else 2
                ) delay(10)
            }
            AiPackageInstaller.cancel("runtime-consumer-a")
            first.cancelAndJoin()
            assertTrue(transfer.isActive)
            source.copyTo(AiBlobStore.getBlobFile(directory, archive.sha256, archive.fileName))
            transfer.complete(true)

            assertTrue(withTimeout(5_000) { second.await() })
            if (!sharedArtifact) {
                assertFalse(AiBlobStore.getBlobFile(directory, firstArtifact.sha256, firstArtifact.fileName).exists())
            }
            assertEquals(secondArtifact.sha256, AiDownloadGuard.computeSha256(AiBlobStore.getBlobFile(directory, secondArtifact.sha256, secondArtifact.fileName)))
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
            transfer.cancelAndJoin()
            jobs.remove(archive.sha256, transfer)
        }
    }

    @Test
    fun cancellingLastRuntimeConsumerStopsArchiveDownload() = runBlocking {
        val (artifact, _) = fixture()
        val directory = temporaryFolder.newFolder("cancelled-blobs")
        val archive = artifact.downloadArtifact
        val field = AiPackageInstaller::class.java.getDeclaredField("activeBlobJobs").apply { isAccessible = true }
        val usersField = AiPackageInstaller::class.java.getDeclaredField("blobUsers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(AiPackageInstaller) as MutableMap<String, kotlinx.coroutines.Deferred<Boolean>>
        @Suppress("UNCHECKED_CAST")
        val users = usersField.get(AiPackageInstaller) as Map<String, Set<String>>
        val transfer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        jobs[archive.sha256] = transfer
        val consumer = async(Dispatchers.Default) {
            AiPackageInstaller.downloadBlobSingleFlight(
                packageId = "runtime-last-consumer",
                artifact = artifact,
                targetBlobFile = target,
                partialBlobFile = partial,
                mirrorIndex = 0,
                modelRepoDirectory = null,
                blobsDir = directory,
                onProgress = null,
            )
        }
        try {
            withTimeout(5_000) {
                while (users[archive.sha256].isNullOrEmpty()) delay(10)
            }
            AiPackageInstaller.cancel("runtime-last-consumer")
            withTimeout(5_000) { consumer.cancelAndJoin() }

            assertTrue(transfer.isCancelled)
            assertFalse(target.exists())
            assertFalse(partial.exists())
            assertTrue(users[archive.sha256].isNullOrEmpty())
        } finally {
            consumer.cancelAndJoin()
            transfer.cancelAndJoin()
            jobs.remove(archive.sha256, transfer)
        }
    }
}
