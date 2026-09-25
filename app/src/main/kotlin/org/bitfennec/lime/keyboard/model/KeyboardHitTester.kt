package org.bitfennec.lime.keyboard.model

import kotlin.math.max
import kotlin.math.min

data class KeyCenterOffset(val dx: Float, val dy: Float)

fun interface KeyCenterOffsetProvider {
    fun offsetFor(layoutId: String, key: SoftKey): KeyCenterOffset?
}

object KeyboardHitTester {
    fun updateHitRects(rows: List<List<SoftKey>>) {
        val rowBounds = rows.map { row ->
            val top = row.minOfOrNull { it.mTop } ?: 0
            val bottom = row.maxOfOrNull { it.mBottom } ?: 0
            top to bottom
        }
        rows.forEachIndexed { rowIndex, row ->
            val (rowTop, rowBottom) = rowBounds[rowIndex]
            val top = if (rowIndex > 0) {
                (rowBounds[rowIndex - 1].second + rowTop) / 2
            } else {
                rowTop
            }
            val bottom = if (rowIndex < rows.lastIndex) {
                (rowBottom + rowBounds[rowIndex + 1].first) / 2
            } else {
                rowBottom
            }
            val keys = row.sortedBy { it.mLeft }
            keys.forEachIndexed { index, key ->
                val edgeExpansion = (key.width() * EDGE_HIT_EXPANSION_RATIO).toInt()
                val left = if (index > 0) {
                    (keys[index - 1].mRight + key.mLeft) / 2
                } else {
                    key.mLeft - edgeExpansion
                }
                val right = if (index < keys.lastIndex) {
                    (key.mRight + keys[index + 1].mLeft) / 2
                } else {
                    key.mRight + edgeExpansion
                }
                key.setHitBounds(
                    min(left, key.mLeft),
                    min(top, key.mTop),
                    max(right, key.mRight),
                    max(bottom, key.mBottom),
                )
            }
        }
    }

    fun mapToKey(
        rows: List<List<SoftKey>>,
        x: Int,
        y: Int,
        hysteresisKey: SoftKey? = null,
        layoutId: String = "",
        offsetProvider: KeyCenterOffsetProvider? = null,
    ): SoftKey? {
        if (hysteresisKey?.containsHitPoint(x, y) == true) {
            return hysteresisKey
        }
        var bestCoreKey: SoftKey? = null
        var bestCoreDistance = Float.MAX_VALUE
        rows.forEach { row ->
            row.forEach { key ->
                val offset = offsetProvider?.offsetFor(layoutId, key)
                if (key.containsCorePoint(x, y, offset)) {
                    val distance = key.centerDistanceSquaredTo(x, y, offset)
                    if (distance < bestCoreDistance) {
                        bestCoreKey = key
                        bestCoreDistance = distance
                    }
                }
            }
        }
        if (bestCoreKey != null) {
            return bestCoreKey
        }
        rows.forEach { row ->
            row.forEach { key ->
                if (key.containsHitPoint(x, y)) return key
            }
        }
        return null
    }

    private fun SoftKey.containsHitPoint(x: Int, y: Int): Boolean =
        hitLeft <= x && hitTop <= y && hitRight > x && hitBottom > y

    private fun SoftKey.containsCorePoint(x: Int, y: Int, offset: KeyCenterOffset?): Boolean {
        val dx = offset?.dx ?: 0f
        val dy = offset?.dy ?: 0f
        return mLeft + dx <= x && mTop + dy <= y && mRight + dx > x && mBottom + dy > y
    }

    private fun SoftKey.centerDistanceSquaredTo(x: Int, y: Int, offset: KeyCenterOffset?): Float {
        val dx = offset?.dx ?: 0f
        val dy = offset?.dy ?: 0f
        val centerX = (mLeft + mRight) / 2f + dx
        val centerY = (mTop + mBottom) / 2f + dy
        val distanceX = x - centerX
        val distanceY = y - centerY
        return distanceX * distanceX + distanceY * distanceY
    }

    private const val EDGE_HIT_EXPANSION_RATIO = 0.2f
}
