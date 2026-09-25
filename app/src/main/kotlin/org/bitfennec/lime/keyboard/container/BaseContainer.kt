package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.environment.ImeEnvironment

/** Base container view for soft keyboard layouts. */
@SuppressLint("ViewConstructor")
open class BaseContainer(
    context: Context,
    protected val inputView: InputView,
) : ConstraintLayout(context) {

    /**
     * Updates soft keyboard layout.
     */
    open fun updateSkbLayout() {}

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val env = ImeEnvironment
        val measuredWidth = inputView.currentKeyboardSurfaceWidth()
        val measuredHeight = env.skbHeight
        val widthMeasure = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val heightMeasure = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }
}
