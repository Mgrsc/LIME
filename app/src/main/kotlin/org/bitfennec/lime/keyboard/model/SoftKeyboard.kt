package org.bitfennec.lime.keyboard.model

import org.bitfennec.lime.environment.ImeEnvironment
class SoftKeyboard(
    var mKeyRows: List<List<SoftKey>>,
    val layoutId: String = "",
) {
    val keyXMargin = ImeEnvironment.keyXMargin
    val keyYMargin = ImeEnvironment.keyYMargin
    var keyCenterOffsetProvider: KeyCenterOffsetProvider? = null

    init {
        updateKeySize()
    }

    fun updateKeySize() {
        val skbWidth = ImeEnvironment.skbWidth
        val skbHeight = ImeEnvironment.skbHeight
        val totalRows = mKeyRows.size
        for ((rowIndex, element) in mKeyRows.withIndex()) {
            val isBottom = totalRows > 1 && rowIndex >= totalRows - 2
            for (sKey in element) {
                sKey.isBottomRow = isBottom
                sKey.setSkbCoreSize(skbWidth, skbHeight)
            }
        }
        KeyboardHitTester.updateHitRects(mKeyRows)
    }

    /**
     * Finds key by coordinates; returns closest key if coordinates fall outside all keys.
     */
    fun mapToKey(x: Int, y: Int, hysteresisKey: SoftKey? = null, useAdaptiveOffset: Boolean = true): SoftKey? {
        val offsetProvider = if (useAdaptiveOffset) keyCenterOffsetProvider else null
        return KeyboardHitTester.mapToKey(mKeyRows, x, y, hysteresisKey, layoutId, offsetProvider)
    }

    /**
     * Finds key by keyCode.
     */
    fun getKeyByCode(code: Int): SoftKey? {
        for (keyRow in mKeyRows) {
            for (sKey in keyRow) {
                if (sKey.code == code) return sKey
            }
        }
        return null
    }

}
