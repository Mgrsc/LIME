package org.bitfennec.lime.keyboard.container

import org.bitfennec.lime.inputmethod.voice.ui.VoiceOverlayLayout
import org.bitfennec.lime.data.emojicon.EmojiconData
import org.bitfennec.lime.prefs.behavior.SymbolMode
import org.bitfennec.lime.utils.EditButtonLayout
import org.bitfennec.lime.utils.calculateGridSpanCount
import org.bitfennec.lime.utils.resolveEditButtonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutTest {
    @Test
    fun gridColumnsRespectContentWidthAndFontGrowth() {
        assertEquals(7, calculateGridSpanCount(342, 48f))
        assertEquals(8, calculateGridSpanCount(422, 48f))
        assertEquals(5, calculateGridSpanCount(342, 64f))
        assertEquals(2, calculateGridSpanCount(350, 144f))
        assertEquals(1, calculateGridSpanCount(0, 48f))
        assertEquals(1, calculateGridSpanCount(32, 48f))
    }

    @Test
    fun editingButtonsUseAvailableHeightAndKeepLabelsWhenTheyFit() {
        assertEquals(EditButtonLayout.Stacked, resolveEditButtonLayout(80, 42, 22, 30f, 14, 2, 8))
        assertEquals(EditButtonLayout.Inline, resolveEditButtonLayout(80, 23, 19, 30f, 14, 4, 8))
        assertEquals(EditButtonLayout.IconOnly, resolveEditButtonLayout(55, 23, 19, 50f, 14, 4, 8))
        assertEquals(EditButtonLayout.IconOnly, resolveEditButtonLayout(80, 23, 19, 30f, 28, 4, 8))
    }

    @Test
    fun compactVoiceControlsRemainVisibleAndSeparated() {
        for (height in listOf(150f, 168f, 218f, 242f, 400f)) {
            val layout = VoiceOverlayLayout.calculate(height)
            assertTrue(layout.finishTop >= 0f)
            assertTrue(layout.finishTop + VoiceOverlayLayout.FINISH_HEIGHT_DP <= height)
            assertTrue(layout.lockedModeBaseline + VoiceOverlayLayout.MODE_HALF_HEIGHT_DP <= layout.finishTop)
            assertTrue(layout.lockedPreviewBaseline + 4f <= layout.lockedModeBaseline - VoiceOverlayLayout.MODE_HALF_HEIGHT_DP)
            assertTrue(layout.targetTop + VoiceOverlayLayout.TARGET_HEIGHT_DP <= layout.previewBaseline - 16f)
            assertTrue(layout.modeBaseline + VoiceOverlayLayout.MODE_HALF_HEIGHT_DP <= layout.anchorTop)
            assertTrue(layout.anchorTop + layout.anchorHeight <= height)
        }
    }

    @Test
    fun symbolStateOnlyRestoresInItsUnrestrictedEditorSession() {
        val state = SymbolPanelState(7, SymbolMode.Emojicon, EmojiconData.SymbolCategory.COMMON,
            null, EmojiconData.SymbolMoreSubCategory.PARENTHESES, true)
        assertTrue(state.canRestore(7, false))
        assertFalse(state.canRestore(8, false))
        assertFalse(state.canRestore(7, true))
    }
}
