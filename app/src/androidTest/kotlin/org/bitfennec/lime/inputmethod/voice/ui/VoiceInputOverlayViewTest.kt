package org.bitfennec.lime.inputmethod.voice.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bitfennec.lime.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceInputOverlayViewTest {

    private fun preparingHint(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.voice_overlay_engine_preparing)

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

        assertTrue(text.contains(preparingHint()))
    }

    @Test
    fun recognizedPreviewReplacesPreparingHint() {
        val text = renderedText {
            setEnginePreparing(true)
            updateLiveText(PREVIEW_TEXT)
        }

        assertTrue(text.contains(PREVIEW_TEXT))
        assertFalse(text.contains(preparingHint()))
    }

    @Test
    fun clearingRecognizedPreviewDoesNotRestorePreparingHint() {
        val text = renderedText {
            setEnginePreparing(true)
            updateLiveText(PREVIEW_TEXT)
            updateLiveText("")
        }

        assertFalse(text.contains(PREVIEW_TEXT))
        assertFalse(text.contains(preparingHint()))
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

        assertTrue(text.contains(preparingHint()))
        assertFalse(text.contains(PREVIEW_TEXT))
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
