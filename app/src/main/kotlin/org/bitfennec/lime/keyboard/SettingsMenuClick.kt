package org.bitfennec.lime.keyboard

import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.service.ClipboardHelper
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.prefs.behavior.SymbolMode
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.AppUtil
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.bitfennec.lime.keyboard.container.CandidatesContainer
import org.bitfennec.lime.keyboard.container.ClipBoardContainer
import org.bitfennec.lime.keyboard.container.SettingsContainer
import org.bitfennec.lime.keyboard.container.SymbolContainer
import org.bitfennec.lime.keyboard.container.TextEditContainer
import org.bitfennec.lime.utils.toast
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.R

fun onSettingsMenuClick(inputView: InputView, skbMenuMode: SkbMenuMode) {
    when (skbMenuMode) {
        SkbMenuMode.Emojicon, SkbMenuMode.Emoticon -> {
            val symbolType = if (skbMenuMode == SkbMenuMode.Emoticon) SymbolMode.Emoticon else SymbolMode.Emojicon
            val currentContainer = KeyboardManager.instance.currentContainer as? SymbolContainer
            if (currentContainer != null && (currentContainer.getMenuMode() == SymbolMode.Emojicon || currentContainer.getMenuMode() == SymbolMode.Emoticon)) {
                KeyboardManager.instance.switchKeyboard()
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setEmojisView(symbolType)
            }
            inputView.updateCandidateBar()
        }
        SkbMenuMode.SwitchKeyboard -> {
            val currentContainer = KeyboardManager.instance.currentContainer as? SettingsContainer
            if (currentContainer != null && currentContainer.isShowingModeSelect()) {
                KeyboardManager.instance.switchKeyboard()
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showSkbSelelctModeView()
            }
            inputView.updateCandidateBar()
        }
        SkbMenuMode.KeyboardHeight -> {
            inputView.showKeyboardHeightAdjustBar()
        }
        SkbMenuMode.DarkTheme -> {
            val theme = (if (ThemeManager.activeTheme.isDark) ThemeManager.prefs.lightModeTheme else ThemeManager.prefs.darkModeTheme).getValue()
            ThemeManager.setNormalModeTheme(theme)
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Feedback -> {
            val currentContainer = KeyboardManager.instance.currentContainer as? SettingsContainer
            if (currentContainer != null) {
                currentContainer.showFeedbackSettingView()
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showFeedbackSettingView()
            }
            inputView.updateCandidateBar()
        }
        SkbMenuMode.NumberRow -> {
            val abcNumberLine = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
            AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(!abcNumberLine)
            // Reload keyboard after changing keyboard layout
            KeyboardLoaderUtil.instance.changeSKBNumberRow()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.JianFan -> {
            val chineseFanTi = AppPrefs.getInstance().input.chineseFanTi.getValue()
            val newFanTi = !chineseFanTi
            AppPrefs.getInstance().input.chineseFanTi.setValue(newFanTi)
            EnginePipeline.syncImeOptions()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.LockEnglish -> {
            val keyboardLockEnglish = AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.setValue(!keyboardLockEnglish)
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.SymbolShow -> {
            val keyboardSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
            ThemeManager.prefs.keyboardSymbol.setValue(!keyboardSymbol)
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.EmojiInput -> {
            val emojiInput = AppPrefs.getInstance().input.emojiInput.getValue()
            AppPrefs.getInstance().input.emojiInput.setValue(!emojiInput)
            EnginePipeline.syncImeOptions()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Handwriting -> AppUtil.launchSettingsToHandwriting(inputView.context)
        SkbMenuMode.Settings -> AppUtil.launchSettings(inputView.context)
        SkbMenuMode.OneHanded -> {
            if (ImeEnvironment.keyboardModeFloat) {
                inputView.context.toast(inputView.context.getString(R.string.toast_one_handed_in_floating_unsupported))
                return
            }
            AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.setValue(!AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue())
            ImeEnvironment.initData()
            inputView.initView(inputView.context)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
            inputView.post { inputView.requestApplyInsets() }
        }
        SkbMenuMode.FloatKeyboard -> {
            val keyboardModeFloat = ImeEnvironment.keyboardModeFloat
            ImeEnvironment.keyboardModeFloat = !keyboardModeFloat
            ImeEnvironment.initData()
            inputView.initView(inputView.context)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
            inputView.post { inputView.requestApplyInsets() }
        }
        SkbMenuMode.ClipBoard -> {
            val currentContainer = KeyboardManager.instance.currentContainer as? ClipBoardContainer
            if(currentContainer != null){
                if(currentContainer.getMenuMode() == skbMenuMode) KeyboardManager.instance.switchKeyboard()
                else currentContainer.showClipBoardView()
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.ClipBoard)
                (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.showClipBoardView()
            }
            inputView.updateCandidateBar()
        }
        SkbMenuMode.Custom -> {
            val currentContainer = KeyboardManager.instance.currentContainer as? SettingsContainer
            if (currentContainer != null) {
                currentContainer.enableDragItem(true)
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                (KeyboardManager.instance.currentContainer as? SettingsContainer)?.enableDragItem(true)
            }
            inputView.updateCandidateBar()
        }
        SkbMenuMode.CloseSKB -> {
            inputView.requestHideSelf()
        }
        SkbMenuMode.SettingsMenu -> {
            if (KeyboardManager.instance.isInputKeyboard) {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showSettingsView()
                inputView.updateCandidateBar()
            } else {
                KeyboardManager.instance.switchKeyboard()
            }
        }
        SkbMenuMode.CandidatesMore -> {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.CANDIDATES)
            (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
        }
        SkbMenuMode.LockClipBoard -> {
            ClipboardHelper.isLocked = !ClipboardHelper.isLocked
            (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.updateLockState()
        }
        SkbMenuMode.TextEdit -> {
            val currentContainer = KeyboardManager.instance.currentContainer as? TextEditContainer
            if (currentContainer != null) {
                KeyboardManager.instance.switchKeyboard()
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.TEXTEDIT)
            }
            inputView.updateCandidateBar()
        }
        else ->{}
    }
}