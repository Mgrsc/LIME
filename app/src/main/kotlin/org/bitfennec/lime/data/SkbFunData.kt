package org.bitfennec.lime.data

import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.entity.SkbFunItem
import org.bitfennec.lime.prefs.behavior.SkbMenuMode

val menuSkbFunsPreset: Map<SkbMenuMode, SkbFunItem> = hashMapOf(
    SkbMenuMode.Emojicon to SkbFunItem(Launcher.instance.context.getString(R.string.emoji_setting), R.drawable.ic_menu_emoji, SkbMenuMode.Emojicon),
    SkbMenuMode.SwitchKeyboard to SkbFunItem(Launcher.instance.context.getString(R.string.changeKeyboard), R.drawable.ic_menu_keyboard, SkbMenuMode.SwitchKeyboard),
    SkbMenuMode.KeyboardHeight to SkbFunItem(Launcher.instance.context.getString(R.string.setting_ime_keyboard_height), R.drawable.ic_menu_height, SkbMenuMode.KeyboardHeight),
    SkbMenuMode.ClipBoard to SkbFunItem(Launcher.instance.context.getString(R.string.clipboard), R.drawable.ic_menu_clipboard, SkbMenuMode.ClipBoard),
    SkbMenuMode.DarkTheme to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_theme_night), R.drawable.ic_menu_dark, SkbMenuMode.DarkTheme),
    SkbMenuMode.Feedback to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_feedback), R.drawable.ic_menu_touch, SkbMenuMode.Feedback),
    SkbMenuMode.OneHanded to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_one_handed_mod), R.drawable.ic_menu_one_hand, SkbMenuMode.OneHanded),
    SkbMenuMode.NumberRow to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_number_row), R.drawable.ic_menu_shuzihang, SkbMenuMode.NumberRow),
    SkbMenuMode.JianFan to SkbFunItem(Launcher.instance.context.getString(R.string.setting_jian_fan), R.drawable.ic_menu_fanti, SkbMenuMode.JianFan),
    SkbMenuMode.FloatKeyboard to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_menu_float), R.drawable.ic_menu_float, SkbMenuMode.FloatKeyboard),
    SkbMenuMode.Custom to SkbFunItem(Launcher.instance.context.getString(R.string.skb_item_custom), R.drawable.ic_menu_custom, SkbMenuMode.Custom),
    SkbMenuMode.SettingsMenu to SkbFunItem(Launcher.instance.context.getString(R.string.skb_item_settings_menu), R.drawable.ic_menu_setting, SkbMenuMode.SettingsMenu),
    SkbMenuMode.Settings to SkbFunItem(Launcher.instance.context.getString(R.string.skb_item_settings), R.drawable.ic_menu_setting, SkbMenuMode.Settings),
    SkbMenuMode.CloseSKB to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_iv_menu_close), R.drawable.ic_menu_arrow_down, SkbMenuMode.CloseSKB),
    SkbMenuMode.Emoticon to SkbFunItem(Launcher.instance.context.getString(R.string.emoticons), R.drawable.ic_menu_emoji_emoticons, SkbMenuMode.Emoticon),
    SkbMenuMode.LockClipBoard to SkbFunItem(Launcher.instance.context.getString(R.string.lock_view), R.drawable.icon_symbol_lock, SkbMenuMode.LockClipBoard),
    SkbMenuMode.TextEdit to SkbFunItem(Launcher.instance.context.getString(R.string.menu_text_edit), R.drawable.ic_menu_cursor_icon, SkbMenuMode.TextEdit),
)
