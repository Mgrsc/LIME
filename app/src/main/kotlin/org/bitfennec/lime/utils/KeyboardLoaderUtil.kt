package org.bitfennec.lime.utils

import android.view.KeyEvent
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.keyboard.model.SoftKeyToggle
import org.bitfennec.lime.keyboard.model.SoftKeyboard
import org.bitfennec.lime.keyboard.model.ToggleState
import org.bitfennec.lime.keyboard.KeyPreset
import org.bitfennec.lime.keyboard.KeyboardData
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.keyboard.doubleFlyMnemonicPreset
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
/**
 * Keyboard layout loader for QWERTY, T9, and handwriting layouts.
 */
class KeyboardLoaderUtil private constructor() {
    private var rimeValue: String? = null
    private var numberLine: Boolean = false
    fun clearKeyboardMap() {
        mSoftKeyboardMap.clear()
    }

    private fun loadBaseSkb(skbValue: Int): SoftKeyboard {
        // Shift key state
        // Direct input state
        val shiftToggleStates = ArrayList<ToggleState>()
        shiftToggleStates.add(ToggleState(0))
        shiftToggleStates.add(ToggleState(1))
        shiftToggleStates.add(ToggleState(2))
        // Spelling mode
        shiftToggleStates.add(ToggleState(3))
        shiftToggleStates.add(ToggleState(4))
        shiftToggleStates.add(ToggleState(5))

        val softKeyboard: SoftKeyboard?
        numberLine = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
        val isHandwritingOrNumber = skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING ||
            skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER
        val effectiveNumberLine = numberLine && !isHandwritingOrNumber
        val rows: MutableList<List<SoftKey>> = ArrayList()
        if (effectiveNumberLine) {
            val qwertyKeys = createNumberLineKeys(arrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))
            rows.add(qwertyKeys.asList())
        }
        when(skbValue){
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN -> {  // 1000 QWERTY Pinyin
                val isQwerty9 = AppPrefs.getInstance().keyboardSetting.qwerty9Geometry.getValue()
                rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
                val keys = if (isQwerty9) KeyboardData.layoutQwerty9Cn else KeyboardData.layoutQwertyCn
                val row1Keys = createQwertyPYKeys(keys[0])
                val row2Keys = createQwertyPYKeys(keys[1])
                val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT).apply {
                    setToggleStates(shiftToggleStates)
                }
                val row3Keys = createQwertyPYKeys(keys[2])
                applyQwertyLayoutGeometry(isQwerty9, row1Keys, row2Keys, softKeyToggle, row3Keys)

                rows.add(row1Keys.asList())
                rows.add(row2Keys.asList())
                rows.add(mutableListOf<SoftKey>().apply {
                    add(softKeyToggle)
                    addAll(row3Keys)
                })
                rows.add(lastRows(skbValue))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {  // 4000 English layout
                val isQwerty9 = AppPrefs.getInstance().keyboardSetting.qwerty9Geometry.getValue()
                val keys = if (isQwerty9) KeyboardData.layoutQwerty9En else KeyboardData.layoutQwertyEn
                val row1Keys = createQwertyKeys(keys[0])
                val row2Keys = createQwertyKeys(keys[1])
                val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT).apply {
                    setToggleStates(shiftToggleStates)
                }
                val row3Keys = createQwertyKeys(keys[2])
                applyQwertyLayoutGeometry(isQwerty9, row1Keys, row2Keys, softKeyToggle, row3Keys)

                rows.add(row1Keys.asList())
                rows.add(row2Keys.asList())
                rows.add(mutableListOf<SoftKey>().apply {
                    add(softKeyToggle)
                    addAll(row3Keys)
                })
                val keyBeans = lastRows(skbValue)
                keyBeans[keyBeans.size - 2].stateId = 1
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> {     // 5000 Numeric keypad
                var keyBeans: MutableList<SoftKey> = ArrayList()
                val keys =  KeyboardData.layoutT9Number
                var t9Key = createT9NumberKeys(keys[0])
                t9Key.first().apply {
                    widthF = 0.15f
                    heightF = 0.75f
                }
                for (i in 1 until t9Key.size - 1) {
                    t9Key[i].widthF = 0.2333f
                }
                t9Key.last().apply {
                    widthF = 0.15f
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = ArrayList()
                t9Key = createT9NumberKeys(keys[1])
                t9Key.first().mLeftF = 0.15f
                for (i in 0 until t9Key.size - 1) {
                    t9Key[i].widthF = 0.2333f
                }
                t9Key.last().apply {
                    widthF = 0.15f
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = ArrayList()
                t9Key = createT9NumberKeys(keys[2])
                t9Key.first().mLeftF = 0.15f
                for (i in 0 until t9Key.size - 1) {
                    t9Key[i].widthF = 0.2333f
                }
                t9Key.last().apply {
                    widthF = 0.15f
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN -> {     // 2000 T9 Pinyin
                var keyBeans: MutableList<SoftKey> = ArrayList()
                val keys =  KeyboardData.layoutT9Cn
                var t9Key = createT9Keys(keys[0])
                t9Key.first().apply {
                    widthF = 0.15f
                    heightF = 0.75f
                }
                for (i in 1 until t9Key.size - 1) {
                    t9Key[i].widthF = 0.2333f
                }
                t9Key.last().apply {
                    widthF = 0.15f
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = ArrayList()
                t9Key = createT9Keys(keys[1])
                t9Key.first().mLeftF = 0.15f
                for (i in 0 until t9Key.size - 1) {
                    t9Key[i].widthF = 0.2333f
                }
                t9Key.last().apply {
                    widthF = 0.15f
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = ArrayList()
                t9Key = createT9Keys(keys[2])
                t9Key.first().mLeftF = 0.15f
                for (i in 0 until t9Key.size - 1) {
                    t9Key[i].widthF = 0.2333f
                }
                t9Key.last().apply {
                    widthF = 0.15f
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING -> {  // 3000 Handwriting input
                val keyBeans: MutableList<SoftKey> = ArrayList()
                val keys = KeyboardData.layoutHandwritingCn[0]
                val numberKey = createHandwritingKey(keys[0]).apply {
                    widthF = 0.18f
                    heightF = 0.22f
                    mTopF = 0.78f
                    mLeftF = 0.0f
                }
                val symbolKey = createHandwritingKey(keys[1]).apply {
                    widthF = 0.15f
                    heightF = 0.22f
                    mTopF = 0.78f
                    mLeftF = 0.18f
                }
                val spaceKey = createHandwritingKey(keys[2]).apply {
                    widthF = 0.34f
                    heightF = 0.22f
                    mTopF = 0.78f
                    mLeftF = 0.33f
                }
                val delKey = createHandwritingKey(keys[3]).apply {
                    widthF = 0.15f
                    heightF = 0.22f
                    mTopF = 0.78f
                    mLeftF = 0.67f
                }
                val enterToggleStates = getEnterToggleStates()
                val enterKey = createKeyToggle(keys[4]).apply {
                    widthF = 0.18f
                    heightF = 0.22f
                    mTopF = 0.78f
                    mLeftF = 0.82f
                    stateId = 0
                    setToggleStates(enterToggleStates)
                }
                keyBeans.add(numberKey)
                keyBeans.add(symbolKey)
                keyBeans.add(spaceKey)
                keyBeans.add(delKey)
                keyBeans.add(enterKey)
                rows.add(keyBeans)
            }
        }
        softKeyboard = getSoftKeyboard(rows, effectiveNumberLine, skbValue)
        mSoftKeyboardMap[skbValue] = softKeyboard
        return softKeyboard
    }

    private fun getEnterToggleStates(): List<ToggleState> {
        val context = runCatching { Launcher.instance.context }.getOrNull()
        return if (context != null) {
            listOf(
                ToggleState(context.getString(R.string.action_go), 2),
                ToggleState(context.getString(R.string.action_search), 3),
                ToggleState(context.getString(R.string.action_send), 4),
                ToggleState(context.getString(R.string.action_next), 5),
                ToggleState(context.getString(R.string.action_done), 6),
                ToggleState(context.getString(R.string.action_previous), 7)
            )
        } else {
            listOf(
                ToggleState("Go", 2), ToggleState("Search", 3), ToggleState("Send", 4),
                ToggleState("Next", 5), ToggleState("Done", 6), ToggleState("Previous", 7)
            )
        }
    }

    // Bottom row (consistent across layouts, slightly modified for numpad)
    private fun lastRows(skbValue: Int): MutableList<SoftKey> {
        val enterToggleStates = getEnterToggleStates()
        val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_ENTER)
        softKeyToggle.widthF = 0.18f
        softKeyToggle.stateId = 0
        softKeyToggle.setToggleStates(enterToggleStates)
        val keyBeans = mutableListOf<SoftKey>()
        val t9Keys = when(skbValue) {
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN -> {
                createT9Keys(arrayOf(
                    InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                    KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG
                ))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {
                createQwertyKeys(arrayOf(
                    InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                    InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE,
                    InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_LANG
                ))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> {
                createT9NumberKeys(arrayOf(
                    InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_RETURN,
                    7, KeyEvent.KEYCODE_SPACE
                ))
            }
            else -> { // QWERTY Pinyin
                createQwertyPYKeys(arrayOf(
                    InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                    InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE,
                    InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_LANG
                ))
            }
        }
        if (t9Keys.size == 6) {
            softKeyToggle.widthF = 0.147f
            t9Keys[0].widthF = 0.147f
            t9Keys[1].widthF = 0.11f
            t9Keys[2].widthF = 0.10f
            t9Keys[3].widthF = 0.296f
            t9Keys[4].widthF = 0.10f
            t9Keys[5].widthF = 0.10f
        } else if (t9Keys.size == 5) {
            softKeyToggle.widthF = 0.147f
            t9Keys[0].widthF = 0.147f
            t9Keys[1].widthF = 0.099f
            t9Keys[2].widthF = 0.099f
            t9Keys[3].widthF = 0.396f
            t9Keys[4].widthF = 0.099f
        } else if (skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {
            softKeyToggle.widthF = 0.15f
            t9Keys[0].widthF = 0.15f
            t9Keys[1].widthF = 0.2333f
            t9Keys[2].widthF = 0.2333f
            t9Keys[3].widthF = 0.2333f
        } else {
            softKeyToggle.widthF = 0.15f
            t9Keys[0].widthF = 0.15f
            t9Keys[1].widthF = 0.15f
            t9Keys[2].widthF = 0.40f
            t9Keys[3].widthF = 0.15f
        }
        keyBeans.addAll(t9Keys)
        keyBeans.add(softKeyToggle)
        return keyBeans
    }

    fun getSoftKeyboard(skbValue: Int): SoftKeyboard {
        var softKeyboard = mSoftKeyboardMap[skbValue]
        if (softKeyboard == null) {
            softKeyboard = loadBaseSkb(skbValue)
        }
        return softKeyboard
    }

    fun changeSKBNumberRow() {
        val keys = mSoftKeyboardMap.keys.toList()
        for (item in keys) {
            mSoftKeyboardMap[item] = loadBaseSkb(item)
        }
    }

    /** Generates keyboard layout and computes bounding rects */
    private fun getSoftKeyboard(rows: List<List<SoftKey>>, isNumberRow: Boolean, skbValue: Int): SoftKeyboard {
        var lastKeyBottom = 0f
        var lastKeyRight: Float
        var lastKeyTop: Float
        for (rowBean in rows) {
            lastKeyTop = lastKeyBottom  // New row top matches previous row bottom
            lastKeyRight = 0f // New row X starts at 0
            for (keyBean in rowBean) {
                var keyXPos = keyBean.mLeftF
                var keyYPos = keyBean.mTopF
                val keyWidth = keyBean.widthF
                val keyHeight = keyBean.heightF
                if (keyXPos == -1f || keyYPos == -1f || isNumberRow) {
                    if (keyXPos == -1f) keyXPos = lastKeyRight
                    if (keyYPos == -1f) keyYPos = lastKeyTop
                    if (isNumberRow) {
                        keyBean.setKeyDimensions(keyXPos, keyYPos / 1.2f, keyHeight / 1.2f)
                    } else {
                        keyBean.setKeyDimensions(keyXPos, keyYPos)
                    }
                }
                keyBean.setSkbCoreSize(ImeEnvironment.skbWidth, ImeEnvironment.skbHeight)
                lastKeyRight = keyXPos + keyWidth
                lastKeyTop = keyYPos
                lastKeyBottom = keyYPos + keyHeight
            }
        }
        return SoftKeyboard(rows, buildLayoutId(skbValue, isNumberRow))
    }

    private fun buildLayoutId(skbValue: Int, isNumberRow: Boolean): String {
        val environment = ImeEnvironment
        val orientation = if (environment.isLandscape) "land" else "port"
        val numberLine = if (isNumberRow) "num1" else "num0"
        val isQwerty = skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN ||
            skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC
        val geo = if (isQwerty) {
            if (AppPrefs.getInstance().keyboardSetting.qwerty9Geometry.getValue()) "geo9" else "geo10"
        } else {
            "geo-"
        }
        return "${skbValue.toString(16)}:$orientation:$numberLine:$geo:${environment.skbWidth}:${environment.skbHeight}"
    }

    private fun createT9Keys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = KeyPreset.t9PYKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1)?: "").apply {
                widthF = 0.21f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createT9NumberKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = KeyPreset.t9NumberKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "").apply {
                widthF = 0.21f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyPYKeys(codes: Array<Int>): Array<SoftKey> {
        val keyMnemonicPreset = if (rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) doubleFlyMnemonicPreset else emptyMap()
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyPYKeyPreset else KeyPreset.qwertyPYKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = keyMnemonicPreset[code] ?: "").apply {
                widthF = 0.099f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyKeyPreset else KeyPreset.qwertyKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = labels?.getOrNull(2) ?: "").apply {
                widthF = 0.099f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createHandwritingKey(code: Int): SoftKey {
        val keyPreset = KeyPreset.t9PYKeyPreset
        val labels = keyPreset[code] ?: KeyPreset.qwertyKeyPreset[code]
        return SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "")
    }

    private fun createNumberLineKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        for(code in codes) {
            val softKey = SoftKey(label = code.toString()).apply {
                widthF = 0.099f
                heightF = 0.2f
            }
            softKeys.add(softKey)
        }
        return softKeys.toTypedArray()
    }

    private fun createKeyToggle(code: Int): SoftKeyToggle {
        return SoftKeyToggle(code)
    }

    private fun applyQwertyLayoutGeometry(
        isQwerty9: Boolean,
        row1Keys: Array<SoftKey>,
        row2Keys: Array<SoftKey>,
        shiftToggle: SoftKeyToggle,
        row3Keys: Array<SoftKey>,
    ) {
        if (isQwerty9) {
            for (k in row1Keys) {
                k.widthF = 0.111f
            }
            for (k in row2Keys) {
                k.widthF = 0.111f
            }
            shiftToggle.widthF = 0.139f
            for (i in 0 until row3Keys.size - 1) {
                row3Keys[i].widthF = 0.089f
            }
            row3Keys.last().widthF = 0.150f
        } else {
            row2Keys.first().mLeftF = 0.06f
            shiftToggle.widthF = 0.147f
            row3Keys.last().widthF = 0.147f
        }
    }

    companion object {
        private val mSoftKeyboardMap = HashMap<Int, SoftKeyboard?>() // Cache for loaded keyboards
        val instance: KeyboardLoaderUtil by lazy(LazyThreadSafetyMode.NONE) {
            KeyboardLoaderUtil()
        }
    }
}
