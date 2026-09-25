package org.bitfennec.lime.view.popup

import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.PopupMenuMode
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.R

class PopupComponent private constructor() {
    private var showingEntryUi: PopupEntryUi? = null
    private val entryUi by lazy { PopupEntryUi(Launcher.instance.context) }

    private var showingContainerUi: PopupContainerUi? = null
    private var showingActionCapsuleUi: PopupActionCapsuleUi? = null

    private val popupRadius by lazy {
        ThemeManager.prefs.keyRadius.getValue().toFloat()
    }

    val root: FrameLayout by lazy {
        FrameLayout(Launcher.instance.context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false
        }
    }

    companion object {
        private var instance: PopupComponent? = null
            get() {
                if (field == null) field = PopupComponent()
                return field
            }
        fun get(): PopupComponent {
            return instance!!
        }
    }

    fun showPopup(content: String, bounds: Rect) {
        showingEntryUi?.apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = entryUi.apply {
            lastShowTime = System.currentTimeMillis()
            setBackground(ThemeManager.activeTheme, popupRadius)
            setText(content)
        }
        val lp = FrameLayout.LayoutParams(bounds.width(), bounds.height()).apply {
            bottomMargin = ImeEnvironment.skbAreaHeight - (bounds.top + bounds.bottom) / 2
        }
        popup.root.translationX = (ImeEnvironment.leftMarginWidth + bounds.left).toFloat()
        if (popup.root.parent == null) {
            root.addView(popup.root, lp)
        } else {
            val current = popup.root.layoutParams as FrameLayout.LayoutParams
            if (current.width != lp.width || current.height != lp.height ||
                current.bottomMargin != lp.bottomMargin
            ) {
                popup.root.layoutParams = lp
            }
        }
        popup.root.visibility = View.VISIBLE
        showingEntryUi = popup
    }

    fun showKeyboard(label: String, labelSmall: String, bounds: Rect) {
        showingEntryUi?.setText("") ?: showPopup("", bounds)
        val labels = keyboardPopupLabels(label, labelSmall)
        if (labels.isNotEmpty()) {
            reallyShowKeyboard(labels, bounds)
        } else {
            dismissPopup()
        }
    }

    fun showKeyboardMenu(mCurrentKey: SoftKey, bounds: Rect, distanceY: Float) {
        val ctx = runCatching { Launcher.instance.context }.getOrNull()
        val key = when (mCurrentKey.code) {
            InputModeSwitcher.USER_KEYCODE_LANG -> Pair(PopupMenuMode.SwitchIME, "🌐")
            InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI -> Pair(PopupMenuMode.EMOJI, "😆")
            KeyEvent.KEYCODE_SHIFT_LEFT -> {
                if (InputModeSwitcher.isChinese) {
                    return
                }
                val label = if (AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()) {
                    ctx?.getString(R.string.popup_mode_direct) ?: "Direct"
                } else {
                    ctx?.getString(R.string.popup_mode_pinyin) ?: "Pinyin"
                }
                Pair(PopupMenuMode.EnglishCell, label)
            }
            KeyEvent.KEYCODE_DEL -> Pair(PopupMenuMode.Clear, ctx?.getString(R.string.popup_slide_up_clear) ?: "Clear")
            InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> Pair(PopupMenuMode.Move, "")
            else -> Pair(PopupMenuMode.Enter, ctx?.getString(R.string.popup_return) ?: "Enter")
        }
        showingEntryUi?.setText("") ?: showPopup("", bounds)
        reallyMenuKeyboard(key, bounds, mCurrentKey.code != KeyEvent.KEYCODE_DEL)
    }

    fun onGestureEvent(distance: Float) {
        showingContainerUi?.onGestureEvent(distance)
    }

    private fun reallyMenuKeyboard(key: Pair<PopupMenuMode, String>, bounds: Rect, isSelect: Boolean) {
        val popupWidth = ImeEnvironment.skbWidth.div(10) * key.second.length / 2
        val keyboardUi = PopupKeyboardMenuUi(bounds, { dismissPopup() }, popupRadius, popupWidth, isSelect, key)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ImeEnvironment.skbAreaHeight - (bounds.top + bounds.bottom) / 2
            leftMargin = ImeEnvironment.leftMarginWidth + bounds.left + keyboardUi.offsetX
        }
        root.addView(keyboardUi.root, lp)
        dismissPopup()
        // The hidden entry must not contribute to the menu's wrap-content height.
        entryUi.root.visibility = View.GONE
        showingContainerUi = keyboardUi
    }

    private fun reallyShowKeyboard(keys: Array<String>, bounds: Rect) {
        val popupWidth = ImeEnvironment.skbWidth.div(10)
        val keyboardUi = PopupKeyboardUi(bounds, { dismissPopup() }, popupRadius, popupWidth, keys)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ImeEnvironment.skbAreaHeight - (bounds.top + bounds.bottom) / 2
            leftMargin = ImeEnvironment.leftMarginWidth + bounds.left + keyboardUi.offsetX
        }
        root.addView(keyboardUi.root, lp)
        dismissPopup()
        // The hidden entry must not contribute to the menu's wrap-content height.
        entryUi.root.visibility = View.GONE
        showingContainerUi = keyboardUi
    }

    fun changeFocus(x: Float, y: Float): Boolean {
        return showingContainerUi?.changeFocus(x, y) ?: false
    }

    fun triggerFocused(): Pair<PopupMenuMode, String> {
        return showingContainerUi?.onTrigger() ?: Pair(PopupMenuMode.None, "")
    }

    fun showActionPopup(iconRes: Int, content: String) {
        showingEntryUi = null
        entryUi.root.visibility = View.GONE

        val capsule = showingActionCapsuleUi ?: PopupActionCapsuleUi(Launcher.instance.context).also {
            showingActionCapsuleUi = it
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = (ImeEnvironment.skbAreaHeight - ImeEnvironment.heightForCandidatesArea - dp(40f)).coerceAtLeast(dp(60f))
            }
            it.root.alpha = 0f
            it.root.scaleX = 0.88f
            it.root.scaleY = 0.88f
            root.addView(it.root, lp)
            it.root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120)
                .start()
        }
        capsule.setContent(iconRes, content, ThemeManager.activeTheme)
    }

    fun dismissPopup() {
        dismissPopupContainer()
        dismissActionCapsule()
        showingEntryUi?.also {
            showingEntryUi = null
            it.root.visibility = View.INVISIBLE
        }
    }

    private fun dismissActionCapsule() {
        showingActionCapsuleUi?.also {
            showingActionCapsuleUi = null
            root.removeView(it.root)
        }
    }

    private fun dismissPopupContainer() {
        showingContainerUi?.also {
            showingContainerUi = null
            root.removeView(it.root)
        }
    }
}
