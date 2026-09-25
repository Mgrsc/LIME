package org.bitfennec.lime.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL

class AiDownloadManagerTest {

    @Test
    fun testDownloadGuardArtifactUrlValidation() {
        val ortArtifact = AiPackageSpec.ortArtifact
        val hwArtifact = AiPackageSpec.hwModelArtifact

        val approvedOrtUrl = URL(ortArtifact.pinnedUrls[0])
        val approvedMirrorUrl = URL(ortArtifact.pinnedUrls[1])
        val approvedHwUrl = URL(hwArtifact.pinnedUrls[0])

        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = approvedOrtUrl,
            fileName = ortArtifact.fileName,
            sha256 = ortArtifact.sha256,
            allowedPrefixes = ortArtifact.pinnedUrls
        ))
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = approvedMirrorUrl,
            fileName = ortArtifact.fileName,
            sha256 = ortArtifact.sha256,
            allowedPrefixes = ortArtifact.pinnedUrls
        ))
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = approvedHwUrl,
            fileName = hwArtifact.fileName,
            sha256 = hwArtifact.sha256,
            allowedPrefixes = hwArtifact.pinnedUrls
        ))

        // Approved HuggingFace AWS CloudFront CDN redirect (us.aws.cdn.hf.co)
        val hfCdnUrl = URL("https://us.aws.cdn.hf.co/xet-bridge-us/12345?Policy=test")
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = hfCdnUrl,
            fileName = ortArtifact.fileName,
            sha256 = ortArtifact.sha256,
            allowedPrefixes = ortArtifact.pinnedUrls
        ))

        // Aliyun OSS CDN redirect (domain allowed; payload anti-tampering guaranteed by streaming SHA-256)
        val voiceArtifact = AiPackageSpec.voiceModelArtifact
        val aliyunCdnUrl = URL("https://modelscope.oss-cn-hangzhou.aliyuncs.com/model.int8.onnx")
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = aliyunCdnUrl,
            fileName = voiceArtifact.fileName,
            sha256 = voiceArtifact.sha256,
            allowedPrefixes = voiceArtifact.pinnedUrls
        ))

        // Reject non-whitelisted and hostile URLs
        val evilUrl = URL("https://evil.attacker.com/libonnxruntime.so")
        val forbiddenHfCdn = URL("https://cdn-lfs.huggingface.co/libonnxruntime.so")
        val traversalUrl = URL("${ortArtifact.pinnedUrls[0]}../malicious.so")
        val encodedTraversalUrl = URL("${ortArtifact.pinnedUrls[0]}%2e%2e/malicious.so")
        val httpUrl = URL("http://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/libonnxruntime.so")

        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(evilUrl, ortArtifact.fileName, ortArtifact.sha256, ortArtifact.pinnedUrls))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(forbiddenHfCdn, ortArtifact.fileName, ortArtifact.sha256, ortArtifact.pinnedUrls))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(traversalUrl, ortArtifact.fileName, ortArtifact.sha256, ortArtifact.pinnedUrls))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(encodedTraversalUrl, ortArtifact.fileName, ortArtifact.sha256, ortArtifact.pinnedUrls))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(httpUrl, ortArtifact.fileName, ortArtifact.sha256, ortArtifact.pinnedUrls))
    }

    @Test
    fun testDownloadGuardSha256Verification() {
        val testFile = File.createTempFile("ai_guard_test", ".bin")
        try {
            val content = "lime-secure-ai-runtime-payload-test"
            testFile.writeText(content)

            val computedHash = AiDownloadGuard.computeSha256(testFile)
            assertTrue(!computedHash.isNullOrEmpty())

            assertTrue(AiDownloadGuard.verifySha256(testFile, computedHash!!))
            assertFalse(AiDownloadGuard.verifySha256(testFile, "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"))
        } finally {
            testFile.delete()
        }
    }
}
