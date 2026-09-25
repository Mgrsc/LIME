package org.bitfennec.lime.keyboard.model

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import org.bitfennec.lime.keyboard.keyIconRecords
import org.bitfennec.lime.manager.InputModeSwitcher
import java.util.Objects

open class SoftKey(
    var code: Int = 0,
    var label: String = "",
    var labelSmall: String = "",
    var keyMnemonic: String = "",
) {
    var mLeftF = -1f
    var mTopF = -1f
    var widthF = 0f
    var heightF = 0.25f

    var mLeft = 0
    var mRight = 0
    var mTop = 0
    var mBottom = 0
    var hitLeft = 0
    var hitTop = 0
    var hitRight = 0
    var hitBottom = 0

    var stateId = 0
    var pressed = false
    var isBottomRow: Boolean = false

    fun isBottomRowKey(): Boolean = isBottomRow || mTopF >= 0.50f

    fun onPressed() {
        pressed = true
    }

    fun onReleased() {
        pressed = false
    }

    fun setKeyDimensions(left: Float, top: Float) {
        mLeftF = left
        mTopF = top
    }

    fun setKeyDimensions(left: Float, top: Float, height: Float) {
        setKeyDimensions(left, top)
        heightF = height
    }

    fun setSkbCoreSize(skbWidth: Int, skbHeight: Int) {
        mLeft = (mLeftF * skbWidth).toInt()
        mRight = ((mLeftF + widthF) * skbWidth).toInt()
        mTop = (mTopF * skbHeight).toInt()
        mBottom = ((mTopF + heightF) * skbHeight).toInt()
        setHitBounds(mLeft, mTop, mRight, mBottom)
    }

    fun setHitBounds(left: Int, top: Int, right: Int, bottom: Int) {
        hitLeft = left
        hitTop = top
        hitRight = right
        hitBottom = bottom
    }

    open val keyIcon: Drawable?
        get() = keyIconRecords[Objects.hash(code, stateId)]

    open val keyLabel: String
        get() = label

    open val keyLabelSmall: String
        get() = labelSmall

    val isUserDefKey: Boolean
        get() = code < 0

    val isUniStrKey: Boolean
        get() = code == 0

    fun repeatable(): Boolean {
        return code == KeyEvent.KEYCODE_DEL
                || code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION
                || code in KeyEvent.KEYCODE_DPAD_UP .. KeyEvent.KEYCODE_DPAD_RIGHT
    }

    fun width(): Int {
        return mRight - mLeft
    }

    fun height(): Int {
        return mBottom - mTop
    }
}
