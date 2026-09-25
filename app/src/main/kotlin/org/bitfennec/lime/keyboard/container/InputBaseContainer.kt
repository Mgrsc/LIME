package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardSurface
import org.bitfennec.lime.keyboard.TextKeyboard
import org.bitfennec.lime.utils.KeyboardLoaderUtil.Companion.instance

/**
 * Base input container hosting TextKeyboard with touch and state management.
 */
@SuppressLint("ViewConstructor")
open class InputBaseContainer(
    context: Context?,
    inputView: InputView,
) : BaseContainer(context ?: inputView.context, inputView) {

    protected var mMajorView: TextKeyboard? = null

    /**
     * Refreshes key state (e.g. Enter key action on layout switch).
     */
    fun updateStates() {
        mMajorView?.updateStates()
    }

    fun cancelActiveTouch() {
        mMajorView?.cancelActiveTouch()
    }

    protected fun attachTextKeyboard(surface: KeyboardSurface, skbValue: Int): TextKeyboard {
        val keyboard = surface.attachTo(this)
        mMajorView = keyboard
        surface.setSoftKeyboard(instance.getSoftKeyboard(skbValue))
        return keyboard
    }
}
