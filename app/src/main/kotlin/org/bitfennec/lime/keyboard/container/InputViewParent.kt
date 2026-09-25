package org.bitfennec.lime.keyboard.container

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout

/**
 * Root container coordinating keyboard layout switching and view recycling.
 */
class InputViewParent @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = 0) : RelativeLayout(context, attrs, defStyleAttr, defStyleRes) {
    private var mLastContainer: View? = null
    fun showView(child: View?) {
        if (child == null) return
        (child as? InputBaseContainer)?.updateStates()
        if (child.parent == null) {
            super.addView(child)
        } else if (child.parent !== this) {
            (child.parent as ViewGroup).removeView(child)
            super.addView(child)
        }
        child.visibility = VISIBLE
        if (child === mLastContainer) return
        if (mLastContainer != null) {
            hideView(mLastContainer!!)
        }
        mLastContainer = child
    }

    fun clearViews() {
        mLastContainer = null
        removeAllViews()
    }

    private fun hideView(child: View) {
        child.visibility = GONE
    }
}
