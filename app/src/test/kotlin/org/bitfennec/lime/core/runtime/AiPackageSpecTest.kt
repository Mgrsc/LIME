package org.bitfennec.lime.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class AiPackageSpecTest {

    @Test
    fun testPackageGraphTopology() {
        val hwDeps = AiPackageSpec.resolveDependencies("handwriting")
        val hwDepIds = hwDeps.map { it.id }
        assertEquals(listOf("ort", "ort-jni", "handwriting"), hwDepIds)

        val voiceDeps = AiPackageSpec.resolveDependencies("voice")
        val voiceDepIds = voiceDeps.map { it.id }
        assertEquals(listOf("ort", "sherpa-jni", "voice"), voiceDepIds)
    }

    @Test
    fun testAllArtifactsHaveValidShaAndUrls() {
        val shaRegex = Regex("^[0-9a-fA-F]{64}$")
        for ((pkgId, pkg) in AiPackageSpec.ALL_PACKAGES) {
            assertTrue("Package $pkgId must have version", pkg.version.isNotBlank())
            assertTrue("Package $pkgId must have artifacts", pkg.artifacts.isNotEmpty())
            for (artifact in pkg.artifacts) {
                assertTrue("Artifact ${artifact.id} must have valid SHA-256", shaRegex.matches(artifact.sha256))
                assertTrue("Artifact ${artifact.id} minExpectedBytes must be > 0", artifact.minExpectedBytes > 0)
                assertTrue("Artifact ${artifact.id} must have pinned URLs", artifact.pinnedUrls.isNotEmpty())
                assertTrue("Artifact ${artifact.id} displayNameRes must not be 0", artifact.displayNameRes != 0)
                for (urlStr in artifact.pinnedUrls) {
                    val url = URL(urlStr)
                    assertEquals("URL must use HTTPS", "https", url.protocol)
                    assertTrue("URL must point to artifact filename", urlStr.endsWith(artifact.fileName))
                }
            }
        }
    }

    @Test
    fun testPackageDisplayNamesConfigured() {
        for ((_, pkg) in AiPackageSpec.ALL_PACKAGES) {
            assertTrue("Package ${pkg.id} must have valid displayNameRes", pkg.displayNameRes != 0)
        }
    }

    @Test
    fun testGetDisplayNameFallbackWhenResourcesUnavailable() {
        val mockContext = object : android.content.ContextWrapper(null) {}
        val artifactName = AiPackageSpec.ortArtifact.getDisplayName(mockContext)
        assertEquals(AiPackageSpec.ortArtifact.fileName, artifactName)
        val pkgName = AiPackageSpec.ortPackage.getDisplayName(mockContext)
        assertEquals(AiPackageSpec.ortPackage.id, pkgName)
    }

    @Test
    fun testGuardD4ArtifactUrlValidation() {
        // First hop from pinned URLs must pass
        val pinnedHwUrl = URL(AiPackageSpec.hwModelArtifact.pinnedUrls.first())
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            pinnedHwUrl,
            AiPackageSpec.hwModelArtifact.fileName,
            AiPackageSpec.hwModelArtifact.sha256,
            AiPackageSpec.HANDWRITING_PREFIXES,
        ))

        // ModelScope LFS object redirect matching exact SHA
        val msLfsUrl = URL("https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/54/35/fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634")
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            msLfsUrl,
            AiPackageSpec.hwModelArtifact.fileName,
            AiPackageSpec.hwModelArtifact.sha256,
            AiPackageSpec.HANDWRITING_PREFIXES,
        ))

        // ModelScope LFS with wrong SHA must be rejected
        val badMsLfsUrl = URL("https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/00/00/000000000000000000000000000000000000000000000000000000000000")
        assertTrue(!AiDownloadGuard.isAllowedArtifactUrl(
            badMsLfsUrl,
            AiPackageSpec.hwModelArtifact.fileName,
            AiPackageSpec.hwModelArtifact.sha256,
            AiPackageSpec.HANDWRITING_PREFIXES,
        ))

        // HuggingFace AWS CloudFront CDN redirect matching approved domain (*.hf.co)
        val hfCdnUrl = URL("https://us.aws.cdn.hf.co/xet-bridge-us/c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51?Policy=ey...")
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            hfCdnUrl,
            AiPackageSpec.voiceModelArtifact.fileName,
            AiPackageSpec.voiceModelArtifact.sha256,
            AiPackageSpec.VOICE_PREFIXES,
            AiPackageSpec.VOICE_VERSION,
        ))

        // HuggingFace CDN direct download without repo route forbidden
        val badHfCdnUrl = URL("https://cdn-lfs.huggingface.co/ppocrv6_rec.onnx")
        assertTrue(!AiDownloadGuard.isAllowedArtifactUrl(
            badHfCdnUrl,
            AiPackageSpec.hwModelArtifact.fileName,
            AiPackageSpec.hwModelArtifact.sha256,
            AiPackageSpec.HANDWRITING_PREFIXES,
        ))
    }
}
