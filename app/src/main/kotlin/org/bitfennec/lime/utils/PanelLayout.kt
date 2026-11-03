package org.bitfennec.lime.utils

import kotlin.math.floor

internal fun calculateGridSpanCount(availableWidth: Int, minimumItemWidth: Float): Int {
    if (availableWidth <= 0 || minimumItemWidth <= 0f) return 1
    return floor(availableWidth / minimumItemWidth).toInt().coerceAtLeast(1)
}

internal enum class EditButtonLayout { Stacked, Inline, IconOnly }

internal fun resolveEditButtonLayout(
    width: Int,
    height: Int,
    iconSize: Int,
    textWidth: Float,
    textHeight: Int,
    gap: Int,
    horizontalPadding: Int
): EditButtonLayout = when {
    height >= iconSize + gap + textHeight -> EditButtonLayout.Stacked
    height >= textHeight && width >= iconSize + gap + textWidth + horizontalPadding -> EditButtonLayout.Inline
    else -> EditButtonLayout.IconOnly
}
