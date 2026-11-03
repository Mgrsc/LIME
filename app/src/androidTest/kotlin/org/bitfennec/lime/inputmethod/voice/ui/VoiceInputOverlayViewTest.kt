package org.bitfennec.lime.inputmethod.voice.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bitfennec.lime.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceInputOverlayViewTest {

    private fun preparingHint(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.voice_overlay_engine_preparing)

    private fun containsPreparingHint(text: List<String>): Boolean {
        val hint = preparingHint()
        return text.any { it == hint || (it.endsWith("\u2026") && hint.startsWith(it.dropLast(1))) }
    }

    private fun renderedText(configure: VoiceInputOverlayView.() -> Unit): List<String> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text = ArrayList<String>()
        instrumentation.runOnMainSync {
            val overlay = VoiceInputOverlayView(instrumentation.targetContext)
            try {
                overlay.layout(0, 0, OVERLAY_WIDTH_PX, OVERLAY_HEIGHT_PX)
                overlay.show()
                overlay.configure()
                overlay.draw(TextRecordingCanvas(text))
            } finally {
                overlay.dismiss()
            }
        }
        return text
    }

    @Test
    fun emptyPreviewKeepsPreparingHint() {
        val text = renderedText {
            setEnginePreparing(true)
            updateLiveText("")
        }

        assertTrue(containsPreparingHint(text))
    }

    @Test
    fun recognizedPreviewReplacesPreparingHint() {
        val text = renderedText {
            setEnginePreparing(true)
            updateLiveText(PREVIEW_TEXT)
        }

        assertTrue(text.contains(PREVIEW_TEXT))
        assertFalse(containsPreparingHint(text))
    }

    @Test
    fun clearingRecognizedPreviewDoesNotRestorePreparingHint() {
        val text = renderedText {
            setEnginePreparing(true)
            updateLiveText(PREVIEW_TEXT)
            updateLiveText("")
        }

        assertFalse(text.contains(PREVIEW_TEXT))
        assertFalse(containsPreparingHint(text))
    }

    @Test
    fun newSessionCanShowPreparingHintAgain() {
        val text = renderedText {
            setEnginePreparing(true)
            updateLiveText(PREVIEW_TEXT)
            dismiss()
            show()
            setEnginePreparing(true)
        }

        assertTrue(containsPreparingHint(text))
        assertFalse(text.contains(PREVIEW_TEXT))
    }

    @Test
    fun lockedFinishButtonIsVisibleAndClickableAtCompactHeights() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val density = instrumentation.targetContext.resources.displayMetrics.density
            for (heightDp in listOf(168, 218, 320)) {
                val overlay = VoiceInputOverlayView(instrumentation.targetContext)
                var finishes = 0
                overlay.onLockFinishListener = { finishes++ }
                try {
                    val width = (350 * density).toInt()
                    val height = (heightDp * density).toInt()
                    overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    overlay.layout(0, 0, width, height)
                    overlay.show()
                    overlay.enterLockMode()
                    val canvas = BoundsRecordingCanvas()
                    overlay.draw(canvas)
                    val finish = canvas.buttons.single { it.width() >= 100 * density && it.height() >= 40 * density }
                    assertTrue(finish.left >= 0f && finish.top >= 0f)
                    assertTrue(finish.right <= width && finish.bottom <= height)
                    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, finish.centerX(), finish.centerY(), 0)
                    val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, finish.centerX(), finish.centerY(), 0)
                    try {
                        overlay.onTouchEvent(down)
                        overlay.onTouchEvent(up)
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                    assertEquals(1, finishes)
                } finally {
                    overlay.dismiss()
                }
            }
        }
    }

    @Test
    fun lockedFinishHitAreaUpdatesBeforeDrawingAfterResize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val overlay = VoiceInputOverlayView(instrumentation.targetContext)
            var finishes = 0
            overlay.onLockFinishListener = { finishes++ }
            try {
                val width = (350 * density).toInt()
                overlay.layout(0, 0, width, (320 * density).toInt())
                overlay.show()
                overlay.enterLockMode()
                overlay.draw(BoundsRecordingCanvas())
                val height = (168 * density).toInt()
                overlay.layout(0, 0, width, height)
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, width / 2f, height - 30 * density, 0)
                try {
                    overlay.onTouchEvent(event)
                } finally {
                    event.recycle()
                }
                assertEquals(1, finishes)
            } finally {
                overlay.dismiss()
            }
        }
    }

    @Test
    fun longPreviewFitsNarrowOverlayInBothRecordingModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val overlay = VoiceInputOverlayView(instrumentation.targetContext)
            try {
                val width = (350 * density).toInt()
                overlay.layout(0, 0, width, (168 * density).toInt())
                overlay.show()
                overlay.updateLiveText("recognized preview ".repeat(20))
                for (locked in listOf(false, true)) {
                    if (locked) overlay.enterLockMode()
                    val canvas = BoundsRecordingCanvas()
                    overlay.draw(canvas)
                    assertTrue(canvas.textBounds.isNotEmpty())
                    canvas.textBounds.forEach { bounds ->
                        assertTrue(bounds.left >= -1f && bounds.right <= width + 1f)
                        assertTrue(bounds.top >= -1f && bounds.bottom <= overlay.height + 1f)
                    }
                }
            } finally {
                overlay.dismiss()
            }
        }
    }

    private class BoundsRecordingCanvas : Canvas() {
        val buttons = mutableListOf<RectF>()
        val textBounds = mutableListOf<RectF>()

        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            buttons.add(RectF(rect))
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val halfWidth = paint.measureText(text) / 2f
            textBounds.add(RectF(x - halfWidth, y + paint.ascent(), x + halfWidth, y + paint.descent()))
        }
    }

    private class TextRecordingCanvas(private val text: MutableList<String>) : Canvas() {
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            this.text.add(text)
        }
    }

    private companion object {
        const val OVERLAY_WIDTH_PX = 1080
        const val OVERLAY_HEIGHT_PX = 640
        const val PREVIEW_TEXT = "recognized preview"
    }
}
