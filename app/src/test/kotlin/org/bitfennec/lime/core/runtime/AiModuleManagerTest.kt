package org.bitfennec.lime.core.runtime

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bitfennec.lime.inputmethod.voice.VoiceModelManager
import java.io.File
import java.io.RandomAccessFile

class AiModuleManagerTest {

    private fun createFakeBlob(context: Context, artifact: AiPackageSpec.Artifact) {
        val blob = AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName)
        blob.parentFile?.mkdirs()
        val targetSize = if (artifact.expectedBytes > 0) artifact.expectedBytes else (artifact.minExpectedBytes + 1024L)
        RandomAccessFile(blob, "rw").use { raf ->
            raf.setLength(targetSize)
        }
    }

    @Test
    fun testOrtAndHandwritingReadyVerification() {
        val tempDir = File.createTempFile("ai_test_dir", "").apply {
            delete()
            mkdirs()
        }

        try {
            val fakeContext = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
                override fun getApplicationContext(): Context = this
            }

            // Initial uninstalled state returns false
            AiModuleManager.invalidateCache()
            assertFalse(AiModuleManager.isOrtReady(fakeContext))
            assertFalse(AiModuleManager.isHandwritingReady(fakeContext))

            // Create ORT blob & activate ort package
            createFakeBlob(fakeContext, AiPackageSpec.ortArtifact)
            AiPackageInstaller.activatePackage(fakeContext, AiPackageSpec.ortPackage)

            assertTrue(AiModuleManager.isOrtReady(fakeContext))

            // Handwriting bundle missing; isHandwritingReady remains false
            assertFalse(AiModuleManager.isHandwritingReady(fakeContext))

            // Create JNI bridge file & activate ort-jni package
            createFakeBlob(fakeContext, AiPackageSpec.ortJniArtifact)
            AiPackageInstaller.activatePackage(fakeContext, AiPackageSpec.ortJniPackage)

            assertTrue(AiModuleManager.isOrtJniReady(fakeContext))
            assertFalse(AiModuleManager.isHandwritingReady(fakeContext))

            // Create handwriting bundle blobs & activate handwriting package
            createFakeBlob(fakeContext, AiPackageSpec.hwModelArtifact)
            createFakeBlob(fakeContext, AiPackageSpec.hwDictArtifact)
            createFakeBlob(fakeContext, AiPackageSpec.hwPinyinArtifact)
            AiPackageInstaller.activatePackage(fakeContext, AiPackageSpec.handwritingPackage)

            assertTrue(AiModuleManager.isHandwritingReady(fakeContext))

            // In-memory cache test
            val cachedResult = AiModuleManager.isHandwritingReady(fakeContext)
            assertTrue(cachedResult)

            // Detect missing after cache invalidation (e.g. handwriting deleted)
            AiBlobStore.deletePackage(fakeContext, "handwriting")
            AiModuleManager.invalidateCache()
            assertFalse(AiModuleManager.isHandwritingReady(fakeContext))
        } finally {
            tempDir.deleteRecursively()
            VoiceModelManager.invalidateCache()
        }
    }

    @Test
    fun testVoiceReadyWithoutCachePoisoning() {
        val tempDir = File.createTempFile("voice_test_dir", "").apply {
            delete()
            mkdirs()
        }

        try {
            val fakeContext = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
                override fun getApplicationContext(): Context = this
            }

            AiModuleManager.invalidateCache()
            VoiceModelManager.invalidateCache()
            assertFalse(VoiceModelManager.isModelReady(fakeContext))
            // Initial unready state returns false
            assertFalse(AiModuleManager.isVoiceReady(fakeContext))

            // Create ORT blob & activate
            createFakeBlob(fakeContext, AiPackageSpec.ortArtifact)
            AiPackageInstaller.activatePackage(fakeContext, AiPackageSpec.ortPackage)
            assertTrue(AiModuleManager.isOrtReady(fakeContext))

            // Sherpa JNI not ready; isVoiceReady remains false
            assertFalse(AiModuleManager.isVoiceReady(fakeContext))

            // Create Sherpa JNI & activate
            createFakeBlob(fakeContext, AiPackageSpec.sherpaJniArtifact)
            AiPackageInstaller.activatePackage(fakeContext, AiPackageSpec.sherpaJniPackage)

            // Voice model not installed; isVoiceReady returns false without cache poisoning
            assertFalse(AiModuleManager.isVoiceReady(fakeContext))
            assertFalse(AiModuleManager.isVoiceReady(fakeContext))

            // Activate voice package with its model & tokens blobs
            createFakeBlob(fakeContext, AiPackageSpec.voiceModelArtifact)
            createFakeBlob(fakeContext, AiPackageSpec.voiceTokensArtifact)
            AiPackageInstaller.activatePackage(fakeContext, AiPackageSpec.voicePackage)

            assertTrue(AiModuleManager.isVoiceReady(fakeContext))
            assertTrue(VoiceModelManager.isModelReady(fakeContext))
        } finally {
            tempDir.deleteRecursively()
            VoiceModelManager.invalidateCache()
        }
    }
}
