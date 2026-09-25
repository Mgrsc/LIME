package org.bitfennec.lime.view.popup

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.dp

class PopupEntryUi(val ctx: Context) {

    var lastShowTime = -1L
    private val background = GradientDrawable()

    val textView = AutoScaleTextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ImeEnvironment.keyTextSize * 1.2f)
        scaleMode = AutoScaleTextView.Mode.Proportional
    }

    val root: FrameLayout = FrameLayout(ctx).apply {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f).toFloat()
        addView(textView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setText(text: String) {
        textView.text = text
    }

    fun setBackground(theme: Theme, radius: Float) {
        background.cornerRadius = radius
        background.setColor(theme.popupBackgroundColor)
        if (root.background !== background) root.background = background
        textView.setTextColor(theme.keyTextColor)
    }
}
