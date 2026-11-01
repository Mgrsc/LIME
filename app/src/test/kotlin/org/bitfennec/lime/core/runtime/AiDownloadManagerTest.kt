package org.bitfennec.lime.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL

class AiDownloadManagerTest {

    private fun nestedAsset(fileName: String) = AiPackageSpec.voiceTokensArtifact.copy(
        fileName = fileName,
        pinnedUrls = AiPackageSpec.VOICE_PREFIXES.map { "$it$fileName" },
    )

    @Test
    fun testDownloadGuardArtifactUrlValidation() {
        val ortArtifact = AiPackageSpec.ortArtifact.downloadArtifact
        val hwArtifact = AiPackageSpec.hwModelArtifact

        val approvedHwUrl = URL(hwArtifact.pinnedUrls[0])

        for (url in ortArtifact.pinnedUrls) {
            assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
                url = URL(url),
                fileName = ortArtifact.fileName,
                sha256 = ortArtifact.sha256,
                allowedPrefixes = ortArtifact.pinnedUrls
            ))
        }
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
    fun permitsEncodedAssetSeparatorsInBoundHuggingFaceCacheRedirects() {
        val revision = "6f16bd378457e13f36ccf3910df9017f96c346fb"
        val prefix = "https://huggingface.co/api/resolve-cache/models/csukuangfj/${AiPackageSpec.VOICE_VERSION}/$revision/"
        val artifacts = listOf(
            nestedAsset("tokenizer/vocab.json"),
            nestedAsset("tokenizer/merges.txt"),
            nestedAsset("tokenizer/tokenizer.json"),
        )
        for (artifact in artifacts) {
            for (separator in listOf("/", "%2F", "%2f")) {
                val url = URL(prefix + artifact.fileName.replace("/", separator) + "?etag=test")
                assertTrue(url.toString(), AiDownloadGuard.isAllowedArtifactUrl(
                    url = url,
                    fileName = artifact.fileName,
                    sha256 = artifact.sha256,
                    allowedPrefixes = artifact.pinnedUrls,
                    modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
                ))
            }
        }
    }

    @Test
    fun rejectsUnboundOrUnsafeEncodedCacheRedirects() {
        val artifact = nestedAsset("tokenizer/merges.txt")
        val revision = "6f16bd378457e13f36ccf3910df9017f96c346fb"
        val prefix = "https://huggingface.co/api/resolve-cache/models/csukuangfj/${AiPackageSpec.VOICE_VERSION}/$revision/"
        val encodedFile = artifact.fileName.replace("/", "%2F")
        val rejected = listOf(
            prefix.replace("https://", "http://") + encodedFile,
            prefix.replace("huggingface.co", "user@huggingface.co") + encodedFile,
            prefix.replace("huggingface.co", "huggingface.co:444") + encodedFile,
            prefix.replace("huggingface.co", "huggingface.co.attacker.example") + encodedFile,
            prefix.replace(AiPackageSpec.VOICE_VERSION, "another-model") + encodedFile,
            prefix.replace("csukuangfj/", "another-owner/") + encodedFile,
            prefix.replace(revision, "invalid-revision") + encodedFile,
            prefix + "tokenizer%2Ftokenizer.json",
            prefix + "tokenizer%2F..%2Fmerges.txt",
            prefix + "tokenizer%2F%2e%2e%2Fmerges.txt",
            prefix + "tokenizer%252Fmerges.txt",
            prefix + "tokenizer%5Cmerges.txt",
            prefix + "tokenizer%2F%2Fmerges.txt",
            prefix.replace("huggingface.co", "hf-mirror.com") + encodedFile,
            "https://huggingface.co/csukuangfj/${AiPackageSpec.VOICE_VERSION}/resolve/main/$encodedFile",
        )
        for (url in rejected) {
            assertFalse(url, AiDownloadGuard.isAllowedArtifactUrl(
                url = URL(url),
                fileName = artifact.fileName,
                sha256 = artifact.sha256,
                allowedPrefixes = artifact.pinnedUrls,
                modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
            ))
        }
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
