package org.bitfennec.lime.core

import org.bitfennec.lime.core.runtime.AiModuleManager
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class HandwritingEngineTest {

    @Test
    fun testModuleNotReadyWhenFilesMissing() {
        val fakeContext = object : android.content.ContextWrapper(null) {
            private val tempDir = File.createTempFile("lime-test-files", "").apply {
                delete()
                mkdirs()
            }
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): android.content.Context = this
        }

        assertFalse("Handwriting should not be ready in fresh empty files dir",
            AiModuleManager.isHandwritingReady(fakeContext))
        assertFalse("Voice should not be ready in fresh empty files dir",
            AiModuleManager.isVoiceReady(fakeContext))
    }

    @Test
    fun testHandwritingEngineReleaseIdempotent() {
        // Must execute safely without throwing any exceptions even when not initialized
        HandwritingEngine.release()
        HandwritingEngine.release()
    }

    @Test
    fun testHandwritingEngineIdleUnloadScheduleAndCancel() {
        try {
            HandwritingEngine.scheduleIdleUnload(60_000L)
            val job = HandwritingEngine.idleUnloadJob
            org.junit.Assert.assertNotNull("Idle unload job should be scheduled", job)
            org.junit.Assert.assertTrue("Idle unload job should be active when scheduled", job?.isActive == true)

            HandwritingEngine.cancelIdleUnload()
            org.junit.Assert.assertNull("Idle unload job should be nulled when cancelled", HandwritingEngine.idleUnloadJob)
            org.junit.Assert.assertTrue("Cancelled job should be cancelled", job?.isCancelled == true)
        } finally {
            HandwritingEngine.cancelIdleUnload()
            HandwritingEngine.release()
        }
    }

    @Test
    fun testRasterizeStrokesToOcrTensor() {
        // Construct simple cross stroke
        val strokeHorizontal = listOf(
            android.graphics.PointF().apply { x = 10f; y = 24f },
            android.graphics.PointF().apply { x = 38f; y = 24f },
        )
        val strokeVertical = listOf(
            android.graphics.PointF().apply { x = 24f; y = 10f },
            android.graphics.PointF().apply { x = 24f; y = 38f },
        )
        val strokes = listOf(strokeHorizontal, strokeVertical)

        val buffer = HandwritingEngine.rasterizeStrokesToOcrTensor(strokes)
        org.junit.Assert.assertNotNull(buffer)
        org.junit.Assert.assertEquals("Buffer capacity must equal 1 * 3 * 48 * 320", 1 * 3 * 48 * 320, buffer.capacity())

        // Verify channel 0
        // 1. Verify right padding region [48..319] is all 0.0f
        var allPaddingZero = true
        for (y in 0 until 48) {
            for (x in 48 until 320) {
                val v = buffer.get(0 * 48 * 320 + y * 320 + x)
                if (v != 0.0f) {
                    allPaddingZero = false
                    break
                }
            }
        }
        org.junit.Assert.assertTrue("Paddle padding columns [48..319] must be exactly 0.0f", allPaddingZero)

        // 2. Verify left block background is 1.0f ((255 / 255.0 - 0.5) / 0.5)
        val cornerPixel = buffer.get(0 * 48 * 320 + 0 * 320 + 0)
        org.junit.Assert.assertEquals("Background pixel must normalize to 1.0f", 1.0f, cornerPixel, 1e-4f)

        // 3. Verify stroke intersection point (24, 24) is -1.0f ((0 / 255.0 - 0.5) / 0.5)
        val centerPixel = buffer.get(0 * 48 * 320 + 24 * 320 + 24)
        org.junit.Assert.assertEquals("Black ink stroke center must normalize to -1.0f", -1.0f, centerPixel, 1e-4f)
    }
}
