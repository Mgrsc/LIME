package org.bitfennec.lime.keyboard

import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.bitfennec.lime.R
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.manager.InputModeSwitcher

/**
 * Accessibility helper providing virtual node hierarchy for custom-drawn SoftKeyboard canvas.
 */
@Suppress("DEPRECATION")
class KeyboardAccessibilityHelper(private val keyboardView: BaseKeyboardView) : ExploreByTouchHelper(keyboardView) {

    private fun getAllKeys(): List<SoftKey> {
        return keyboardView.getFlattenedKeys()
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val key = keyboardView.getKeyIndices(x.toInt(), y.toInt(), useAdaptiveOffset = false) ?: return INVALID_ID
        val allKeys = getAllKeys()
        val index = allKeys.indexOf(key)
        return if (index >= 0) index else INVALID_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        val allKeys = getAllKeys()
        for (i in allKeys.indices) {
            virtualViewIds.add(i)
        }
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val allKeys = getAllKeys()
        if (virtualViewId !in allKeys.indices) {
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 0, 0))
            return
        }
        val key = allKeys[virtualViewId]
        node.setBoundsInParent(Rect(key.mLeft, key.mTop, key.mRight, key.mBottom))
        node.contentDescription = getKeyDescription(key)
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        if (key.code == KeyEvent.KEYCODE_SPACE && !InputModeSwitcher.isPrivateOrSensitive) {
            node.isLongClickable = true
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                keyboardView.context.getString(R.string.ime_settings_voice),
            ))
        }
        node.className = "android.widget.Button"
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK && !InputModeSwitcher.isPrivateOrSensitive) {
            val key = getAllKeys().getOrNull(virtualViewId) ?: return false
            return key.code == KeyEvent.KEYCODE_SPACE && keyboardView.startVoiceFromAccessibility()
        }
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            val allKeys = getAllKeys()
            if (virtualViewId in allKeys.indices) {
                val key = allKeys[virtualViewId]
                keyboardView.onKeyClickedFromAccessibility(key)
                return true
            }
        }
        return false
    }

    private fun getKeyDescription(key: SoftKey): String {
        val context = keyboardView.context
        return when (key.code) {
            KeyEvent.KEYCODE_SPACE -> context.getString(R.string.accessibility_key_space)
            KeyEvent.KEYCODE_DEL -> context.getString(R.string.accessibility_key_delete)
            KeyEvent.KEYCODE_ENTER -> context.getString(R.string.accessibility_key_enter)
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> context.getString(R.string.accessibility_key_shift)
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> context.getString(R.string.accessibility_key_symbol)
            KeyEvent.KEYCODE_LANGUAGE_SWITCH -> context.getString(R.string.accessibility_key_language)
            InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> context.getString(R.string.accessibility_key_cursor_move)
            else -> {
                val label = key.keyLabel
                if (label.isNotBlank()) label else key.label.ifBlank {
                    context.getString(R.string.accessibility_key_generic)
                }
            }
        }
    }
}
