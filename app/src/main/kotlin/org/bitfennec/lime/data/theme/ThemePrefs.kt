package org.bitfennec.lime.data.theme

import android.content.SharedPreferences
import org.bitfennec.lime.R
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.ManagedPreferenceCategory
import org.bitfennec.lime.prefs.behavior.KeyboardSymbolSlideUpMod
import org.bitfennec.lime.view.preference.ManagedPreference

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themePreference(
        key: String,
        defaultValue: Theme
    ): ManagedThemePreference {
        val pref = ManagedThemePreference(sharedPreferences, key, defaultValue)
        pref.register()
        return pref
    }

    // Theme settings title
    val themeSetting = category(R.string.theme_select)
    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        true,
        summary = R.string.follow_system_day_night_theme_summary
    )

    val lightModeTheme = themePreference(
        "light_mode_theme",
        ThemePreset.MaterialLight
    )

    val darkModeTheme = themePreference(
        "dark_mode_theme",
        ThemePreset.MaterialDark
    )

    val dayNightModePrefNames = setOf(
        normalModeTheme.key,
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )

    // Keyboard settings title
    val titleKeyboardSetting = category(R.string.setting_ime_keyboard_show)


    val abcNumberLine get() = AppPrefs.getInstance().keyboardSetting.abcNumberLine

    val keyboardFontBold =
        switch(R.string.keyboard_font_bold, "keyboard_font_bold_enable", true)

    val keyboardChineseUppercase =
        switch(R.string.keyboard_chinese_uppercase, "keyboard_chinese_uppercase", true, summary = R.string.keyboard_chinese_uppercase_desc)

    val keyboardFontSize = int(
        R.string.keyboard_font_size,
        "keyboard_font_size",
        100,
        70,
        170,
        "%"
    )

    val candidateTextSize get() = AppPrefs.getInstance().keyboardSetting.candidateTextSize

    val keyboardBalloonShow get() = AppPrefs.getInstance().keyboardSetting.keyboardBalloonShow

    val keyboardSymbol =
        switch(R.string.keyboard_symbol_show, "keyboard_symbol_show_enable", true)

    val symbolSlideUpMod = list(
        R.string.keyboard_symbol_slide_up_mod,
        "keyboard_symbol_slide_up_mod",
        KeyboardSymbolSlideUpMod.MEDIUM,
        KeyboardSymbolSlideUpMod,
        listOf(
            KeyboardSymbolSlideUpMod.SHORT,
            KeyboardSymbolSlideUpMod.MEDIUM,
            KeyboardSymbolSlideUpMod.LONG
        ),
        listOf(
            R.string.keyboard_symbol_slide_up_short,
            R.string.keyboard_symbol_slide_up_medium,
            R.string.keyboard_symbol_slide_up_long,
        )
    ) {
        keyboardSymbol.getValue()
    }

    val keyBorder = switch(R.string.key_border, "key_border", true)

    val keyXMargin: ManagedPreference.PInt
    val keyYMargin: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.keyboard_key_margin,
            R.string.key_horizontal_margin,
            "keyboard_key_margin_x",
            4,
            R.string.key_vertical_margin,
            "keyboard_key_margin_y",
            10,
            0,
            100,
            "",
            defaultLabel = R.string.system_default
        ) {
            keyBorder.getValue()
        }
        keyXMargin = primary
        keyYMargin = secondary
    }

    val keyRadius = int(
        R.string.key_radius,
        "key_radius",
        20,
        0,
        60,
        "dp"
    ) {
        keyBorder.getValue()
    }
}
