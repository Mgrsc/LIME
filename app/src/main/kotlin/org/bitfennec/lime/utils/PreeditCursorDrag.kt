package org.bitfennec.lime.utils

import org.bitfennec.lime.inputmethod.CompositionPresentation
import kotlin.math.abs

internal class PreeditCursorDrag(
    private val touchSlop: Float,
    private val stepDistance: Float,
) {
    var isDragging = false
        private set

    private var downX = 0f
    private var lastStepX = 0f
    private var positions = emptyList<Int>()
    private var positionIndex = 0

    fun start(x: Float, composition: CompositionPresentation) {
        isDragging = false
        downX = x
        lastStepX = x
        positions = composition.boundaries.map { it.rawOffset }.distinct()
        positionIndex = positions.indices.minByOrNull {
            abs(positions[it] - composition.rawCursor)
        } ?: 0
    }

    fun move(x: Float, moveCursor: (Int) -> Boolean) {
        if (positions.isEmpty()) return
        if (!isDragging) {
            val distance = x - downX
            if (abs(distance) <= touchSlop) return
            isDragging = true
        }
        val steps = ((x - lastStepX) / stepDistance).toInt()
        if (steps == 0) return
        val targetIndex = (positionIndex + steps).coerceIn(positions.indices)
        if (targetIndex == positionIndex) {
            lastStepX = x
            return
        }
        if (moveCursor(positions[targetIndex])) {
            val movedSteps = targetIndex - positionIndex
            positionIndex = targetIndex
            lastStepX = if (movedSteps == steps) lastStepX + steps * stepDistance else x
        }
    }

    fun reset() {
        isDragging = false
        positions = emptyList()
        positionIndex = 0
    }
}
