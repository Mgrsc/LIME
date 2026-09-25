package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import org.bitfennec.lime.keyboard.HandwritingKeyboard
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.utils.KeyboardLoaderUtil.Companion.instance

/**
 * Modern handwriting keyboard container hosting full-width HandwritingKeyboard.
 */
@SuppressLint("ViewConstructor")
class HandwritingContainer(context: Context?, inputView: InputView) : InputBaseContainer(context, inputView) {

    val handwritingKeyboard: HandwritingKeyboard?
        get() = mMajorView as? HandwritingKeyboard

    /**
     * Updates soft keyboard layout.
     */
    override fun updateSkbLayout() {
        if (null == mMajorView) {
            mMajorView = HandwritingKeyboard(context)
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(mMajorView, params)
            (mMajorView as HandwritingKeyboard).setResponseKeyEvent(inputView)
        }
        inputView.registerHandwritingKeyboard(mMajorView as HandwritingKeyboard)
        inputView.prepareHandwritingSession()
        val softKeyboard = instance.getSoftKeyboard(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING)
        mMajorView!!.setSoftKeyboard(softKeyboard)
        mMajorView!!.invalidate()
    }
}
