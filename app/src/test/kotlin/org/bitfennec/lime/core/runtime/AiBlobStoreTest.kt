package org.bitfennec.lime.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiBlobStoreTest {

    @Test
    fun testBlobFilePathConvention() {
        val tempBlobsDir = File.createTempFile("test_ai_blobs", "").apply {
            delete()
            mkdirs()
        }
        try {
            val soBlob = AiBlobStore.getBlobFile(tempBlobsDir, "dummy_sha_1", "libonnxruntime.so")
            assertTrue("SO blob path must end with .so filename", soBlob.path.endsWith("dummy_sha_1/libonnxruntime.so"))

            val modelBlob = AiBlobStore.getBlobFile(tempBlobsDir, "dummy_sha_2", "model.onnx")
            assertTrue("Non-SO blob path should use flat sha", modelBlob.path.endsWith("dummy_sha_2"))

            val partialFile = AiBlobStore.getPartialFile(tempBlobsDir, "dummy_sha_3")
            assertTrue("Partial file must have .partial suffix", partialFile.name == "dummy_sha_3.partial")
        } finally {
            tempBlobsDir.deleteRecursively()
        }
    }

    @Test
    fun testHasBlobValidation() {
        val tempBlobsDir = File.createTempFile("test_ai_blobs2", "").apply {
            delete()
            mkdirs()
        }
        try {
            val sha = "abc12345"
            val targetFile = AiBlobStore.getBlobFile(tempBlobsDir, sha)
            assertFalse(AiBlobStore.hasBlob(tempBlobsDir, sha))

            targetFile.writeText("hello-world")
            val len = targetFile.length()

            assertTrue(AiBlobStore.hasBlob(tempBlobsDir, sha))
            assertTrue(AiBlobStore.hasBlob(tempBlobsDir, sha, expectedBytes = len))
            assertFalse(AiBlobStore.hasBlob(tempBlobsDir, sha, expectedBytes = len + 10))
            assertTrue(AiBlobStore.hasBlob(tempBlobsDir, sha, minBytes = len - 2))
            assertFalse(AiBlobStore.hasBlob(tempBlobsDir, sha, minBytes = len + 5))
        } finally {
            tempBlobsDir.deleteRecursively()
        }
    }

    @Test
    fun testCandidateUrlResolution() = kotlinx.coroutines.runBlocking {
        val urls = listOf(
            "https://huggingface.co/repo/model.onnx",
            "https://www.modelscope.cn/repo/model.onnx",
            "https://hf-mirror.com/repo/model.onnx"
        )

        val msUrls = AiBlobDownloader.resolveCandidateUrls(urls, mirrorIndex = 1)
        assertTrue("First URL should be ModelScope", msUrls.first().contains("modelscope"))

        val hfMirrorUrls = AiBlobDownloader.resolveCandidateUrls(urls, mirrorIndex = 2)
        assertTrue("First URL should be HF-Mirror", hfMirrorUrls.first().contains("hf-mirror"))

        val preservedUrls = AiBlobDownloader.resolveCandidateUrls(urls, mirrorIndex = -1)
        assertEquals(urls, preservedUrls)
    }
}
