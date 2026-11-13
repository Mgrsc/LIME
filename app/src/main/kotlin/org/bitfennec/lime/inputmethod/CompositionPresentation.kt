package org.bitfennec.lime.inputmethod

import org.bitfennec.lime.core.RimeComposition
import kotlin.math.abs

data class PreeditBoundary(val displayOffset: Int, val rawOffset: Int)

data class CompositionPresentation(
    val text: String = "",
    val rawCursor: Int = 0,
    val boundaries: List<PreeditBoundary> = emptyList(),
    val revision: Long = 0L,
    val nativeDisplayCursor: Int? = null,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
) {
    val displayCursor: Int
        get() = nativeDisplayCursor ?: boundaries.minWithOrNull(
            compareBy<PreeditBoundary> { abs(it.rawOffset - rawCursor) }
                .thenByDescending { it.displayOffset },
        )?.displayOffset ?: text.length

    fun rawOffsetForDisplay(offset: Int): Int? = boundaries.minWithOrNull(
        compareBy<PreeditBoundary> { abs(it.displayOffset - offset) }
            .thenByDescending { it.displayOffset },
    )?.rawOffset

    companion object {
        val Empty = CompositionPresentation()

        fun from(composition: RimeComposition, revision: Long): CompositionPresentation {
            val text = composition.preedit.orEmpty()
            val display = composition.displayBoundaries
            val raw = composition.rawBoundaries
            val valid = display.isNotEmpty() && display.size == raw.size &&
                display.first() == 0 && display.last() == text.length &&
                display.indices.all { index ->
                    display[index] in 0..text.length && raw[index] in 0..composition.length &&
                        (display[index] == 0 || display[index] == text.length ||
                            !Character.isLowSurrogate(text[display[index]])) &&
                        (index == 0 || display[index] > display[index - 1] && raw[index] >= raw[index - 1])
                }
            return CompositionPresentation(
                text = text,
                rawCursor = composition.cursorPos,
                boundaries = if (valid) display.indices.map { PreeditBoundary(display[it], raw[it]) } else emptyList(),
                revision = revision,
                nativeDisplayCursor = composition.displayCursorPos.takeIf {
                    it in 0..text.length && (it == 0 || it == text.length || !Character.isLowSurrogate(text[it]))
                },
                selectionStart = composition.selStart.coerceIn(0, text.length),
                selectionEnd = composition.selEnd.coerceIn(0, text.length),
            )
        }
    }
}
