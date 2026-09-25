package org.bitfennec.lime.view.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import org.bitfennec.lime.keyboard.HandwritingKeyboard

/**
 * Live handwriting stroke thickness preview view.
 * Renders Chinese calligraphy character strokes and dynamic Bezier curves
 * that update in real time with SeekBar adjustments.
 */
class HandwritingStrokePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var thicknessPercent: Int = 35

    private val cardBounds = RectF()
    private val strokePath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setThicknessPercent(percent: Int) {
        this.thicknessPercent = percent.coerceIn(0, 100)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density

        // Check dark mode
        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        // 1. Draw canvas background
        cardBounds.set(0f, 0f, w, h)
        val cornerRadius = 12f * density

        if (isDarkMode) {
            bgPaint.color = "#181B20".toColorInt()
            borderPaint.color = "#334155".toColorInt()
            borderPaint.strokeWidth = 1f * density
            gridPaint.color = "#263345".toColorInt()
            gridPaint.strokeWidth = 1f * density
            strokePaint.color = "#FFFFFF".toColorInt()
            accentStrokePaint.color = "#60A5FA".toColorInt()
        } else {
            bgPaint.color = "#F8FAFC".toColorInt()
            borderPaint.color = "#CBD5E1".toColorInt()
            borderPaint.strokeWidth = 1f * density
            gridPaint.color = "#E2E8F0".toColorInt()
            gridPaint.strokeWidth = 1f * density
            strokePaint.color = "#0F172A".toColorInt()
            accentStrokePaint.color = "#2563EB".toColorInt()
        }

        canvas.drawRoundRect(cardBounds, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(cardBounds, cornerRadius, cornerRadius, borderPaint)

        // 2. Draw guide grid lines
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        canvas.drawLine(w * 0.45f, 0f, w * 0.45f, h, gridPaint)

        // 3. Compute stroke width from unified HandwritingKeyboard mapping
        val realMaxStrokeDp = HandwritingKeyboard.computeStrokeMaxWidthDp(thicknessPercent)
        // yagni: preview card (~70dp character box) scales strokes proportionally (0.35x) to match full keyboard canvas (~200dp character height); upgrade when preview auto-scales with canvas metrics
        val calligraphyScale = 0.35f
        val baseWidthPx = realMaxStrokeDp * calligraphyScale * density

        // 4. Left area: Render Yong calligraphy stroke components (dot, horizontal, vertical, hook)
        // Dot (Drop)
        strokePaint.strokeWidth = baseWidthPx * 1.25f
        strokePath.apply {
            rewind()
            moveTo(w * 0.22f, h * 0.22f)
            lineTo(w * 0.23f, h * 0.26f)
        }
        canvas.drawPath(strokePath, strokePaint)

        // Horizontal & Turn
        strokePaint.strokeWidth = baseWidthPx * 0.85f
        strokePath.apply {
            rewind()
            moveTo(w * 0.12f, h * 0.40f)
            lineTo(w * 0.32f, h * 0.38f)
            lineTo(w * 0.32f, h * 0.48f)
        }
        canvas.drawPath(strokePath, strokePaint)

        // Vertical & Hook
        strokePaint.strokeWidth = baseWidthPx * 1.15f
        strokePath.apply {
            rewind()
            moveTo(w * 0.22f, h * 0.36f)
            lineTo(w * 0.22f, h * 0.76f)
            lineTo(w * 0.16f, h * 0.70f)
        }
        canvas.drawPath(strokePath, strokePaint)

        // Left & Right strokes
        strokePaint.strokeWidth = baseWidthPx * 0.75f
        strokePath.apply {
            rewind()
            moveTo(w * 0.18f, h * 0.50f)
            quadTo(w * 0.12f, h * 0.65f, w * 0.08f, h * 0.72f)
        }
        canvas.drawPath(strokePath, strokePaint)

        strokePaint.strokeWidth = baseWidthPx * 1.1f
        strokePath.apply {
            rewind()
            moveTo(w * 0.24f, h * 0.56f)
            quadTo(w * 0.30f, h * 0.68f, w * 0.38f, h * 0.78f)
        }
        canvas.drawPath(strokePath, strokePaint)

        // 5. Right area: Render continuous cubic Bezier curve
        accentStrokePaint.strokeWidth = baseWidthPx
        strokePath.apply {
            rewind()
            moveTo(w * 0.52f, h * 0.68f)
            cubicTo(w * 0.62f, h * 0.15f, w * 0.74f, h * 0.88f, w * 0.88f, h * 0.30f)
        }
        canvas.drawPath(strokePath, accentStrokePaint)

        // Trailing cursive stroke
        accentStrokePaint.strokeWidth = baseWidthPx * 0.65f
        strokePath.apply {
            rewind()
            moveTo(w * 0.88f, h * 0.30f)
            quadTo(w * 0.93f, h * 0.42f, w * 0.91f, h * 0.68f)
        }
        canvas.drawPath(strokePath, accentStrokePaint)
    }
}
