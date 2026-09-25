package org.bitfennec.lime.view.popup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.utils.dp

/**
 * Modern floating action capsule tooltip for gesture feedback.
 */
class PopupActionCapsuleUi(val ctx: Context) {

    private val ivIcon = ImageView(ctx).apply {
        val size = dp(18f)
        val margin = dp(8f)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL
            rightMargin = margin
            marginEnd = margin
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val tvText = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
    }

    private val contentLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dp(14f)
        val padV = dp(8f)
        setPadding(padH, padV, padH, padV)
        addView(ivIcon)
        addView(tvText)
    }

    val root: FrameLayout = FrameLayout(ctx).apply {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(6f).toFloat()
        clipToPadding = false
        addView(
            contentLayout,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private var cachedTheme: Theme? = null
    private var cachedBg: GradientDrawable? = null

    fun setContent(iconRes: Int, text: String, theme: Theme) {
        if (iconRes != 0) {
            ivIcon.visibility = View.VISIBLE
            ivIcon.setImageResource(iconRes)
            val accentColor = if (theme.accentKeyBackgroundColor != 0) theme.accentKeyBackgroundColor else ctx.getColor(R.color.gesture_select)
            when (iconRes) {
                R.drawable.ic_gesture_clear -> ivIcon.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.gesture_clear))
                R.drawable.ic_gesture_undo -> ivIcon.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.gesture_undo))
                R.drawable.ic_gesture_select -> ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
                R.drawable.ic_gesture_punctuation -> ivIcon.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.gesture_punctuation))
                else -> ivIcon.imageTintList = ColorStateList.valueOf(if (theme.isDark) Color.WHITE else ctx.getColor(R.color.gesture_capsule_icon_light))
            }
        } else {
            ivIcon.visibility = View.GONE
        }
        tvText.text = text

        if (cachedTheme != theme || cachedBg == null) {
            cachedTheme = theme
            val bg = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                val bgColor = if (theme.popupBackgroundColor != 0) {
                    theme.popupBackgroundColor
                } else if (theme.isDark) {
                    ctx.getColor(R.color.gesture_capsule_bg_dark)
                } else {
                    ctx.getColor(R.color.gesture_capsule_bg_light)
                }
                val strokeColor = if (theme.isDark) {
                    ctx.getColor(R.color.gesture_capsule_stroke_dark)
                } else {
                    ctx.getColor(R.color.gesture_capsule_stroke_light)
                }
                setColor(bgColor)
                setStroke(dp(1f), strokeColor)
            }
            cachedBg = bg
            contentLayout.background = bg

            val textColor = if (theme.keyTextColor != 0) {
                theme.keyTextColor
            } else if (theme.isDark) {
                Color.WHITE
            } else {
                ctx.getColor(R.color.gesture_capsule_text_light)
            }
            tvText.setTextColor(textColor)
        }
    }
}
