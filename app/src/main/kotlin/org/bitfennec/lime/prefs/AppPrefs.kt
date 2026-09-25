package org.bitfennec.lime.prefs

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode
import org.bitfennec.lime.prefs.behavior.DeleteGestureAction
import org.bitfennec.lime.prefs.behavior.HalfWidthSymbolsMode
import org.bitfennec.lime.prefs.behavior.KeyboardOneHandedMod
import org.bitfennec.lime.utils.DevicesUtils


class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val pinyinModeRime = string("input_method_pinyin_mode_rime", CustomConstant.SCHEMA_ZH_QWERTY) // Active Rime pinyin schema (default QWERTY)
        val inputDefaultMode = int("input_default_method_mode", InputModeSwitcher.MODE_QWERTY_CHINESE)   // Default input mode (default QWERTY)
        val inputMethodPinyinMode = int("input_method_pinyin_mode", InputModeSwitcher.MODE_QWERTY_CHINESE)  // Stored Chinese input mode (default QWERTY)
        val lastAppVersionCode = long("app_installed_version_code", 0L) // Installed App VersionCode
        val lastAppUpdateTime = long("app_installed_update_time", 0L)   // Installed App last update timestamp
        val keyboardHeightRatio = float("keyboard_height_ratio", 0.245f)     // Keyboard height ratio
        val keyboardHeightRatioLandscape = float("keyboard_height_ratio_landscape", 0.34f)     // Keyboard height ratio (landscape)
        val keyboardModeFloat = bool("keyboard_mode_float", false)     // Floating mode
        val keyboardModeFloatLandscape = bool("keyboard_mode_float_landscape", true)// Floating mode in landscape (enabled by default)
        val keyboardBottomPaddingFloat = int("keyboard_padding_bottom", DevicesUtils.dip2px(100))     // Portrait floating bottom margin
        val keyboardRightPaddingFloat = int("keyboard_padding_right", DevicesUtils.dip2px(20))     // Portrait floating right margin
        val keyboardBottomPaddingLandscapeFloat = int("keyboard_padding_bottom_landscape", DevicesUtils.dip2px(24))     // Landscape floating bottom margin
        val keyboardRightPaddingLandscapeFloat = int("keyboard_padding_right_landscape", -1)     // Landscape floating right margin (-1 for center)
        val keyboardRightPadding = int("keyboard_padding_right_normal", DevicesUtils.dip2px(0))     // Portrait non-floating right margin
        val clipboardUpdateTime = long("clipboard_update_time", 0)     // Clipboard update timestamp
        val clipboardUpdateContent = string("clipboard_update_content","")     // Clipboard update content

        init {
            if (!sharedPreferences.getBoolean("sound_vibration_keys_migrated_v1", false)) {
                val oldSound = sharedPreferences.getInt("key_press_vibration_amplitude", 0)
                val oldVibration = sharedPreferences.getInt("key_press_sound_volume", 0)
                sharedPreferences.edit {
                    putInt("key_press_sound_volume", oldSound)
                    putInt("key_press_vibration_amplitude", oldVibration)
                    putBoolean("sound_vibration_keys_migrated_v1", true)
                }
            }
        }

        val soundOnKeyPress = int("key_press_sound_volume", 0)     // Key press sound volume
        val vibrationAmplitude = int("key_press_vibration_amplitude", 0)     // Haptic vibration amplitude

        val privacyPolicySure = bool("privacy_policy_sure", false) // Privacy policy agreement status
        val deleteGestureGuideShown = bool("delete_gesture_guide_shown", false) // First-time delete gesture guide shown
    }

    inner class Input : ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {

        val titleChinese = category(R.string.chinese_input_setting)

        val chineseFanTi = switch(
            R.string.setting_jian_fan, "chinese_jian_fan_enable", false
        )

        val chinesePrediction = switch(
            R.string.chinese_association, "chinese_association_enable", true
        )

        val titleEnglish = category(R.string.EnglishInput)

        // English word completion
        val abcSearchEnglishCell = switch(
            R.string.search_english_cell, "search_english_cell_enable", true
        )

        val abcSpaceAuto = switch(
            R.string.space_auto, "abc_space_auto_enable", false
        ){
            abcSearchEnglishCell.getValue()
        }

        val titleEmoji = category(R.string.emoji_setting)
        val emojiInput = switch(
            R.string.emoji_input, "emoji_input_enable", true
        )

        val titleSymbol = category(R.string.symbol_setting)
        val symbolPairInput = switch(
            R.string.symbol_pair_input, "symbol_pair_input_enable", true
        )
    }

    inner class KeyboardSetting : ManagedPreferenceCategory(R.string.setting_ime_keyboard, sharedPreferences) {

        val candidateTextSize = int(
            R.string.candidate_size_input_setting,
            "candidate_size",
            55,
            25,
            100,
            "%",
            defaultLabel = R.string.system_default
        )

        val keyboardBalloonShow = switch(R.string.keypopup_input_settings, "keyboard_balloon_show_enable", false)

        val longPressTimeout = int(
            R.string.long_press_timeout,
            "long_press_timeout",
            400,
            100,
            700,
            "ms",
            50,
            defaultLabel = R.string.number_400_ms,
            unitRes = R.string.unit_ms,
        )


        val abcNumberLine = switch(R.string.keyboard_number_row, "keyboard_abc_number_line_enable", false)

        val qwerty9Geometry = switch(R.string.keyboard_qwerty9_geometry, "keyboard_qwerty9_geometry", false)

        val swipeDownCaps = switch(R.string.keyboard_swipe_down_caps, "keyboard_swipe_down_caps", true)

        // Remember language mode: persists active Chinese/English mode across sessions
        val keyboardLockEnglish = switch(R.string.keyboard_menu_lock_english, "keyboard_menu_lock_english_enable", false)

        // Keyboard calculator in numeric keypad
        val keyboardCalculator = switch(R.string.keyboard_calculator, "keyboard_calculator_enable", true)

        val oneHandedModSwitch = switch(R.string.keyboard_one_handed_mod, "keyboard_one_handed_mod_enable", false)

        val oneHandedMod = list(
            R.string.keyboard_one_handed_mod,
            "keyboard_one_handed_mod",
            KeyboardOneHandedMod.LEFT,
            KeyboardOneHandedMod,
            listOf(
                KeyboardOneHandedMod.LEFT,
                KeyboardOneHandedMod.RIGHT
            ),
            listOf(
                R.string.keyboard_one_handed_mod_left,
                R.string.keyboard_one_handed_mod_right
            )
        ) {
            oneHandedModSwitch.getValue()
        }

        val halfWidthSymbolsMode = list(
            R.string.half_width_symbols_tips,
            "half_width_symbols_tips",
            HalfWidthSymbolsMode.All,
            HalfWidthSymbolsMode,
            listOf(
                HalfWidthSymbolsMode.All,
                HalfWidthSymbolsMode.OnlyUsed,
                HalfWidthSymbolsMode.None
            ),
            listOf(
                R.string.half_width_symbols_tips_all,
                R.string.half_width_symbols_tips_only_used,
                R.string.half_width_symbols_tips_none
            )
        )

        val showVirtualKeyboardOnPhysicalKeyboard = switch(R.string.show_virtual_keyboard_with_external, "show_virtual_keyboard_with_external", false)
        val deleteSwipeUpAction = stringLike(
            "delete_gesture_swipe_up_action",
            DeleteGestureAction.CLEAR_ALL,
            DeleteGestureAction,
        )
        val deleteSwipeLeftAction = stringLike(
            "delete_gesture_swipe_left_action",
            DeleteGestureAction.SWIPE_SELECT,
            DeleteGestureAction,
        )
        val deleteSwipeDownAction = stringLike(
            "delete_gesture_swipe_down_action",
            DeleteGestureAction.UNDO_REVERT,
            DeleteGestureAction,
        )
    }

    inner class Voice : ManagedPreferenceCategory(R.string.voice_input, sharedPreferences) {
        val removeTrailingPunctuation = switch(
            R.string.voice_remove_trailing_punctuation,
            "voice_remove_trailing_punctuation",
            true
        )
        val mirrorSource = int(
            R.string.voice_mirror_source,
            "voice_mirror_source",
            0
        )
        val idleUnloadTimeoutSeconds = int(
            R.string.voice_idle_unload_timeout,
            "voice_idle_unload_timeout",
            300,
            180,
            600
        )
    }

    inner class Handwriting : ManagedPreferenceCategory(R.string.ime_settings_handwriting, sharedPreferences) {


        val handWritingWidth = int(
            R.string.paint_thickness,
            "hand_writing_width",
            35,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        )

        val handWritingSpeed = int(
            R.string.handwriting_speed_title,
            "hand_writing_speed",
            500,
            300,
            1300,
            "ms",
            100,
            defaultLabel = R.string.number_500_ms,
            unitRes = R.string.unit_ms,
        )

        val mirrorSource = int(
            R.string.handwriting_mirror_source,
            "handwriting_mirror_source",
            0
        )
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            200,
            20,
            1000,
            "items",
            20,
            defaultLabel = R.string.num_200,
            unitRes = R.string.unit_items,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            5,
            300,
            "s",
            5,
            defaultLabel = R.string.number_30_s,
            unitRes = R.string.unit_s,
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }

        val clipboardLayoutCompact = list(
            R.string.clipboard_layout_compact_mode,
            "clipboard_layout_mode",
            ClipboardLayoutMode.FlexboxView,
            ClipboardLayoutMode,
            listOf(
                ClipboardLayoutMode.FlexboxView,
                ClipboardLayoutMode.GridView,
                ClipboardLayoutMode.ListView
            ),
            listOf(
                R.string.clipboard_layout_mode_flexbox,
                R.string.clipboard_layout_mode_grid_default,
                R.string.clipboard_layout_mode_list_plain
            )
        ) {
            clipboardListening.getValue()
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }


    val internal = Internal().register()
    val voice = Voice().register()
    val handwriting = Handwriting().register()
    val input = Input().register()
    val clipboard = Clipboard().register()
    val keyboardSetting = KeyboardSetting().register()

    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            providers.forEach {
                it.managedPreferences[key]?.fireChange()
            }
        }

    fun syncToDeviceEncryptedStorage() {
        val ctx = Launcher.instance.context.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            internal.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }

            input.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            voice.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            handwriting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            clipboard.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            keyboardSetting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }
    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
