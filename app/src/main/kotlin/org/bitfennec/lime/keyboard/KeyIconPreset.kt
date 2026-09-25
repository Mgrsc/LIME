package org.bitfennec.lime.keyboard

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.manager.InputModeSwitcher
import java.util.Objects

val keyIconRecords: Map<Int, Drawable?> = mapOf(
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_off_0_icon),
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 1) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_on_1_icon),
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 2) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_lock_2_icon),
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 3) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_off_3_icon),
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 4) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_on_4_icon),
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 5) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_lock_5_icon),
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 7) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_on_7_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_EMOJI, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.ic_menu_emoji),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_comma_emoji),
    Objects.hash(KeyEvent.KEYCODE_SPACE, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_space_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 1) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_DEL, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_delete_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_direction_icon),
)