package org.bitfennec.lime.keyboard

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.view.popup.PopupComponent

class KeyboardFeedbackRouter(
    private val view: View,
    private val popupComponent: PopupComponent,
) {
    fun keyDown(keyCode: Int) {
        DevicesUtils.tryPlayKeyDown(keyCode)
        DevicesUtils.tryVibrate(view)
    }

    fun repeatDelete() {
        DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_DEL)
        DevicesUtils.tryVibrate(view, HapticEvent.STEP)
    }

    fun vibrate(event: HapticEvent = HapticEvent.TAP) {
        DevicesUtils.tryVibrate(view, event)
    }

    fun showPopup(text: String, key: SoftKey) {
        popupComponent.showPopup(text, key.bounds())
    }

    fun showActionPopup(iconRes: Int, text: String) {
        popupComponent.showActionPopup(iconRes, text)
    }

    fun showKeyboard(label: String, smallLabel: String, key: SoftKey) {
        popupComponent.showKeyboard(label, smallLabel, key.bounds())
    }

    fun showKeyboardMenu(key: SoftKey, distanceY: Float) {
        popupComponent.showKeyboardMenu(key, key.bounds(), distanceY)
    }

    fun changeFocus(deltaX: Float, deltaY: Float) {
        popupComponent.changeFocus(deltaX, deltaY)
    }

    fun onGestureEvent(distanceY: Float) {
        popupComponent.onGestureEvent(distanceY)
    }

    fun triggerFocused() = popupComponent.triggerFocused()

    fun dismissPopup() {
        popupComponent.dismissPopup()
    }

    private fun SoftKey.bounds(): Rect = Rect(mLeft, mTop, mRight, mBottom)
}
