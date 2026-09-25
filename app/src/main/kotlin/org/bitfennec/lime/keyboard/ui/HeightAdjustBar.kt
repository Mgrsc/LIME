package org.bitfennec.lime.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.bitfennec.lime.utils.dp
import kotlin.math.roundToInt

/**
 * Top edge drag bar for live keyboard height adjustment.
 * Features:
 * 1. Positioned at keyboard top edge with transparent overlay;
 * 2. Real-time dynamic height scaling with haptic snap at 100%;
 * 3. Integrated Reset and Done capsule buttons.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class HeightAdjustBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var inputView: InputView? = null

    private lateinit var btnReset: TextView
    private lateinit var tvHeightInfo: TextView
    private lateinit var handlePill: View
    private lateinit var btnDone: TextView
    private lateinit var centerContainer: LinearLayout

    private var initialRawY = 0f
    private var initialRatio = 0.245f
    private var isDragging = false
    private var lastHapticPercent = -1

    var onDismissListener: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dp(12)
        val padV = dp(6)
        setPadding(padH, padV, padH, padV)
        initViews()
    }

    fun attachInputView(view: InputView) {
        this.inputView = view
    }

    private fun initViews() {
        val theme = activeTheme
        val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat().coerceAtLeast(dp(8f).toFloat())

        // 1. Top bar background
        val barBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                keyRadius, keyRadius,
                keyRadius, keyRadius,
                0f, 0f,
                0f, 0f
            )
            setColor(theme.keyboardColor)
            setStroke(DevicesUtils.dip2px(1f), Color.argb(40, Color.red(theme.keyTextColor), Color.green(theme.keyTextColor), Color.blue(theme.keyTextColor)))
        }
        background = barBg

        // 2. Reset button
        btnReset = TextView(context).apply {
            text = context.getString(R.string.setting_ime_keyboard_height_reset)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            val btnPadH = dp(10)
            val btnPadV = dp(5)
            setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
            background = GradientDrawable().apply {
                setColor(theme.keyBackgroundColor)
                cornerRadius = keyRadius
            }
            setOnClickListener {
                DevicesUtils.tryVibrate(this)
                resetHeight()
            }
        }
        addView(btnReset, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // 3. Drag handle area (percentage text + indicator capsule)
        centerContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }

        tvHeightInfo = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
        }

        handlePill = View(context).apply {
            val pillBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3f).toFloat()
                setColor(theme.accentKeyBackgroundColor)
            }
            background = pillBg
            layoutParams = LayoutParams(dp(44), DevicesUtils.dip2px(4.5f)).apply {
                topMargin = dp(3)
            }
        }

        centerContainer.addView(tvHeightInfo)
        centerContainer.addView(handlePill)
        addView(centerContainer)

        // 4. Done button
        btnDone = TextView(context).apply {
            text = context.getString(R.string.setting_ime_keyboard_height_sure)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accentKeyTextColor)
            gravity = Gravity.CENTER
            val btnPadH = dp(14)
            val btnPadV = dp(5)
            setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
            background = GradientDrawable().apply {
                setColor(theme.accentKeyBackgroundColor)
                cornerRadius = keyRadius
            }
            setOnClickListener {
                DevicesUtils.tryVibrate(this)
                dismiss()
            }
        }
        addView(btnDone, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        updateHeightInfo()
    }

    /**
     * Updates height percentage label and handle color.
     */
    private fun updateHeightInfo() {
        val isLand = ImeEnvironment.isLandscape
        val defaultRatio = if (isLand) 0.34f else 0.245f
        val currentRatio = ImeEnvironment.keyBoardHeightRatio
        val percent = (currentRatio / defaultRatio * 100).roundToInt()
        tvHeightInfo.text = context.getString(R.string.height_adjust_prompt, percent)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val screenHeight = ImeEnvironment.screenHeight
        if (screenHeight <= 0) return super.onTouchEvent(event)
        val isLand = ImeEnvironment.isLandscape
        val defaultRatio = if (isLand) 0.34f else 0.245f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialRawY = event.rawY
                initialRatio = ImeEnvironment.keyBoardHeightRatio
                isDragging = true
                lastHapticPercent = (initialRatio / defaultRatio * 100).roundToInt()
                parent?.requestDisallowInterceptTouchEvent(true)
                handlePill.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return true
                val isFloat = ImeEnvironment.keyboardModeFloat
                val dy = initialRawY - event.rawY // Upward drag increases height
                val deltaRatio = if (isLand && isFloat) {
                    val baseLandscapeFloatHeight = DevicesUtils.dip2px(142).toFloat()
                    if (baseLandscapeFloatHeight > 0f) (dy / baseLandscapeFloatHeight) * defaultRatio else dy / screenHeight.toFloat()
                } else {
                    dy / screenHeight.toFloat()
                }
                val minRatio = if (isLand) 0.22f else 0.16f
                val maxRatio = if (isLand) 0.38f else 0.42f
                val targetRatio = (initialRatio + deltaRatio).coerceIn(minRatio, maxRatio)

                applyHeightRatio(targetRatio)

                val currentPercent = (targetRatio / defaultRatio * 100).roundToInt()
                // Haptic snap feedback when crossing 100% default height
                if ((lastHapticPercent < 100 && currentPercent >= 100) || (lastHapticPercent > 100 && currentPercent <= 100)) {
                    DevicesUtils.tryVibrate(this, HapticEvent.STEP)
                }
                lastHapticPercent = currentPercent
                updateHeightInfo()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    handlePill.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Applies height scale and triggers redraw.
     */
    private fun applyHeightRatio(ratio: Float) {
        val env = ImeEnvironment
        if (Math.abs(env.keyBoardHeightRatio - ratio) < 0.001f) return

        env.keyBoardHeightRatio = ratio
        context?.let { env.initData(it) }

        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        KeyboardManager.instance.switchKeyboard()

        inputView?.let { iv ->
            iv.requestLayout()
            iv.post { iv.requestApplyInsets() }
        }
    }

    /**
     * Synchronizes theme colors and corner radius.
     */
    fun updateTheme() {
        val theme = activeTheme
        val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat().coerceAtLeast(dp(8f).toFloat())

        val barBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                keyRadius, keyRadius,
                keyRadius, keyRadius,
                0f, 0f,
                0f, 0f
            )
            setColor(theme.keyboardColor)
            setStroke(DevicesUtils.dip2px(1f), Color.argb(40, Color.red(theme.keyTextColor), Color.green(theme.keyTextColor), Color.blue(theme.keyTextColor)))
        }
        background = barBg

        if (::btnReset.isInitialized) {
            btnReset.setTextColor(theme.keyTextColor)
            btnReset.background = GradientDrawable().apply {
                setColor(theme.keyBackgroundColor)
                cornerRadius = keyRadius
            }
        }

        if (::tvHeightInfo.isInitialized) {
            tvHeightInfo.setTextColor(theme.keyTextColor)
        }

        if (::handlePill.isInitialized) {
            handlePill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3f).toFloat()
                setColor(Color.argb(90, Color.red(theme.keyTextColor), Color.green(theme.keyTextColor), Color.blue(theme.keyTextColor)))
            }
        }

        if (::btnDone.isInitialized) {
            btnDone.setTextColor(theme.accentKeyTextColor)
            btnDone.background = GradientDrawable().apply {
                setColor(theme.accentKeyBackgroundColor)
                cornerRadius = keyRadius
            }
        }
    }

    /**
     * Resets to default height.
     */
    private fun resetHeight() {
        val isLand = ImeEnvironment.isLandscape
        val defaultRatio = if (isLand) 0.34f else 0.245f
        applyHeightRatio(defaultRatio)
        updateHeightInfo()
    }

    fun show() {
        updateTheme()
        visibility = VISIBLE
        updateHeightInfo()
        alpha = 0f
        translationY = -dp(20f).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    fun dismiss() {
        animate().alpha(0f).translationY(-dp(20f).toFloat()).setDuration(150).withEndAction {
            visibility = GONE
            onDismissListener?.invoke()
        }.start()
    }

    fun isShowing(): Boolean = isVisible
}
