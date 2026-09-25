package org.bitfennec.lime.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import org.bitfennec.lime.keyboard.model.SoftKeyboard

class KeyboardSurface(context: Context, inputView: InputView) {
    private val textKeyboard: TextKeyboard = TextKeyboard(context).apply {
        setResponseKeyEvent(inputView)
    }

    fun attachTo(container: ConstraintLayout): TextKeyboard {
        val parent = textKeyboard.parent as? ViewGroup
        if (parent !== container) {
            parent?.removeView(textKeyboard)
            container.addView(textKeyboard, 0, layoutParams())
        } else if (textKeyboard.layoutParams !is ConstraintLayout.LayoutParams) {
            textKeyboard.layoutParams = layoutParams()
        }
        textKeyboard.visibility = View.VISIBLE
        return textKeyboard
    }

    fun setSoftKeyboard(softKeyboard: SoftKeyboard) {
        textKeyboard.setSoftKeyboard(softKeyboard)
        textKeyboard.invalidate()
    }

    fun detach() {
        textKeyboard.cancelActiveTouch()
        (textKeyboard.parent as? ViewGroup)?.removeView(textKeyboard)
    }

    private fun layoutParams() = ConstraintLayout.LayoutParams(
        ConstraintLayout.LayoutParams.MATCH_PARENT,
        ConstraintLayout.LayoutParams.MATCH_PARENT,
    )
}
