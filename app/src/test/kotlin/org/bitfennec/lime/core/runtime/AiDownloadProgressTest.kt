package org.bitfennec.lime.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AiDownloadProgressTest {

    private val runtime = AiPackageSpec.ortArtifact
    private val jni = AiPackageSpec.ortJniArtifact
    private val archive = runtime.downloadArtifact

    @Test
    fun cachedRuntimeDoesNotCreditMissingArchiveRequiredByJni() {
        val progress = AiPackageInstaller.DownloadProgress(listOf(runtime, jni))

        progress.complete(runtime)

        assertEquals(archive.expectedBytes, progress.totalBytes)
        assertEquals(0L, progress.bytesRead)
        assertEquals(0, progress.percent)
    }

    @Test
    fun laterRuntimeConsumerReportsActualArchiveTransfer() {
        val progress = AiPackageInstaller.DownloadProgress(listOf(runtime, jni))
        progress.complete(runtime)

        progress.update(jni, archive.expectedBytes / 2)

        assertEquals(archive.expectedBytes / 2, progress.bytesRead)
        assertEquals(49, progress.percent)
    }

    @Test
    fun repeatedSourceProgressIsNotCountedTwice() {
        val progress = AiPackageInstaller.DownloadProgress(listOf(runtime, jni))
        val received = archive.expectedBytes / 2

        progress.update(runtime, received)
        progress.update(jni, received)
        progress.update(runtime, received)

        assertEquals(received, progress.bytesRead)
    }

    @Test
    fun allCachedRuntimeOutputsSatisfySourceWithoutArchiveTransfer() {
        val progress = AiPackageInstaller.DownloadProgress(listOf(runtime, jni))

        progress.complete(runtime)
        progress.complete(jni)

        assertEquals(archive.expectedBytes, progress.bytesRead)
        assertEquals(99, progress.percent)
    }

    @Test
    fun resumedTransferDoesNotRegressOrExceedSourceSize() {
        val progress = AiPackageInstaller.DownloadProgress(listOf(runtime, jni))

        progress.update(runtime, archive.expectedBytes / 2)
        progress.update(runtime, 0)
        assertEquals(archive.expectedBytes / 2, progress.bytesRead)

        progress.update(jni, archive.expectedBytes * 2)
        assertEquals(archive.expectedBytes, progress.bytesRead)
        assertEquals(99, progress.percent)
    }

    @Test
    fun modelAndRuntimeSourcesAccumulateIndependently() {
        val model = AiPackageSpec.voiceModelArtifact
        val progress = AiPackageInstaller.DownloadProgress(listOf(runtime, jni, model))

        progress.complete(runtime)
        progress.update(jni, archive.expectedBytes / 2)
        progress.update(model, model.expectedBytes / 3)

        assertEquals(archive.expectedBytes + model.expectedBytes, progress.totalBytes)
        assertEquals(archive.expectedBytes / 2 + model.expectedBytes / 3, progress.bytesRead)
    }
}
