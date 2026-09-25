package org.bitfennec.lime.utils

import android.view.KeyEvent
import android.view.View
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.prefs.InputFeedbacks.SoundEffect
import org.bitfennec.lime.prefs.InputFeedbacks.hapticFeedback
import org.bitfennec.lime.prefs.InputFeedbacks.soundEffect

/**
 * Device utility functions.
 */
object DevicesUtils {
	fun dip2px(dpValue: Float): Int {
        val scale = runCatching { Launcher.instance.context.resources.displayMetrics.density }.getOrDefault(2.0f)
        return (dpValue * scale + 0.5f).toInt()
    }
    fun dip2px(dpValue: Int): Int {
        val scale = runCatching { Launcher.instance.context.resources.displayMetrics.density }.getOrDefault(2.0f)
        return (dpValue * scale + 0.5f).toInt()
    }


	fun tryVibrate(view: View?, event: HapticEvent = HapticEvent.TAP) {
        if (view != null) {
            hapticFeedback(view, event)
        }
    }

	fun tryPlayKeyDown(code: Int = 0) {
        var soundEffect = SoundEffect.Standard
        soundEffect = when (code) {
            KeyEvent.KEYCODE_DEL -> SoundEffect.Delete
            KeyEvent.KEYCODE_SPACE -> SoundEffect.SpaceBar
            KeyEvent.KEYCODE_ENTER -> SoundEffect.Return
            else -> SoundEffect.Standard
        }
        soundEffect(soundEffect)
    }
}
