package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import org.bitfennec.lime.keyboard.KeyboardSurface
import org.bitfennec.lime.keyboard.InputView


/**
 * QWERTY keyboard container hosting TextKeyboard.
 */
@SuppressLint("ViewConstructor")
class QwertyContainer(
    context: Context?,
    inputView: InputView,
    private val skbValue: Int,
    private val keyboardSurface: KeyboardSurface,
) : InputBaseContainer(context, inputView) {

    /**
     * Updates soft keyboard layout.
     */
    override fun updateSkbLayout() {
        attachTextKeyboard(keyboardSurface, skbValue)
    }
}
