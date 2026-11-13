package org.bitfennec.lime.utils

import android.text.Layout
import org.bitfennec.lime.inputmethod.CompositionPresentation
import kotlin.math.abs

object PreeditCursorUtils {
    fun touchOffsetToRawIndex(touchOffset: Int, composition: CompositionPresentation): Int? {
        val displayOffset = if (touchOffset > composition.displayCursor) touchOffset - 1 else touchOffset
        return composition.rawOffsetForDisplay(displayOffset.coerceIn(0, composition.text.length))
    }

    fun findNearestRawIndex(layout: Layout, touchX: Float, composition: CompositionPresentation): Int? {
        if (layout.lineCount <= 0) return null
        var closestRaw: Int? = null
        var minDistance = Float.MAX_VALUE
        for (boundary in composition.boundaries) {
            val offsets = if (boundary.displayOffset == composition.displayCursor) {
                intArrayOf(boundary.displayOffset, boundary.displayOffset + 1)
            } else {
                intArrayOf(boundary.displayOffset + if (boundary.displayOffset > composition.displayCursor) 1 else 0)
            }
            for (offset in offsets) {
                if (offset > layout.text.length) return null
                val distance = abs(layout.getPrimaryHorizontal(offset) - touchX)
                if (distance < minDistance) {
                    minDistance = distance
                    closestRaw = boundary.rawOffset
                }
            }
        }
        return closestRaw
    }
}
