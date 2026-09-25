package org.bitfennec.lime.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.KeyEvent
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.data.theme.ThemeManager.prefs
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.keyboard.model.SoftKeyToggle
import org.bitfennec.lime.keyboard.model.SoftKeyboard
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.environment.ImeEnvironment
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import android.graphics.Color
import org.bitfennec.lime.utils.DevicesUtils

/**
 * Soft keyboard view.
 */
open class TextKeyboard(context: Context?) : BaseKeyboardView(context){
    private var mKeyboardChanged = false
    private var mBuffer: Bitmap? = null
    private var mCanvas: Canvas? = null
    private var mNormalKeyTextSize = 0   // Normal key text size
    private var mNormalKeyTextSizeSmall = 0  // Small key text size
    private val mPaint: Paint = Paint()   // Paint for key rendering
    private val mFmi: FontMetricsInt
    private var isKeyBorder = false // Key borders enabled
    protected lateinit var mActiveTheme: Theme
    private var keyRadius = 0
    private var keyboardFontBold = false
    private var keyboardChineseUppercase = true
    private var keyboardSymbol = false


    init {
        mPaint.isAntiAlias = true
        mFmi = mPaint.fontMetricsInt
        keyboardFontBold = prefs.keyboardFontBold.getValue()
        keyboardChineseUppercase = prefs.keyboardChineseUppercase.getValue()
        keyboardSymbol = prefs.keyboardSymbol.getValue()
    }

    /** Sets keyboard model. */
    override fun setSoftKeyboard(softSkb: SoftKeyboard) {
        super.setSoftKeyboard(softSkb)
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = activeTheme
        mPaint.color = mActiveTheme.keyTextColor
        mKeyboardChanged = true
        invalidateView()
    }

    /** Refreshes key state. */
    fun updateStates() {
        var softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_ENTER) as? SoftKeyToggle
        softKey?.enableToggleState(InputModeSwitcher.mToggleStates.imeAction)
        softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_SHIFT_LEFT) as? SoftKeyToggle
        val isEnglishCell = AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()
        val shiftState = if (InputModeSwitcher.isEnglish) {
            InputModeSwitcher.mToggleStates.modifiers + (if (isEnglishCell) 3 else 0)
        } else {
            0
        }
        softKey?.enableToggleState(shiftState)
        invalidateView()
    }

    /** Resets theme styling. */
    open fun setTheme(theme: Theme) {
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = theme
        mPaint.color = mActiveTheme.keyTextColor
        invalidateView()
    }

    fun setKeyRadiusPreview(radius: Int) {
        keyRadius = radius
        mKeyboardChanged = true
        invalidateKey()
        invalidate()
    }

    fun setKeyBorderPreview(border: Boolean) {
        isKeyBorder = border
        mKeyboardChanged = true
        invalidateKey()
        invalidate()
    }

    fun setFontBoldPreview(bold: Boolean) {
        keyboardFontBold = bold
        mKeyboardChanged = true
        invalidateKey()
        invalidate()
    }

    fun setSymbolPreview(show: Boolean) {
        keyboardSymbol = show
        mKeyboardChanged = true
        invalidateKey()
        invalidate()
    }

    fun setChineseUppercasePreview(uppercase: Boolean) {
        keyboardChineseUppercase = uppercase
        mKeyboardChanged = true
        invalidateKey()
        invalidate()
    }

    private var previewKeyXMargin: Int? = null
    private var previewKeyYMargin: Int? = null

    fun setKeyMarginPreview(marginX: Int? = null, marginY: Int? = null) {
        if (marginX != null) previewKeyXMargin = marginX
        if (marginY != null) previewKeyYMargin = marginY
        mKeyboardChanged = true
        invalidateKey()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var measuredWidth = 0
        var measuredHeight = 0
        if (null != mSoftKeyboard) {
            measuredWidth = (mService?.currentKeyboardSurfaceWidth() ?: ImeEnvironment.skbWidth) + paddingLeft + paddingRight
            measuredHeight = ImeEnvironment.skbHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    private fun invalidateView() {
        mDirtyRect.setEmpty()
        mKeyboardChanged = true
        requestLayout()
        invalidateKey()
    }

    public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBuffer = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mDrawPending || mBuffer == null || mKeyboardChanged) {
            onBufferDraw()
        }
        if (mBuffer != null) {
            canvas.drawBitmap(mBuffer!!, 0f, 0f, null)
        }
    }

    override fun onBufferDraw() {
        if (mBuffer == null || mKeyboardChanged) {
            if (mBuffer == null || mBuffer!!.width != width || mBuffer!!.height != height) {
                val width = max(1.0, width.toDouble()).toInt()
                val height = max(1.0, height.toDouble()).toInt()
                mBuffer = createBitmap(width, height)
                mCanvas = Canvas(mBuffer!!)
            }
            mDirtyRect.set(0, 0, width, height)
            mKeyboardChanged = false
        }
        if (mSoftKeyboard == null) return
        val clip = if (mDirtyRect.isEmpty) Rect(0, 0, width, height) else Rect(mDirtyRect)
        mCanvas!!.withSave {
            val canvas = mCanvas
            canvas?.clipRect(clip)
            canvas?.drawColor(0x00000000, PorterDuff.Mode.CLEAR)
            val env = ImeEnvironment
            mNormalKeyTextSize = env.keyTextSize
            mNormalKeyTextSizeSmall = env.keyTextSmallSize
            val keyXMargin = previewKeyXMargin ?: mSoftKeyboard!!.keyXMargin
            val keyYMargin = previewKeyYMargin ?: mSoftKeyboard!!.keyYMargin
            for (softKeys in mSoftKeyboard!!.mKeyRows) {
                for (softKey in softKeys) {
                    if (softKey.mRight >= clip.left && softKey.mLeft <= clip.right &&
                        softKey.mBottom >= clip.top && softKey.mTop <= clip.bottom) {
                        canvas?.let { drawSoftKey(it, softKey, keyXMargin, keyYMargin) }
                    }
                }
            }
            mCanvas!!
        }
        mDrawPending = false
        mDirtyRect.setEmpty()
    }

    /** Renders a single soft key onto canvas. */
    companion object {
        const val KEY_PRESS_INSET_DP = 1.2f
        const val KEY_PRESS_DY_DP = 0.8f
    }

    private fun getSoftKeyBackgroundColor(softKey: SoftKey): Int {
        val baseColor = when (softKey.code) {
            KeyEvent.KEYCODE_ENTER -> mActiveTheme.accentKeyBackgroundColor
            KeyEvent.KEYCODE_SPACE -> mActiveTheme.functionKeyBackgroundColor
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DEL,
            InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_SYMBOL,
            InputModeSwitcher.USER_KEYCODE_LANG, InputModeSwitcher.USER_KEYCODE_RETURN -> mActiveTheme.functionKeyBackgroundColor
            else -> mActiveTheme.keyBackgroundColor
        }
        if (!softKey.pressed) return baseColor

        val isDark = ColorUtils.calculateLuminance(baseColor) < 0.5
        return if (isDark) {
            ColorUtils.blendARGB(baseColor, mActiveTheme.accentKeyBackgroundColor, 0.25f)
        } else {
            ColorUtils.blendARGB(baseColor, Color.BLACK, 0.12f)
        }
    }

    private fun drawSoftKey(canvas: Canvas, softKey: SoftKey, keyXMargin: Int, keyYMargin: Int) {
        val isPressed = softKey.pressed
        val pressInset = if (isPressed) DevicesUtils.dip2px(KEY_PRESS_INSET_DP) else 0
        val pressDy = if (isPressed) DevicesUtils.dip2px(KEY_PRESS_DY_DP) else 0

        val bg = GradientDrawable()
        if (isKeyBorder || isPressed) {
            val keyMarginX = if (keyXMargin == 0) (keyRadius / 10).coerceAtLeast(1) else keyXMargin
            val keyMarginY = if (keyYMargin == 0) (keyRadius / 10).coerceAtLeast(1) else keyYMargin
            val topKeyMarginY = if (ImeEnvironment.keyboardModeFloat && ImeEnvironment.isLandscape && softKey.mTop == 0) {
                min(keyMarginY, DevicesUtils.dip2px(4f))
            } else {
                keyMarginY
            }
            val background = if (isKeyBorder) {
                getSoftKeyBackgroundColor(softKey)
            } else {
                mActiveTheme.keyPressHighlightColor
            }
            bg.setColor(background)
            bg.cornerRadius = keyRadius.toFloat()
            bg.setBounds(
                softKey.mLeft + keyMarginX + pressInset,
                softKey.mTop + topKeyMarginY + pressInset,
                softKey.mRight - keyMarginX - pressInset,
                softKey.mBottom - keyMarginY - pressInset
            )
            bg.draw(canvas)
        }
        var keyLabel = displayKeyLabel(softKey, keyboardChineseUppercase)
        var keyLabelSmall = softKey.keyLabelSmall
        val keyMnemonic = softKey.keyMnemonic
        var keyIcon = softKey.keyIcon
        val textColor = if (softKey.code == KeyEvent.KEYCODE_ENTER) {
            mActiveTheme.accentKeyTextColor
        } else {
            mActiveTheme.keyTextColor
        }
        if (softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese) {
            keyLabel = "分词"
            keyIcon = null
        }
        if (softKey.code == InputModeSwitcher.USER_KEYCODE_LANG) {
            keyIcon = null
            keyLabel = if (InputModeSwitcher.isChinese) "中" else "En"
            keyLabelSmall = if (InputModeSwitcher.isChinese) "En" else "中"
        }
        if (keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall)) {
            mPaint.color = textColor
            mPaint.typeface = Typeface.DEFAULT
            mPaint.alpha = 180
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat()
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabelSmall)) / 2.0f
            val smallFm = mPaint.fontMetrics
            val y = softKey.mTop + softKey.height() * 0.26f - (smallFm.ascent + smallFm.descent) / 2.0f + pressDy
            canvas.drawText(keyLabelSmall, x, y, mPaint)
            mPaint.alpha = 255
        }
        if (null != keyIcon) {
            val isSpace = softKey.code == KeyEvent.KEYCODE_SPACE
            val maxIconHeight = if (isSpace) softKey.height() * 0.32f else softKey.height() * 0.52f
            val maxIconWidth = if (isSpace) softKey.width() * 0.45f else softKey.width() * 0.52f
            val aspect = if (keyIcon.intrinsicHeight > 0) keyIcon.intrinsicWidth.toFloat() / keyIcon.intrinsicHeight else 1f
            val iconH = min(maxIconHeight, maxIconWidth / aspect)
            val iconW = iconH * aspect
            val marginLeft = ((softKey.width() - iconW) / 2f).toInt()
            val marginTop = ((softKey.height() - iconH) / 2f).toInt()
            keyIcon.setTint(textColor)
            keyIcon.setBounds(
                softKey.mLeft + marginLeft,
                softKey.mTop + marginTop + pressDy,
                (softKey.mLeft + marginLeft + iconW).toInt(),
                (softKey.mTop + marginTop + iconH + pressDy).toInt()
            )
            keyIcon.draw(canvas)
        } else if (!TextUtils.isEmpty(keyLabel)) { // Centered label
            mPaint.color = textColor
            if (keyboardFontBold) mPaint.typeface = Typeface.DEFAULT_BOLD else mPaint.typeface = Typeface.DEFAULT
            var targetTextSize = when {
                keyLabel.length >= 3 -> mNormalKeyTextSize * 0.62f
                keyLabel.length == 2 -> mNormalKeyTextSize * 0.68f
                else -> mNormalKeyTextSize.toFloat()
            }
            mPaint.textSize = targetTextSize
            val maxTextWidth = (softKey.width() - keyXMargin * 2) * 0.82f
            val measuredW = mPaint.measureText(keyLabel)
            if (measuredW > maxTextWidth && measuredW > 0) {
                targetTextSize *= (maxTextWidth / measuredW)
                mPaint.textSize = targetTextSize
            }
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabel)) / 2.0f
            val fontMetrics = mPaint.fontMetrics
            val textCenterY = (fontMetrics.ascent + fontMetrics.descent) / 2.0f
            val centerY = (softKey.mTop + softKey.mBottom) / 2.0f
            val y = if (keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall)) {
                centerY + softKey.height() * 0.12f - textCenterY + pressDy
            } else {
                centerY - textCenterY + pressDy
            }
            canvas.drawText(keyLabel, x, y, mPaint)
        }
        if (InputModeSwitcher.isChinese && !TextUtils.isEmpty(keyMnemonic)) {  // Sub-mnemonic label (Double Pinyin)
            mPaint.color = textColor
            mPaint.typeface = Typeface.DEFAULT
            var targetMnemonicTextSize = mNormalKeyTextSizeSmall.toFloat() * 0.72f
            mPaint.textSize = targetMnemonicTextSize
            val maxMnemonicWidth = (softKey.width() - keyXMargin * 2) * 0.86f
            val measuredW = mPaint.measureText(keyMnemonic)
            if (measuredW > maxMnemonicWidth && measuredW > 0) {
                targetMnemonicTextSize *= (maxMnemonicWidth / measuredW)
                mPaint.textSize = targetMnemonicTextSize
            }
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyMnemonic)) / 2.0f
            val fontMetrics = mPaint.fontMetrics
            val textCenterY = (fontMetrics.ascent + fontMetrics.descent) / 2.0f
            val y = softKey.mTop + softKey.height() * 0.82f - textCenterY + pressDy
            canvas.drawText(keyMnemonic, x, y, mPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    override fun closing() {
        super.closing()
        mBuffer = null
        mCanvas = null
    }
}
