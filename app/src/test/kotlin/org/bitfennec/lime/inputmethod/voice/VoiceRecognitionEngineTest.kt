package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceRecognitionEngineTest {

    @Test
    fun testVoiceRecognitionEngineHandlesMissingModelsGracefully() {
        val fakeContext = object : android.content.ContextWrapper(null) {
            private val tempDir = File.createTempFile("lime-voice-test", "").apply {
                delete()
                mkdirs()
            }
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): android.content.Context = this
        }

        VoiceRecognitionEngine.release()
        assertEquals(VoiceRecognitionEngine.EngineState.UNINITIALIZED, VoiceRecognitionEngine.getEngineState())

        val latch = CountDownLatch(1)
        var initSuccess = true
        VoiceRecognitionEngine.init(fakeContext) { success ->
            initSuccess = success
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        assertFalse("Initialization should report failure when model files are missing", initSuccess)
        assertFalse("Engine should not be ready", VoiceRecognitionEngine.isReady())

        // release must be idempotent
        VoiceRecognitionEngine.release()
        VoiceRecognitionEngine.release()
        assertEquals(VoiceRecognitionEngine.EngineState.UNINITIALIZED, VoiceRecognitionEngine.getEngineState())
    }

    @Test
    fun testLeasePinningAndTranscribeSyncSafety() {
        VoiceRecognitionEngine.release()
        assertEquals(VoiceRecognitionEngine.EngineState.UNINITIALIZED, VoiceRecognitionEngine.getEngineState())

        // Acquire lease and ensure releaseLease is balanced
        VoiceRecognitionEngine.acquireLease()
        VoiceRecognitionEngine.acquireLease()
        VoiceRecognitionEngine.releaseLease()
        VoiceRecognitionEngine.releaseLease()

        // transcribeSync on empty samples or uninitialized engine should safely return empty without hanging
        val emptyResult = VoiceRecognitionEngine.transcribeSync(FloatArray(0))
        assertEquals("", emptyResult)

        val uninitializedResult = VoiceRecognitionEngine.transcribeSync(FloatArray(100), timeoutMs = 100L)
        assertEquals("", uninitializedResult)
    }

    @Test
    fun testReleaseDuringInitializationCancelsPendingCallbacks() {
        val fakeContext = object : android.content.ContextWrapper(null) {
            private val tempDir = File.createTempFile("lime-voice-test-cancel", "").apply {
                delete()
                mkdirs()
            }
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): android.content.Context = this
        }

        VoiceRecognitionEngine.release()
        assertEquals(VoiceRecognitionEngine.EngineState.UNINITIALIZED, VoiceRecognitionEngine.getEngineState())

        val latch = CountDownLatch(1)
        var callbackResult: Boolean? = null
        VoiceRecognitionEngine.init(fakeContext) { success ->
            callbackResult = success
            latch.countDown()
        }

        // Immediately release while initialization is in-flight
        VoiceRecognitionEngine.release()
        assertEquals(VoiceRecognitionEngine.EngineState.UNINITIALIZED, VoiceRecognitionEngine.getEngineState())

        latch.await(5, TimeUnit.SECONDS)
        assertEquals("Pending callback should be notified with false on release", false, callbackResult)
        assertFalse("Engine should remain uninitialized and not ready", VoiceRecognitionEngine.isReady())
    }

    @Test
    fun testServiceLifecycleScopePersistenceAcrossSimulatedDestruction() {
        val fakeContext = object : android.content.ContextWrapper(null) {
            private val tempDir = File.createTempFile("lime-voice-test-lifecycle", "").apply {
                delete()
                mkdirs()
            }
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): android.content.Context = this
        }

        // 1. First Service lifecycle: init and release
        val latch1 = CountDownLatch(1)
        VoiceRecognitionEngine.init(fakeContext) { latch1.countDown() }
        latch1.await(5, TimeUnit.SECONDS)

        // Simulate ImeService.onDestroy() (release without shutting down process-level ImeDispatchers)
        VoiceRecognitionEngine.release()
        org.bitfennec.lime.core.HandwritingEngine.release()
        assertEquals(VoiceRecognitionEngine.EngineState.UNINITIALIZED, VoiceRecognitionEngine.getEngineState())

        // 2. Second Service lifecycle: init again
        val latch2 = CountDownLatch(1)
        var secondInitCompleted = false
        VoiceRecognitionEngine.init(fakeContext) {
            secondInitCompleted = true
            latch2.countDown()
        }
        latch2.await(5, TimeUnit.SECONDS)

        org.junit.Assert.assertTrue("Engine scope should remain active and complete init in subsequent Service lifecycle", secondInitCompleted)
        VoiceRecognitionEngine.release()
    }

    @Test
    fun testReloadCancelsStaleGenerationAndCompletesNewInit() {
        val fakeContext = object : android.content.ContextWrapper(null) {
            private val tempDir = File.createTempFile("lime-voice-test-reload", "").apply {
                delete()
                mkdirs()
            }
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): android.content.Context = this
        }

        VoiceRecognitionEngine.release()
        val latch = CountDownLatch(1)
        var reloadResult: Boolean? = null
        VoiceRecognitionEngine.reload(fakeContext) { success ->
            reloadResult = success
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        assertEquals("Reload callback should be notified", false, reloadResult)
        VoiceRecognitionEngine.release()
    }
}
