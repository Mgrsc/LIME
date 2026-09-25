package org.bitfennec.lime.manager

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.prefs.AppPrefs.Companion.getInstance
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.container.InputBaseContainer
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.bitfennec.lime.inputmethod.EngineAction
import org.bitfennec.lime.inputmethod.EnginePipeline

/**
 * Input mode switcher managing soft keyboard layouts and states.
 */
object InputModeSwitcher {
    /**
     * User defined key code, used by soft keyboard. Select candidate key.
     */
    const val USER_KEYCODE_SELECTED = -1

    /**
     * User defined key code, used by soft keyboard. Language switch key.
     */
    const val USER_KEYCODE_LANG = -2

    /**
     * User defined key code, used by soft keyboard. Symbol keyboard switch key.
     */
    const val USER_KEYCODE_SYMBOL = -3

    /**
     * User defined key code, used by soft keyboard. Emoji keyboard switch key.
     */
    const val USER_KEYCODE_EMOJI = -4

    /**
     * User defined key code, used by soft keyboard. Numeric keypad switch key.
     */
    const val USER_KEYCODE_NUMBER = -5

    /**
     * User defined key code, used by soft keyboard. Numeric keypad return key.
     */
    const val USER_KEYCODE_RETURN = -6

    /**
     * User defined key code, used by soft keyboard. Comma key with long-press emoji.
     */
    const val USER_KEYCODE_COMMA_EMOJI = -8

    /**
     * User defined key code, used by soft keyboard. Cursor direction / replay key.
     */
    const val USER_KEYCODE_CURSOR_DIRECTION = -9

    /**
     * User defined key code, used by soft keyboard. Sidebar symbol placeholder.
     */
    const val USER_KEYCODE_LEFT_SYMBOL = -12

    /**
     * User defined key code, used by soft keyboard. Left comma key.
     */
    const val USER_KEYCODE_LEFT_COMMA = -13
    /**
     * User defined key code, used by soft keyboard. Left period key.
     */
    const val USER_KEYCODE_LEFT_PERIOD = -14

    /**
     * Bits used to indicate soft keyboard layout. If none bit is set, the
     * current input mode does not require a soft keyboard.
     * Bits 8-15 indicate soft keyboard layout. If 0, current mode requires no soft keyboard.
     */
    const val MASK_SKB_LAYOUT = 0xff00 // Bitmask layout: layout / language / case

    /**
     * A kind of soft keyboard layout. An input mode should be anded with
     * [.MASK_SKB_LAYOUT] to get its soft keyboard layout. QWERTY Pinyin layout.
     */
    const val MASK_SKB_LAYOUT_QWERTY_PINYIN = 0x1000

    /**
     * A kind of soft keyboard layout. An input mode should be anded with
     * [.MASK_SKB_LAYOUT] to get its soft keyboard layout. T9 Pinyin layout.
     */
    const val MASK_SKB_LAYOUT_T9_PINYIN = 0x2000

    /**
     * A kind of soft keyboard layout. An input mode should be anded with
     * [.MASK_SKB_LAYOUT] to get its soft keyboard layout. Handwriting layout.
     */
    const val MASK_SKB_LAYOUT_HANDWRITING = 0x3000

    /**
     * A kind of soft keyboard layout. An input mode should be anded with
     * [.MASK_SKB_LAYOUT] to get its soft keyboard layout. Standard QWERTY layout.
     */
    const val MASK_SKB_LAYOUT_QWERTY_ABC = 0x4000

    /**
     * A kind of soft keyboard layout. An input mode should be anded with
     * [.MASK_SKB_LAYOUT] to get its soft keyboard layout. Numeric keypad layout.
     */
    const val MASK_SKB_LAYOUT_NUMBER = 0x5000

    /**
     * Indicates language.
     */
    private const val MASK_LANGUAGE = 0x00f0

    /**
     * Used to indicate the current language. An input mode should be anded with
     * [.MASK_LANGUAGE] to get this information. Chinese language flag.
     */
    const val MASK_LANGUAGE_CN = 0x0010

    /**
     * Used to indicate the current language. An input mode should be anded with
     * [.MASK_LANGUAGE] to get this information. English language flag.
     */
    private const val MASK_LANGUAGE_EN = 0x0020

    /**
     * Letter case constants.
     */
    const val MASK_CASE_LOWER = 0
    const val MASK_CASE_UPPER = 1
    const val MASK_CASE_CAPS = 2

    /**
     * Mode for inputing Chinese with soft keyboard. QWERTY Chinese mode.
     */
    const val MODE_QWERTY_CHINESE = MASK_SKB_LAYOUT_QWERTY_PINYIN or MASK_LANGUAGE_CN

    /**
     * Mode for inputing Chinese with soft keyboard. T9 Chinese mode.
     */
    const val MODE_T9_CHINESE = MASK_SKB_LAYOUT_T9_PINYIN or MASK_LANGUAGE_CN

    /**
     * Mode for inputing Chinese with soft keyboard. Handwriting Chinese mode.
     */
    const val MODE_HANDWRITING_CHINESE = MASK_SKB_LAYOUT_HANDWRITING or MASK_LANGUAGE_CN

    /**
     * Mode for inputing English lower characters with soft keyboard. Standard English lowercase mode.
     */
    internal const val MODE_SKB_ENGLISH_LOWER = MASK_SKB_LAYOUT_QWERTY_ABC or MASK_LANGUAGE_EN

    /**
     * Unset mode. Unset input mode.
     */
    private const val MODE_UNSET = 0
    /**
     * The input mode for the current edit box. Current input mode for active edit field.
     */
    private var mInputMode = MODE_UNSET
    private var transientInputModeBeforeHandwriting = MODE_UNSET

    /**
     * Used to remember recent mode to input language. Recent language input mode.
     */
    private var mRecentLanguageInputMode = MODE_UNSET

    val mToggleStates = ToggleStates()

    class ToggleStates {
        var modifiers = MASK_CASE_LOWER
        var imeAction = 0
    }

    // Language keyboard
    val skbImeLayout: Int
        get() = mRecentLanguageInputMode and MASK_SKB_LAYOUT

    // Keyboard layout
    val skbLayout: Int
        get() = mInputMode and MASK_SKB_LAYOUT

    data class EditorFieldFlags(
        val isPassword: Boolean = false,
        val isNoPersonalizedLearning: Boolean = false,
        val isNoSuggestions: Boolean = false,
        val isUri: Boolean = false,
        val isEmail: Boolean = false,
        val isNumber: Boolean = false,
        val isCapCharacters: Boolean = false,
        val isCapWords: Boolean = false,
        val isCapSentences: Boolean = false,
        val isMessageField: Boolean = false
    ) {
        val isPrivateOrSensitive: Boolean
            get() = isPassword || isNoPersonalizedLearning

        val isModalInputBlocked: Boolean
            get() = isPassword

        // Message editors may disable spelling suggestions while still accepting Chinese next words.
        val allowsChinesePostCommitPrediction: Boolean
            get() = !isPrivateOrSensitive && (!isNoSuggestions || isMessageField)
    }

    var currentEditorFieldFlags: EditorFieldFlags = EditorFieldFlags()
        private set

    val isPrivateOrSensitive: Boolean
        get() = currentEditorFieldFlags.isPrivateOrSensitive

    val isPassword: Boolean
        get() = currentEditorFieldFlags.isPassword

    fun setEditorFieldFlags(flags: EditorFieldFlags) {
        currentEditorFieldFlags = flags
    }

    val isModalInputBlocked: Boolean
        get() = currentEditorFieldFlags.isModalInputBlocked

    val isNoSuggestions: Boolean
        get() = currentEditorFieldFlags.isNoSuggestions

    var currentEditorSessionId: Long = 0L
        private set

    val isEmailOrUri: Boolean
        get() = currentEditorFieldFlags.isEmail || currentEditorFieldFlags.isUri

    @androidx.annotation.VisibleForTesting
    fun resetEditorSessionForTesting() {
        currentEditorSessionId = 0L
    }

    fun normalizeEditorLiteral(text: String): String =
        normalizeEditorLiteral(text, isEmailOrUri)

    internal fun normalizeEditorLiteral(text: String, isEmailOrUri: Boolean): String {
        if (!isEmailOrUri) return text
        return when (text) {
            "＠" -> "@"
            "。", "．" -> "."
            "＿" -> "_"
            "−", "－", "―" -> "-"
            "＋" -> "+"
            "／" -> "/"
            else -> text
        }
    }

    fun parseEditorFlags(editorInfo: EditorInfo?): EditorFieldFlags {
        if (editorInfo == null) return EditorFieldFlags()
        val inputType = editorInfo.inputType
        val typeClass = inputType and EditorInfo.TYPE_MASK_CLASS
        val typeVariation = inputType and EditorInfo.TYPE_MASK_VARIATION
        val typeFlags = inputType and EditorInfo.TYPE_MASK_FLAGS
        val imeOptions = editorInfo.imeOptions

        val isNoPersonalizedLearning = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val isPassword = (typeClass == EditorInfo.TYPE_CLASS_TEXT && (
                typeVariation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                || typeVariation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || typeVariation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (typeClass == EditorInfo.TYPE_CLASS_NUMBER && typeVariation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD)
        val isUri = typeClass == EditorInfo.TYPE_CLASS_TEXT && typeVariation == EditorInfo.TYPE_TEXT_VARIATION_URI
        val isEmail = typeClass == EditorInfo.TYPE_CLASS_TEXT && (
                typeVariation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || typeVariation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
        val isNoSuggestions = (typeFlags and EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val isNumber = typeClass == EditorInfo.TYPE_CLASS_NUMBER
                || typeClass == EditorInfo.TYPE_CLASS_PHONE
                || typeClass == EditorInfo.TYPE_CLASS_DATETIME
        val isCapCharacters = (typeFlags and EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        val isCapWords = (typeFlags and EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS) != 0
        val isCapSentences = (typeFlags and EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        // QQ and mainstream IMs declare inputType as NORMAL + MULTI_LINE + NO_SUGGESTIONS (0xa0001)
        // to suppress spell-checking underlines; we treat multiline normal fields as message fields
        // to preserve Chinese post-commit prediction while still respecting incognito/password flags.
        val isMessageField = typeClass == EditorInfo.TYPE_CLASS_TEXT && (
                typeVariation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
                typeVariation == EditorInfo.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
                (typeVariation == EditorInfo.TYPE_TEXT_VARIATION_NORMAL &&
                    (typeFlags and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0)
        )

        return EditorFieldFlags(
            isPassword = isPassword,
            isNoPersonalizedLearning = isNoPersonalizedLearning,
            isNoSuggestions = isNoSuggestions,
            isUri = isUri,
            isEmail = isEmail,
            isNumber = isNumber,
            isCapCharacters = isCapCharacters,
            isCapWords = isCapWords,
            isCapSentences = isCapSentences,
            isMessageField = isMessageField
        )
    }

    /**
     * Editor-forced layout/mode. Null = fall through to restarting / lockEnglish / default pinyin.
     */
    internal fun skbModeForEditorFlags(flags: EditorFieldFlags): Int? = when {
        flags.isNumber -> MASK_SKB_LAYOUT_NUMBER
        flags.isEmail || flags.isPassword -> MODE_SKB_ENGLISH_LOWER
        else -> null
    }

    /**
     * Resolves soft keyboard input mode from EditorInfo.
     */
    fun requestInputWithSkb(editorInfo: EditorInfo, restarting: Boolean = false) {
        if (!restarting) {
            currentEditorSessionId++
        }
        val flags = parseEditorFlags(editorInfo)
        currentEditorFieldFlags = flags
        val imeOptions = editorInfo.imeOptions

        val newInputMode: Int = skbModeForEditorFlags(flags) ?: when {
            restarting && mInputMode != MODE_UNSET -> mInputMode
            getInstance().keyboardSetting.keyboardLockEnglish.getValue() -> getInstance().internal.inputDefaultMode.getValue()
            else -> getInstance().internal.inputMethodPinyinMode.getValue()
        }

        val hasNoEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        mToggleStates.imeAction = if (hasNoEnterAction) 0 else imeOptions and EditorInfo.IME_MASK_ACTION

        if (newInputMode != mInputMode && MODE_UNSET != newInputMode) {
            saveInputMode(newInputMode, applyEngine = false)
            KeyboardManager.instance.switchKeyboard()
        } else if (KeyboardManager.instance.currentContainer == null) {
            KeyboardManager.instance.switchKeyboard()
        }

        val charCase = applyEditorCharCase(flags)
        dispatchEditorPolicy(charCase)
        (KeyboardManager.instance.currentContainer as? InputBaseContainer)?.updateStates()
    }

    /**
     * Switches input mode via custom soft key code.
     */
    fun switchModeForUserKey(userKey: Int) {
        var newInputMode = MODE_UNSET
        if (USER_KEYCODE_LANG == userKey) {
            newInputMode = if (isChinese) MODE_SKB_ENGLISH_LOWER else getInstance().internal.inputMethodPinyinMode.getValue()
        } else if (USER_KEYCODE_NUMBER == userKey) {
            newInputMode = MASK_SKB_LAYOUT_NUMBER
        } else if (USER_KEYCODE_RETURN == userKey) {
            newInputMode = if (mRecentLanguageInputMode != 0) mRecentLanguageInputMode else getInstance().internal.inputMethodPinyinMode.getValue()
        }
        saveInputMode(newInputMode)
        KeyboardManager.instance.switchKeyboard()
    }

    fun enterTransientHandwritingMode() {
        if (mInputMode != MODE_HANDWRITING_CHINESE) {
            transientInputModeBeforeHandwriting = mInputMode
            mInputMode = MODE_HANDWRITING_CHINESE
        }
    }

    fun leaveTransientHandwritingMode() {
        if (mInputMode != MODE_HANDWRITING_CHINESE || transientInputModeBeforeHandwriting == MODE_UNSET) return
        mInputMode = transientInputModeBeforeHandwriting
        transientInputModeBeforeHandwriting = MODE_UNSET
        KeyboardManager.instance.inputView?.endHandwritingSession()
        KeyboardManager.instance.switchKeyboard()
        dispatchEditorPolicy(0)
    }

    /**
     * Switches input mode via application preferences.
     */
    fun switchModeForSetting(value: Pair<Int, String>) {
        val inputMode = value.first or MASK_LANGUAGE_CN
        getInstance().internal.inputMethodPinyinMode.setValue(inputMode)
        // Handwriting changes the layout without replacing the saved Chinese schema.
        if (inputMode != MODE_HANDWRITING_CHINESE) {
            getInstance().internal.pinyinModeRime.setValue(value.second)
        }
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        saveInputMode(inputMode)
        KeyboardManager.instance.switchKeyboard(skbImeLayout)
    }


    // Track Shift click timestamp for double-tap detection
    private var lastClickTime = 0L

    fun processShiftKey(userKey: Int) {
        if (!isEnglish) {
            resetCaseState()
            return
        }
        val now = System.currentTimeMillis()
        val isDoubleClick = now - lastClickTime < 500
        mToggleStates.modifiers = when (mToggleStates.modifiers) {
            MASK_CASE_LOWER -> MASK_CASE_UPPER
            MASK_CASE_UPPER -> if (isDoubleClick) MASK_CASE_CAPS else MASK_CASE_LOWER
            MASK_CASE_CAPS -> MASK_CASE_LOWER
            else -> MASK_CASE_LOWER
        }
        lastClickTime = now
        val rimeCase = when (mToggleStates.modifiers) {
            MASK_CASE_CAPS -> KeyEvent.META_CAPS_LOCK_ON
            MASK_CASE_UPPER -> KeyEvent.META_SHIFT_ON
            else -> 0
        }
        EnginePipeline.send(EngineAction.SetCharCase(rimeCase))
        (KeyboardManager.instance.currentContainer as? InputBaseContainer)?.updateStates()
    }

    val isNumberSkb: Boolean
        get() = mInputMode and MASK_SKB_LAYOUT == MASK_SKB_LAYOUT_NUMBER

    val isTextEditSkb: Boolean
        get() = KeyboardManager.instance.currentContainer is org.bitfennec.lime.keyboard.container.TextEditContainer

    val isChinese: Boolean
        get() = mInputMode and MASK_LANGUAGE == MASK_LANGUAGE_CN

    val isChineseT9: Boolean
        get() = mInputMode and (MASK_SKB_LAYOUT or MASK_LANGUAGE) == MODE_T9_CHINESE

    val isChineseHandWriting: Boolean
        get() = mInputMode and (MASK_SKB_LAYOUT or MASK_LANGUAGE) == MODE_HANDWRITING_CHINESE

    val isEnglish: Boolean
        get() = mInputMode and MASK_LANGUAGE == MASK_LANGUAGE_EN
    val isLower: Boolean
        get() = mToggleStates.modifiers == MASK_CASE_LOWER

    fun resolveSoftKeyMetaState(): Int = resolveSoftKeyMetaState(isChineseT9, mToggleStates.modifiers)

    internal fun resolveSoftKeyMetaState(isT9: Boolean, modifiers: Int): Int {
        if (isT9) return 0
        return when (modifiers) {
            MASK_CASE_CAPS -> KeyEvent.META_CAPS_LOCK_ON
            MASK_CASE_UPPER -> KeyEvent.META_SHIFT_ON
            else -> 0
        }
    }

    /**
     * Saves new input mode.
     */
    fun saveInputMode(newInputMode: Int, applyEngine: Boolean = true) {
        val wasHandwriting = isChineseHandWriting
        val wasNumber = isNumberSkb
        mInputMode = newInputMode // Set active input mode
        if (wasHandwriting && !isChineseHandWriting) {
            KeyboardManager.instance.inputView?.endHandwritingSession()
        }
        if (wasNumber && !isNumberSkb) {
            KeyboardManager.instance.inputView?.clearCalcInterim()
        }
        if (isChinese || isEnglish) {
            mRecentLanguageInputMode = mInputMode
            getInstance().internal.inputDefaultMode.setValue(mInputMode)
        }
        mToggleStates.modifiers = MASK_CASE_LOWER
        if (applyEngine) {
            dispatchEditorPolicy(0)
        }
        (KeyboardManager.instance.currentContainer as? InputBaseContainer)?.updateStates()
    }

    fun currentRimeSchema(): String = if (isEnglish) {
        CustomConstant.SCHEMA_EN
    } else {
        when (val schema = getInstance().internal.pinyinModeRime.getValue()) {
            CustomConstant.SCHEMA_ZH_T9,
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY -> schema
            else -> CustomConstant.SCHEMA_ZH_QWERTY
        }
    }

    private fun applyEditorCharCase(flags: EditorFieldFlags): Int {
        val schema = currentRimeSchema()
        if (schema == CustomConstant.SCHEMA_ZH_T9 || isChineseT9) {
            mToggleStates.modifiers = MASK_CASE_LOWER
            return 0
        }
        return when {
            flags.isCapCharacters -> {
                mToggleStates.modifiers = MASK_CASE_CAPS
                KeyEvent.META_CAPS_LOCK_ON
            }
            flags.isCapWords || flags.isCapSentences -> {
                mToggleStates.modifiers = MASK_CASE_UPPER
                KeyEvent.META_SHIFT_ON
            }
            else -> {
                mToggleStates.modifiers = MASK_CASE_LOWER
                0
            }
        }
    }

    private fun dispatchEditorPolicy(charCase: Int) {
        EnginePipeline.send(
            EngineAction.ApplyEditorPolicy(
                schema = currentRimeSchema(),
                charCase = charCase,
                clearComposition = true,
            )
        )
    }

    /**
     * Resets temporary Shift uppercase state after key input (caps lock is preserved).
     */
    fun resetCharCase() {
        if (isEnglish && mToggleStates.modifiers == MASK_CASE_UPPER) resetCaseState()
    }

    /**
     * Resets input mode.
     */
    fun reset() {
        mInputMode = MODE_UNSET
        mRecentLanguageInputMode = MODE_UNSET
        currentEditorFieldFlags = EditorFieldFlags()
        mToggleStates.modifiers = MASK_CASE_LOWER
        EnginePipeline.send(EngineAction.SetCharCase(0))
    }

    private fun resetCaseState(forceEngineReset: Boolean = false) {
        val changed = mToggleStates.modifiers != MASK_CASE_LOWER
        mToggleStates.modifiers = MASK_CASE_LOWER
        if (forceEngineReset || changed) EnginePipeline.send(EngineAction.SetCharCase(0))
        if (changed) {
            (KeyboardManager.instance.currentContainer as? InputBaseContainer)?.updateStates()
        }
    }

}
