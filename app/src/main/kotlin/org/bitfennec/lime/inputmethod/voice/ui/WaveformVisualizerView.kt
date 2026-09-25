package org.bitfennec.lime.inputmethod.voice.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import org.bitfennec.lime.data.theme.ThemeManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 15-bar dynamic waveform visualizer.
 * Features 60fps physics, fast attack response, and smooth decay damping.
 */
class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 15
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()

    private val currentHeights = FloatArray(barCount)
    private val targetHeights = FloatArray(barCount)

    private var animPhase = 0f

    init {
        for (i in 0 until barCount) {
            currentHeights[i] = 0.1f
            targetHeights[i] = 0.1f
        }
    }

    /** Updates input audio amplitude (0.0f .. 1.0f). */
    fun updateAmplitude(amplitude: Float) {
        val clamped = min(1.0f, max(0.06f, amplitude))
        val center = barCount / 2f

        for (i in 0 until barCount) {
            // Center Gaussian envelope weighting
            val dist = (i - center) / (barCount / 2.6f)
            val envelope = exp(-(dist * dist).toDouble()).toFloat()

            // Multi-frequency harmonics for organic bar animation
            val waveMod = 0.75f + (sin(animPhase * 2.0 + i * 0.8) * 0.25).toFloat()
            val valForBar = clamped * envelope * waveMod

            targetHeights[i] = min(1.0f, max(0.08f, valForBar))
        }
        invalidate()
    }

    fun reset() {
        for (i in 0 until barCount) {
            targetHeights[i] = 0.08f
            currentHeights[i] = 0.08f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val isNight = ThemeManager.activeTheme.isDark
        barPaint.color = if (isNight) "#0A84FF".toColorInt() else "#007AFF".toColorInt()

        val barWidth = dp2px(3.0f)
        val spacing = dp2px(3.0f)
        val totalWidth = barCount * barWidth + (barCount - 1) * spacing
        val startX = (w - totalWidth) / 2f

        val minBarHeight = dp2px(5.0f)
        val maxBarHeight = dp2px(24.0f)
        val centerY = h / 2f

        animPhase += 0.12f
        var needInvalidate = false

        for (i in 0 until barCount) {
            val breath = (sin(animPhase + i * 0.6) * 0.04).toFloat()
            val effectiveTarget = max(0.08f, targetHeights[i] + breath)

            // Fast attack and smooth decay damping
            val speed = if (effectiveTarget > currentHeights[i]) 0.55f else 0.25f
            currentHeights[i] += (effectiveTarget - currentHeights[i]) * speed

            if (abs(effectiveTarget - currentHeights[i]) > 0.005f) {
                needInvalidate = true
            }

            val barHeight = minBarHeight + currentHeights[i] * (maxBarHeight - minBarHeight)
            val left = startX + i * (barWidth + spacing)
            val top = centerY - barHeight / 2f
            val right = left + barWidth
            val bottom = centerY + barHeight / 2f

            barRect.set(left, top, right, bottom)
            val cornerRadius = barWidth / 2f
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }

        if (needInvalidate) {
            postInvalidateOnAnimation()
        }
    }

    private fun dp2px(dp: Float): Float {
        return context.resources.displayMetrics.density * dp
    }
}
