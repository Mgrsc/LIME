package org.bitfennec.lime.core.runtime

import kotlinx.coroutines.runBlocking
import org.bitfennec.lime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiBlobDownloaderTest {

    @Test
    fun testResolveCandidateUrlsForHandwritingAndVoice() = runBlocking {
        // Handwriting URLs (ghproxy + github)
        val hwUrls = listOf(
            "https://ghproxy.net/https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/ppocrv6_rec.onnx",
            "https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/ppocrv6_rec.onnx",
        )
        val hwGhUrls = AiBlobDownloader.resolveCandidateUrls(hwUrls, mirrorIndex = 1)
        assertTrue(hwGhUrls.first().startsWith("https://github.com"))

        val hwGhProxyUrls = AiBlobDownloader.resolveCandidateUrls(hwUrls, mirrorIndex = 2)
        assertTrue(hwGhProxyUrls.first().startsWith("https://ghproxy.net"))

        // Voice URLs (modelscope + hf-mirror + huggingface)
        val voiceUrls = listOf(
            "https://www.modelscope.cn/models/gomodels/sherpa/resolve/master/model.int8.onnx",
            "https://hf-mirror.com/csukuangfj/resolve/main/model.int8.onnx",
            "https://huggingface.co/csukuangfj/resolve/main/model.int8.onnx",
        )
        val vMsUrls = AiBlobDownloader.resolveCandidateUrls(voiceUrls, mirrorIndex = 1)
        assertTrue(vMsUrls.first().contains("modelscope"))

        val vHfMirrorUrls = AiBlobDownloader.resolveCandidateUrls(voiceUrls, mirrorIndex = 2)
        assertTrue(vHfMirrorUrls.first().contains("hf-mirror"))

        val vHfUrls = AiBlobDownloader.resolveCandidateUrls(voiceUrls, mirrorIndex = 3)
        assertTrue(vHfUrls.first().contains("huggingface"))
    }

    @Test
    fun testDownloadBlobSkipsWhenTargetAlreadyValid() = runBlocking {
        val tempDir = File.createTempFile("blob_downloader_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            val targetFile = File(tempDir, "target.bin")
            val partialFile = File(tempDir, "target.bin.part")

            val content = "lime-test-ai-artifact-payload"
            targetFile.writeText(content)
            val sha = AiDownloadGuard.computeSha256(targetFile)!!

            val artifact = AiPackageSpec.Artifact(
                id = "test-art",
                fileName = "target.bin",
                sha256 = sha,
                expectedBytes = targetFile.length(),
                minExpectedBytes = targetFile.length(),
                kind = AiPackageSpec.ArtifactKind.ASSET,
                pinnedUrls = listOf("https://github.com/bitfennec/LIME/target.bin"),
                displayNameRes = R.string.ai_artifact_hw_model,
            )

            val success = AiBlobDownloader.downloadBlob(
                artifact = artifact,
                targetFile = targetFile,
                partialFile = partialFile,
            )
            assertTrue("Should succeed immediately when target already valid", success)
            assertFalse("Partial file should not be created", partialFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDownloadBlobRejectsInvalidLocalAndHostileSources() = runBlocking {
        val tempDir = File.createTempFile("blob_downloader_test2", "").apply {
            delete()
            mkdirs()
        }

        try {
            val targetFile = File(tempDir, "target.bin")
            val partialFile = File(tempDir, "target.bin.part")

            // Corrupted local file
            targetFile.writeText("corrupted-data")

            val artifact = AiPackageSpec.Artifact(
                id = "test-art",
                fileName = "target.bin",
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                expectedBytes = 100L,
                minExpectedBytes = 100L,
                kind = AiPackageSpec.ArtifactKind.ASSET,
                pinnedUrls = listOf("https://invalid.unresolvable.domain.local/target.bin"),
                displayNameRes = R.string.ai_artifact_hw_model,
            )

            val success = AiBlobDownloader.downloadBlob(
                artifact = artifact,
                targetFile = targetFile,
                partialFile = partialFile,
            )
            assertFalse("Should fail safely when sources are unresolvable", success)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFormatSpeedBoundary() {
        assertEquals("0 KB/s", AiBlobDownloader.formatSpeed(0L))
        assertEquals("500 KB/s", AiBlobDownloader.formatSpeed(500 * 1024L))
        assertEquals("1023 KB/s", AiBlobDownloader.formatSpeed(1023 * 1024L))
        assertEquals("1.0 MB/s", AiBlobDownloader.formatSpeed(1024 * 1024L))
        assertEquals("1.5 MB/s", AiBlobDownloader.formatSpeed((1.5 * 1024 * 1024).toLong()))
    }
}
