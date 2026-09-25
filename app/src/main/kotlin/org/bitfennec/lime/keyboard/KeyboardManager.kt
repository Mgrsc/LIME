package org.bitfennec.lime.keyboard

import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.keyboard.container.BaseContainer
import org.bitfennec.lime.keyboard.container.CandidatesContainer
import org.bitfennec.lime.keyboard.container.ClipBoardContainer
import org.bitfennec.lime.keyboard.container.HandwritingContainer
import org.bitfennec.lime.keyboard.container.InputBaseContainer
import org.bitfennec.lime.keyboard.container.InputViewParent
import org.bitfennec.lime.keyboard.container.QwertyContainer
import org.bitfennec.lime.keyboard.container.SettingsContainer
import org.bitfennec.lime.keyboard.container.SymbolContainer
import org.bitfennec.lime.keyboard.container.T9TextContainer
import org.bitfennec.lime.keyboard.container.TextEditContainer
import org.bitfennec.lime.keyboard.container.VoiceDownloadContainer
import org.bitfennec.lime.prefs.AppPrefs

/**
 * Keyboard display and container manager.
 */
class KeyboardManager {
    enum class KeyboardType {
        T9, QWERTY, QWERTYABC, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard, TEXTEDIT, VOICE_DOWNLOAD
    }
    private lateinit var mInputView: InputView
    val inputView: InputView?
        get() = if (::mInputView.isInitialized) mInputView else null
    private lateinit var mKeyboardRootView: InputViewParent
    private val keyboards = HashMap<KeyboardType, BaseContainer?>()
    private var keyboardSurface: KeyboardSurface? = null
    var currentContainer: BaseContainer? = null
        private set

    fun setData(keyboardRootView: InputViewParent, inputView: InputView) {
        keyboards.clear()
        keyboardSurface?.detach()
        keyboardSurface = null
        if (::mKeyboardRootView.isInitialized) mKeyboardRootView.clearViews()
        currentContainer = null
        mKeyboardRootView = keyboardRootView
        mInputView = inputView
    }

    fun clearKeyboard() {
        if (currentContainer is HandwritingContainer) {
            if (::mInputView.isInitialized) {
                mInputView.endHandwritingSession()
            }
        }
        keyboards.clear()
        keyboardSurface?.detach()
        keyboardSurface = null
        currentContainer = null
        if (::mKeyboardRootView.isInitialized) mKeyboardRootView.clearViews()
        if (::mInputView.isInitialized) {
            mInputView.initView(mInputView.context)
            switchKeyboard()
        }
    }

    fun switchKeyboard(layout: Int = InputModeSwitcher.skbLayout) {
        val keyboardName = when (layout) {
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN -> KeyboardType.QWERTY
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> KeyboardType.QWERTYABC
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING -> KeyboardType.HANDWRITING
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> KeyboardType.NUMBER
            else -> KeyboardType.T9
        }
        switchKeyboard(keyboardName)
        if (::mInputView.isInitialized) mInputView.updateCandidateBar()
    }

    fun switchKeyboard(keyboardName: KeyboardType) {
        if (!::mKeyboardRootView.isInitialized) return
        var container = keyboards[keyboardName]
        val isNewContainer = container == null
        if (container == null) {
            container = when (keyboardName) {
                KeyboardType.CANDIDATES ->  CandidatesContainer(Launcher.instance.context, mInputView)
                KeyboardType.HANDWRITING -> HandwritingContainer(Launcher.instance.context, mInputView)
                KeyboardType.NUMBER -> T9TextContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER, inputKeyboardSurface())
                KeyboardType.QWERTY -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, inputKeyboardSurface())
                KeyboardType.SETTINGS -> SettingsContainer(Launcher.instance.context, mInputView)
                KeyboardType.SYMBOL -> SymbolContainer(Launcher.instance.context, mInputView)
                KeyboardType.QWERTYABC -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC, inputKeyboardSurface())
                KeyboardType.ClipBoard -> ClipBoardContainer(Launcher.instance.context, mInputView)
                KeyboardType.TEXTEDIT -> TextEditContainer(Launcher.instance.context, mInputView)
                KeyboardType.VOICE_DOWNLOAD -> VoiceDownloadContainer(Launcher.instance.context, mInputView)
                else ->  T9TextContainer(Launcher.instance.context, mInputView, AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT, inputKeyboardSurface())
            }
            keyboards[keyboardName] = container
        }
        if (currentContainer !== container) {
            (currentContainer as? InputBaseContainer)?.cancelActiveTouch()
            if (currentContainer is HandwritingContainer && container !is HandwritingContainer) {
                if (container !is CandidatesContainer) {
                    if (::mInputView.isInitialized) {
                        mInputView.endHandwritingSession()
                    }
                }
                org.bitfennec.lime.core.HandwritingEngine.scheduleIdleUnload(60_000L)
            }
        }
        if (isNewContainer || container is InputBaseContainer) {
            container.updateSkbLayout()
        }
        mKeyboardRootView.showView(container)
        currentContainer = container
    }

    val isInputKeyboard: Boolean
        get() = currentContainer is InputBaseContainer

    private fun inputKeyboardSurface(): KeyboardSurface {
        val current = keyboardSurface
        if (current != null) return current
        val created = KeyboardSurface(Launcher.instance.context, mInputView)
        keyboardSurface = created
        return created
    }

    companion object {
        val instance: KeyboardManager by lazy(LazyThreadSafetyMode.NONE) {
            KeyboardManager()
        }
    }
}
