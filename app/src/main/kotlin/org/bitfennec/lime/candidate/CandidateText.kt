package org.bitfennec.lime.candidate

import android.content.res.Resources
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import org.bitfennec.lime.R

/** Adds glyphs missing from the device font without changing candidate text or indices. */
class CandidateText(private val resources: Resources) {
    private val fallbackFonts by lazy {
        listOf(
            resources.getFont(R.font.lime_candidate_fallback),
            resources.getFont(R.font.lime_candidate_extra)
        )
    }

    fun format(text: String, paint: Paint): CharSequence {
        var styled: SpannableString? = null
        var fallbackPaint: Paint? = null
        var start = 0
        while (start < text.length) {
            val codePoint = text.codePointAt(start)
            var end = start + Character.charCount(codePoint)
            // Keep an ideograph and its variation selector in the same font run.
            if (end < text.length) {
                val next = text.codePointAt(end)
                if (next in 0xFE00..0xFE0F || next in 0xE0100..0xE01EF) {
                    end += Character.charCount(next)
                }
            }
            // The bundled subset covers supplementary Han, including characters newer than ART.
            if (codePoint in 0x20000..0x3FFFF) {
                val glyph = text.substring(start, end)
                if (!paint.hasGlyph(glyph)) {
                    val probe = fallbackPaint ?: Paint(paint).also { fallbackPaint = it }
                    val font = fallbackFonts.firstOrNull {
                        probe.typeface = it
                        probe.hasGlyph(glyph)
                    }
                    if (font != null) {
                        val result = styled ?: SpannableString(text).also { styled = it }
                        result.setSpan(TypefaceSpan(font), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            start = end
        }
        return styled ?: text
    }
}
