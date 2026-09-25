package org.bitfennec.lime.utils

import android.text.Layout
import kotlin.math.abs

object PreeditCursorUtils {

    /**
     * Maps raw Rime preedit cursor position to display cursor position in showComposition.
     * E.g. raw = "nihao", rawCursorPos = 2, show = "ni'hao" -> returns 3 (corresponding to "ni'|hao")
     */
    fun rawCursorPosToDisplayCursorPos(
        rawPreedit: String,
        rawCursorPos: Int,
        showComposition: String
    ): Int {
        if (rawPreedit.isEmpty() || showComposition.isEmpty()) return 0
        val clampedRawPos = rawCursorPos.coerceIn(0, rawPreedit.length)
        if (clampedRawPos == 0) return 0
        if (clampedRawPos == rawPreedit.length) return showComposition.length

        var rawMatched = 0
        for (i in showComposition.indices) {
            if (rawMatched < rawPreedit.length && showComposition[i] == rawPreedit[rawMatched]) {
                rawMatched++
                if (rawMatched == clampedRawPos) {
                    var nextIndex = i + 1
                    while (nextIndex < showComposition.length && showComposition[nextIndex] == '\'') {
                        nextIndex++
                    }
                    return nextIndex
                }
            }
        }
        return showComposition.length
    }

    /**
     * Converts touchOffset in TextView to raw preedit character index:
     * 1. Deducts 1 character offset of cursor placeholder ▎
     * 2. Inversely maps showComposition coordinates to rawPreedit character index
     */
    fun touchOffsetToRawIndex(
        touchOffset: Int,
        displayCursorPos: Int,
        showComposition: String,
        rawPreedit: String
    ): Int {
        if (rawPreedit.isEmpty() || showComposition.isEmpty()) return 0
        val displayIndex = if (touchOffset > displayCursorPos) touchOffset - 1 else touchOffset
        val clampedDisplayIndex = displayIndex.coerceIn(0, showComposition.length)
        if (clampedDisplayIndex == 0) return 0
        if (clampedDisplayIndex >= showComposition.length) return rawPreedit.length

        var rawCount = 0
        for (i in 0 until clampedDisplayIndex) {
            if (rawCount < rawPreedit.length && showComposition[i] == rawPreedit[rawCount]) {
                rawCount++
            }
        }
        return rawCount.coerceIn(0, rawPreedit.length)
    }

    /**
     * Pixel-level nearest character boundary snapping:
     * Uses Layout.getPrimaryHorizontal(i) to find closest character boundary to touch X,
     * eliminating dead zones near narrow glyphs (i, j, l) and separators.
     */
    fun findNearestRawIndex(
        layout: Layout,
        touchX: Float,
        displayCursorPos: Int,
        showComposition: String,
        rawPreedit: String
    ): Int {
        if (rawPreedit.isEmpty() || showComposition.isEmpty()) return 0
        val textLength = layout.text.length
        if (textLength == 0 || layout.lineCount <= 0) return 0

        var bestOffset = 0
        var minDistance = Float.MAX_VALUE
        for (i in 0..textLength) {
            val edgeX = try {
                layout.getPrimaryHorizontal(i)
            } catch (_: IndexOutOfBoundsException) {
                return 0
            }
            val dist = abs(edgeX - touchX)
            if (dist < minDistance) {
                minDistance = dist
                bestOffset = i
            }
        }
        return touchOffsetToRawIndex(bestOffset, displayCursorPos, showComposition, rawPreedit)
    }
}
