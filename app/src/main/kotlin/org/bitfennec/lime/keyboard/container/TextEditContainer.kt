package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.prefs.InputFeedbacks
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.dp

/**
 * Text editing keyboard container.
 * 3x4 ergonomic layout:
 * - Row 1: Home (1), Up (2), End (3), SelectAll/Cut (4)
 * - Row 2: Left (5), Space (6), Right (7), Paste (8)
 * - Row 3: Copy (9), Down (10), Delete (11), Return (12)
 */
@SuppressLint("ViewConstructor")
class TextEditContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {

    private val mRootView: View

    // 12 key controls in 3x4 grid
    private val btnHome: TextView
    private val btnUp: TextView
    private val btnEnd: TextView
    private val btnSelectAllOrCut: TextView

    private val btnLeft: TextView
    private val btnSpace: TextView
    private val btnRight: TextView
    private val btnPaste: TextView

    private val btnCopy: TextView
    private val btnDown: TextView
    private val btnDelete: TextView
    private val btnBack: TextView

    private var hasTextSelected = false
    private val mHandler = Handler(Looper.getMainLooper())

    init {
        val inflater = LayoutInflater.from(context)
        mRootView = inflater.inflate(R.layout.layout_ime_keyboard_text_edit, this, false)
        addView(mRootView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Bind Row 1
        btnHome = mRootView.findViewById(R.id.btn_edit_home)
        btnUp = mRootView.findViewById(R.id.btn_edit_up)
        btnEnd = mRootView.findViewById(R.id.btn_edit_end)
        btnSelectAllOrCut = mRootView.findViewById(R.id.btn_edit_select_all)

        // Bind Row 2
        btnLeft = mRootView.findViewById(R.id.btn_edit_left)
        btnSpace = mRootView.findViewById(R.id.btn_edit_space)
        btnRight = mRootView.findViewById(R.id.btn_edit_right)
        btnPaste = mRootView.findViewById(R.id.btn_edit_paste)

        // Bind Row 3
        btnCopy = mRootView.findViewById(R.id.btn_edit_copy)
        btnDown = mRootView.findViewById(R.id.btn_edit_down)
        btnDelete = mRootView.findViewById(R.id.btn_edit_delete)
        btnBack = mRootView.findViewById(R.id.btn_edit_back)

        val allButtons = listOf(
            btnHome, btnUp, btnEnd, btnSelectAllOrCut,
            btnLeft, btnSpace, btnRight, btnPaste,
            btnCopy, btnDown, btnDelete, btnBack
        )
        val iconSize = dp(22)
        allButtons.forEach { button ->
            val icon = button.compoundDrawables[1] ?: return@forEach
            icon.setBounds(0, 0, iconSize, iconSize)
            button.setCompoundDrawables(null, icon, null, null)
            button.setSingleLine()
            button.ellipsize = TextUtils.TruncateAt.END
            // Top compound drawables follow padding, not the text's vertical gravity.
            button.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val fontMetrics = button.paint.fontMetrics
                val textHeight = kotlin.math.max(button.lineHeight, kotlin.math.ceil((fontMetrics.descent - fontMetrics.ascent).toDouble()).toInt())
                val contentHeight = iconSize + button.compoundDrawablePadding + textHeight
                val topPadding = ((button.height - contentHeight) / 2).coerceAtLeast(0)
                button.setPadding(dp(4), topPadding, dp(4), 0)
            }
        }

        setupListeners()
        applyTheme()
    }

    private fun setupListeners() {
        // D-Pad directional keys with long-press auto-repeat
        setupRepeatButton(btnLeft) {
            triggerFeedback(btnLeft, InputFeedbacks.SoundEffect.Standard)
            inputView.service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        setupRepeatButton(btnRight) {
            triggerFeedback(btnRight, InputFeedbacks.SoundEffect.Standard)
            inputView.service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        setupRepeatButton(btnUp) {
            triggerFeedback(btnUp, InputFeedbacks.SoundEffect.Standard)
            inputView.service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
        }
        setupRepeatButton(btnDown) {
            triggerFeedback(btnDown, InputFeedbacks.SoundEffect.Standard)
            inputView.service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }

        // Home and End of line
        btnHome.setOnClickListener {
            triggerFeedback(btnHome, InputFeedbacks.SoundEffect.Standard)
            inputView.service.sendCombinationKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
        }
        btnEnd.setOnClickListener {
            triggerFeedback(btnEnd, InputFeedbacks.SoundEffect.Standard)
            inputView.service.sendCombinationKeyEvents(KeyEvent.KEYCODE_MOVE_END)
        }

        // Space with auto-repeat
        setupRepeatButton(btnSpace) {
            triggerFeedback(btnSpace, InputFeedbacks.SoundEffect.Standard)
            inputView.service.sendDownUpKeyEvents(KeyEvent.KEYCODE_SPACE)
        }

        // Dynamic Select-All / Cut key
        btnSelectAllOrCut.setOnClickListener {
            triggerFeedback(btnSelectAllOrCut, InputFeedbacks.SoundEffect.Standard)
            if (hasTextSelected) {
                inputView.service.commitTextEditMenu(android.R.id.cut)
            } else {
                inputView.service.commitTextEditMenu(android.R.id.selectAll)
            }
        }

        // Copy
        btnCopy.setOnClickListener {
            triggerFeedback(btnCopy, InputFeedbacks.SoundEffect.Standard)
            inputView.service.commitTextEditMenu(android.R.id.copy)
        }

        // Paste
        btnPaste.setOnClickListener {
            triggerFeedback(btnPaste, InputFeedbacks.SoundEffect.Standard)
            inputView.service.commitTextEditMenu(android.R.id.paste)
        }

        // Delete with auto-repeat
        setupRepeatButton(btnDelete) {
            triggerFeedback(btnDelete, InputFeedbacks.SoundEffect.Delete)
            inputView.service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }

        // Return to normal keyboard
        btnBack.setOnClickListener {
            triggerFeedback(btnBack, InputFeedbacks.SoundEffect.Return)
            KeyboardManager.instance.switchKeyboard()
        }
    }

    private fun setupRepeatButton(view: View, action: () -> Unit) {
        val runnable = object : Runnable {
            override fun run() {
                if (!view.isPressed || !view.isAttachedToWindow || !view.isShown ||
                    view.windowVisibility != VISIBLE
                ) {
                    view.isPressed = false
                    return
                }
                action()
                mHandler.postDelayed(this, 50)
            }
        }
        view.isSoundEffectsEnabled = false
        view.setOnClickListener { action() }
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performClick()
                    mHandler.removeCallbacks(runnable)
                    mHandler.postDelayed(runnable, 350)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mHandler.removeCallbacks(runnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerFeedback(view: View, effect: InputFeedbacks.SoundEffect) {
        InputFeedbacks.hapticFeedback(view)
        InputFeedbacks.soundEffect(effect)
    }

    /**
     * Selection change notification from InputView.onUpdateSelection.
     */
    fun onSelectionUpdated(selStart: Int, selEnd: Int) {
        val hasSelection = (selStart != selEnd)
        if (hasTextSelected != hasSelection) {
            hasTextSelected = hasSelection
            updateSelectAllOrCutButton()
        }
    }

    override fun updateSkbLayout() {
        applyTheme()
    }

    fun applyTheme() {
        val theme = ThemeManager.activeTheme
        val rawRadius = ThemeManager.prefs.keyRadius.getValue().toFloat()
        val cornerRadius = rawRadius.coerceAtLeast(DevicesUtils.dip2px(8).toFloat())

        val normalButtons = listOf(
            btnHome, btnUp, btnEnd,
            btnLeft, btnSpace, btnRight, btnPaste,
            btnCopy, btnDown, btnDelete
        )

        normalButtons.forEach {
            it.background = createCardDrawable(theme.keyBackgroundColor, theme.keyPressHighlightColor, cornerRadius)
            TextViewCompat.setCompoundDrawableTintList(it, ColorStateList.valueOf(theme.keyTextColor))
            it.setTextColor(theme.keyTextColor)
        }

        // Return key (function key styling)
        btnBack.background = createCardDrawable(theme.functionKeyBackgroundColor, theme.functionKeyPressHighlightColor, cornerRadius)
        TextViewCompat.setCompoundDrawableTintList(btnBack, ColorStateList.valueOf(theme.keyTextColor))
        btnBack.setTextColor(theme.keyTextColor)

        updateSelectAllOrCutButton()
    }

    private fun updateSelectAllOrCutButton() {
        val theme = ThemeManager.activeTheme
        val rawRadius = ThemeManager.prefs.keyRadius.getValue().toFloat()
        val cornerRadius = rawRadius.coerceAtLeast(DevicesUtils.dip2px(8).toFloat())

        if (hasTextSelected) {
            // Morph to Cut state (accent highlight)
            btnSelectAllOrCut.background = createCardDrawable(theme.accentKeyBackgroundColor, theme.keyPressHighlightColor, cornerRadius)
            setSelectAllOrCutIcon(R.drawable.ic_edit_cut)
            TextViewCompat.setCompoundDrawableTintList(btnSelectAllOrCut, ColorStateList.valueOf(theme.accentKeyTextColor))
            btnSelectAllOrCut.setTextColor(theme.accentKeyTextColor)
            btnSelectAllOrCut.text = context.getString(R.string.text_edit_cut)
        } else {
            // Standard Select-All state
            btnSelectAllOrCut.background = createCardDrawable(theme.keyBackgroundColor, theme.keyPressHighlightColor, cornerRadius)
            setSelectAllOrCutIcon(R.drawable.ic_edit_select_all)
            TextViewCompat.setCompoundDrawableTintList(btnSelectAllOrCut, ColorStateList.valueOf(theme.keyTextColor))
            btnSelectAllOrCut.setTextColor(theme.keyTextColor)
            btnSelectAllOrCut.text = context.getString(R.string.text_edit_select_all)
        }
    }

    private fun setSelectAllOrCutIcon(resource: Int) {
        val icon = AppCompatResources.getDrawable(context, resource)?.mutate() ?: return
        val size = dp(22)
        icon.setBounds(0, 0, size, size)
        btnSelectAllOrCut.setCompoundDrawables(null, icon, null, null)
    }

    private fun createCardDrawable(normalColor: Int, pressedColor: Int, cornerRadius: Float): RippleDrawable {
        val backgroundShape = GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            setColor(normalColor)
            setCornerRadius(cornerRadius)
            setStroke(dp(1), Color.argb(35, Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor)))
        }
        val maskShape = GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            setColor(Color.WHITE)
            setCornerRadius(cornerRadius)
        }
        val rippleColor = ColorStateList.valueOf(pressedColor)
        return RippleDrawable(rippleColor, backgroundShape, maskShape)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mHandler.removeCallbacksAndMessages(null)
    }

    fun getMenuMode(): SkbMenuMode = SkbMenuMode.TextEdit
}
