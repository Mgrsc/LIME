package org.bitfennec.lime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeServiceMirrorCompositionTest {

    private fun createEditorInfo(inputType: Int, imeOptions: Int = 0): EditorInfo {
        return EditorInfo().apply {
            this.inputType = inputType
            this.imeOptions = imeOptions
        }
    }

    @Test
    fun testNullAndModalBlocked() {
        assertFalse(ImeService.shouldMirrorCompositionToEditor(null, isModalInputBlocked = false))
        val filterEditor = createEditorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER)
        assertFalse(ImeService.shouldMirrorCompositionToEditor(filterEditor, isModalInputBlocked = true))
    }

    @Test
    fun testNonTextClassDoesNotMirror() {
        val numberEditor = createEditorInfo(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_SEARCH)
        assertFalse(ImeService.shouldMirrorCompositionToEditor(numberEditor, isModalInputBlocked = false))
    }

    @Test
    fun testNormalTextEditorsDoNotMirror() {
        // Normal single line text
        val normalText = createEditorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        assertFalse(ImeService.shouldMirrorCompositionToEditor(normalText, isModalInputBlocked = false))

        // Normal multiline message (e.g. chat editor 0x20001)
        val chatEditor = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_DONE
        )
        assertFalse(ImeService.shouldMirrorCompositionToEditor(chatEditor, isModalInputBlocked = false))
    }

    @Test
    fun testFilterVariationMirrors() {
        val filterEditor = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER,
            EditorInfo.IME_ACTION_NONE
        )
        assertTrue(ImeService.shouldMirrorCompositionToEditor(filterEditor, isModalInputBlocked = false))
    }

    @Test
    fun testSearchActionMirrors() {
        val searchEditor = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            EditorInfo.IME_ACTION_SEARCH
        )
        assertTrue(ImeService.shouldMirrorCompositionToEditor(searchEditor, isModalInputBlocked = false))
    }

    @Test
    fun testGoActionMirrors() {
        val goEditor = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            EditorInfo.IME_ACTION_GO
        )
        assertTrue(ImeService.shouldMirrorCompositionToEditor(goEditor, isModalInputBlocked = false))
    }
}
