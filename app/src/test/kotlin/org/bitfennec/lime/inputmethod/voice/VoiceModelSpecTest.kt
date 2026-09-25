package org.bitfennec.lime.inputmethod.voice

import java.io.File
import java.net.URL
import org.bitfennec.lime.core.runtime.AiDownloadGuard
import org.bitfennec.lime.core.runtime.AiPackageSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelSpecTest {

    private val modelArtifact = AiPackageSpec.voiceModelArtifact
    private val tokensArtifact = AiPackageSpec.voiceTokensArtifact

    @Test
    fun validatesExactSizeAndSha256() {
        val file = File.createTempFile("voice-model-spec", ".bin")
        try {
            file.writeText("test")
            val expectedHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

            assertTrue(file.length() == 4L && AiDownloadGuard.verifySha256(file, expectedHash))
            assertFalse(file.length() == 5L && AiDownloadGuard.verifySha256(file, expectedHash))
        } finally {
            file.delete()
        }
    }

    @Test
    fun allowsApprovedSourcesAndHuggingFaceCacheRedirect() {
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = URL(modelArtifact.pinnedUrls.first()),
            fileName = modelArtifact.fileName,
            sha256 = modelArtifact.sha256,
            allowedPrefixes = modelArtifact.pinnedUrls,
            modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
        ))
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = URL("https://huggingface.co/api/resolve-cache/models/csukuangfj/${AiPackageSpec.VOICE_VERSION}/0123456789abcdef/${tokensArtifact.fileName}"),
            fileName = tokensArtifact.fileName,
            sha256 = tokensArtifact.sha256,
            allowedPrefixes = tokensArtifact.pinnedUrls,
            modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
        ))
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            url = URL("https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/c7/1f/0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            fileName = modelArtifact.fileName,
            sha256 = modelArtifact.sha256,
            allowedPrefixes = modelArtifact.pinnedUrls,
            modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
        ))
    }

    @Test
    fun rejectsRedirectsOutsideTheApprovedModelPath() {
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(
            url = URL("https://cdn-lfs.huggingface.co/model.int8.onnx"),
            fileName = modelArtifact.fileName,
            sha256 = modelArtifact.sha256,
            allowedPrefixes = modelArtifact.pinnedUrls,
            modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
        ))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(
            url = URL("https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/c7/1f/000000000000000000000000000000000000000000000000000000000000"),
            fileName = modelArtifact.fileName,
            sha256 = modelArtifact.sha256,
            allowedPrefixes = modelArtifact.pinnedUrls,
            modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
        ))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(
            url = URL("https://huggingface.co/api/resolve-cache/models/other/${AiPackageSpec.VOICE_VERSION}/0123456789abcdef/${modelArtifact.fileName}"),
            fileName = modelArtifact.fileName,
            sha256 = modelArtifact.sha256,
            allowedPrefixes = modelArtifact.pinnedUrls,
            modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
        ))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(
            url = URL("http://huggingface.co/csukuangfj/${AiPackageSpec.VOICE_VERSION}/resolve/main/${modelArtifact.fileName}"),
            fileName = modelArtifact.fileName,
            sha256 = modelArtifact.sha256,
            allowedPrefixes = modelArtifact.pinnedUrls,
            modelRepoDirectory = AiPackageSpec.VOICE_VERSION,
        ))
    }
}
