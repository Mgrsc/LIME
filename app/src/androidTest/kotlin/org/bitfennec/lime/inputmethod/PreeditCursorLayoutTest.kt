package org.bitfennec.lime.inputmethod

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bitfennec.lime.core.RimeComposition
import org.bitfennec.lime.utils.PreeditCursorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreeditCursorLayoutTest {
    @Test
    fun pixelHitsUseNativeBoundariesAcrossUnicodeAndExpandedSpellings() {
        val cases = listOf(
            Triple("l\u00fc you", intArrayOf(0, 1, 2, 3, 4, 5, 6), intArrayOf(0, 1, 2, 2, 3, 4, 5)),
            Triple("zhong guo", intArrayOf(0, 2, 5, 6, 7, 9), intArrayOf(0, 1, 2, 2, 3, 4)),
            Triple("\u4f60\u597dshi jie", intArrayOf(0, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(0, 5, 6, 7, 8, 8, 9, 10, 11)),
            Triple("\uD83D\uDE00a", intArrayOf(0, 2, 3), intArrayOf(0, 2, 3)),
        )
        val paint = TextPaint().apply { textSize = 32f }
        cases.forEach { (text, display, raw) ->
            raw.distinct().forEach { cursor ->
                val composition = CompositionPresentation.from(
                    RimeComposition(length = raw.last(), cursorPos = cursor, preedit = text,
                        displayBoundaries = display, rawBoundaries = raw),
                    revision = 1,
                )
                val rendered = text.substring(0, composition.displayCursor) + "\u258e" +
                    text.substring(composition.displayCursor)
                val layout = StaticLayout.Builder.obtain(rendered, 0, rendered.length, paint, 1000)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                composition.boundaries.forEach { boundary ->
                    val offset = boundary.displayOffset + if (boundary.displayOffset > composition.displayCursor) 1 else 0
                    assertEquals("$text: cursor=$cursor, display=${boundary.displayOffset}",
                        boundary.rawOffset,
                        PreeditCursorUtils.findNearestRawIndex(layout, layout.getPrimaryHorizontal(offset), composition))
                }
            }
        }
    }

    @Test
    fun opaquePreeditCannotCreateAnEditableRawPosition() {
        val text = "opaque"
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(), 1000).build()
        assertNull(PreeditCursorUtils.findNearestRawIndex(layout, 0f,
            CompositionPresentation(text = text, nativeDisplayCursor = 3)))
    }
}
