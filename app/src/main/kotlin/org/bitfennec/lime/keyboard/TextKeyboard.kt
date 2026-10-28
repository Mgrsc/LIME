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
import org.bitfennec.lime.service.DecodingInfo
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import android.graphics.Color
import android.graphics.RectF
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.LimeTypefaces

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
    private val mShadowPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mShadowRect: RectF = RectF()
    private val mGlyphBounds: Rect = Rect()
    private val mKeyBgDrawable: GradientDrawable = GradientDrawable()
    private val mBracketPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val mFmi: FontMetricsInt
    private var isKeyBorder = false // Key borders enabled
    protected lateinit var mActiveTheme: Theme
    private var keyRadius = 0
    private var keyboardFontBold = false
    private var keyboardChineseUppercase = true
    private var keyboardSymbol = false
    private var lastComposingActive = false
    private var mRegularTypeface: Typeface = LimeTypefaces.regular
    private var mMediumTypeface: Typeface = LimeTypefaces.medium


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
        lastComposingActive = !DecodingInfo.isEngineFinish
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

    fun refreshCompositionState() {
        val isComposing = !DecodingInfo.isEngineFinish
        if (isComposing == lastComposingActive) return
        lastComposingActive = isComposing
        val shiftKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_SHIFT_LEFT)
        if (shiftKey != null && mBuffer != null && !mKeyboardChanged) {
            mDirtyRect.union(shiftKey.mLeft, shiftKey.mTop, shiftKey.mRight, shiftKey.mBottom)
            mDrawPending = true
            invalidate()
        } else {
            invalidateKey()
            invalidate()
        }
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
            mRegularTypeface = LimeTypefaces.regular
            mMediumTypeface = LimeTypefaces.medium
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
        const val KEY_PRESS_DY_DP = 0.6f
        private const val SMALL_LABEL_ALPHA = 120
        private const val IDLE_SYLLABLE_LABEL_ALPHA = 110
        private const val SMALL_LABEL_Y_RATIO = 0.24f
        private const val MAIN_LABEL_WITH_SMALL_OFFSET_RATIO = 0.11f
        private const val FUNCTION_KEY_TEXT_SCALE = 0.62f
        private const val LANG_PRIMARY_TEXT_SCALE = 0.54f
        private const val LANG_SECONDARY_TEXT_SCALE = 0.42f
        private const val LANG_SECONDARY_ALPHA = 145
        private const val DARK_KEY_BORDER_COLOR = 0x18FFFFFF
        private const val LIGHT_KEY_BORDER_COLOR = 0x10000000
        private const val DARK_KEY_SHADOW_COLOR = 0x42000000
        private const val LIGHT_KEY_SHADOW_COLOR = 0x22000000
    }

    private fun getSoftKeyBackgroundColor(softKey: SoftKey): Int {
        val baseColor = when (softKey.code) {
            KeyEvent.KEYCODE_ENTER -> mActiveTheme.accentKeyBackgroundColor
            KeyEvent.KEYCODE_SPACE -> mActiveTheme.keyBackgroundColor
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR,
            InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_SYMBOL,
            InputModeSwitcher.USER_KEYCODE_LANG, InputModeSwitcher.USER_KEYCODE_RETURN,
            InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> mActiveTheme.functionKeyBackgroundColor
            else -> mActiveTheme.keyBackgroundColor
        }
        if (!softKey.pressed) return baseColor

        val isDark = ColorUtils.calculateLuminance(baseColor) < 0.5
        return if (isDark) {
            ColorUtils.blendARGB(baseColor, Color.WHITE, 0.16f)
        } else {
            ColorUtils.blendARGB(baseColor, Color.BLACK, 0.10f)
        }
    }

    private fun drawSoftKey(canvas: Canvas, softKey: SoftKey, keyXMargin: Int, keyYMargin: Int) {
        val isPressed = softKey.pressed
        val pressDy = if (isPressed) DevicesUtils.dip2px(KEY_PRESS_DY_DP) else 0

        val keyMarginX = if (keyXMargin == 0) (keyRadius / 10).coerceAtLeast(1) else keyXMargin
        val keyMarginY = if (keyYMargin == 0) (keyRadius / 10).coerceAtLeast(1) else keyYMargin
        val topKeyMarginY = if (ImeEnvironment.keyboardModeFloat && ImeEnvironment.isLandscape && softKey.mTop == 0) {
            min(keyMarginY, DevicesUtils.dip2px(4f))
        } else {
            keyMarginY
        }
        val visibleLeft = softKey.mLeft + keyMarginX
        val visibleTop = softKey.mTop + topKeyMarginY
        val visibleRight = softKey.mRight - keyMarginX
        val visibleBottom = softKey.mBottom - keyMarginY
        val visibleWidth = (visibleRight - visibleLeft).coerceAtLeast(1)
        val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(1)
        val visibleCenterX = (visibleLeft + visibleRight) / 2.0f
        val visibleCenterY = (visibleTop + visibleBottom) / 2.0f

        if (isKeyBorder || isPressed) {
            val radius = keyRadius.toFloat()

            if (isKeyBorder && !isPressed) {
                val shadowOffset = DevicesUtils.dip2px(1f).toFloat()
                mShadowPaint.color = if (mActiveTheme.isDark) DARK_KEY_SHADOW_COLOR else LIGHT_KEY_SHADOW_COLOR
                mShadowRect.set(visibleLeft.toFloat(), visibleTop + shadowOffset, visibleRight.toFloat(), visibleBottom + shadowOffset)
                canvas.drawRoundRect(mShadowRect, radius, radius, mShadowPaint)
            }

            val background = if (isKeyBorder) {
                getSoftKeyBackgroundColor(softKey)
            } else {
                mActiveTheme.keyPressHighlightColor
            }
            mKeyBgDrawable.setColor(background)
            mKeyBgDrawable.cornerRadius = radius
            if (isKeyBorder && !isPressed && softKey.code != KeyEvent.KEYCODE_ENTER) {
                val strokeWidth = max(1, DevicesUtils.dip2px(0.5f))
                val strokeColor = if (mActiveTheme.isDark) DARK_KEY_BORDER_COLOR else LIGHT_KEY_BORDER_COLOR
                mKeyBgDrawable.setStroke(strokeWidth, strokeColor)
            } else {
                mKeyBgDrawable.setStroke(0, Color.TRANSPARENT)
            }
            mKeyBgDrawable.setBounds(visibleLeft, visibleTop, visibleRight, visibleBottom)
            mKeyBgDrawable.draw(canvas)
        }
        var keyLabel = when {
            InputModeSwitcher.isChinese && softKey.code == InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD -> "。"
            InputModeSwitcher.isChinese && softKey.code == InputModeSwitcher.USER_KEYCODE_LEFT_COMMA -> "，"
            else -> displayKeyLabel(softKey, keyboardChineseUppercase)
        }
        val keyLabelSmall = when (softKey.code) {
            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
            InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD -> ""
            else -> if (softKey.keyLabelSmall == "……") "…" else softKey.keyLabelSmall
        }
        val keyMnemonic = softKey.keyMnemonic
        var keyIcon = softKey.keyIcon
        val textColor = if (softKey.code == KeyEvent.KEYCODE_ENTER) {
            mActiveTheme.accentKeyTextColor
        } else {
            mActiveTheme.keyTextColor
        }
        val isIdleSyllableDelimiter = softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese && DecodingInfo.isEngineFinish
        if (softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese) {
            keyLabel = "分词"
            keyIcon = null
        }
        if (softKey.code == InputModeSwitcher.USER_KEYCODE_LANG) {
            drawLanguageSwitchKey(canvas, visibleWidth, visibleHeight, visibleCenterX, visibleCenterY, textColor, pressDy)
            return
        }
        if (keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall)) {
            mPaint.color = textColor
            mPaint.typeface = mRegularTypeface
            mPaint.alpha = SMALL_LABEL_ALPHA
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat()
            val x = visibleCenterX - mPaint.measureText(keyLabelSmall) / 2.0f
            val smallFm = mPaint.fontMetrics
            val y = visibleTop + visibleHeight * SMALL_LABEL_Y_RATIO - (smallFm.ascent + smallFm.descent) / 2.0f + pressDy
            canvas.drawText(keyLabelSmall, x, y, mPaint)
            mPaint.alpha = 255
        }
        if (null != keyIcon) {
            val isSpace = softKey.code == KeyEvent.KEYCODE_SPACE
            val maxIconHeight = if (isSpace) visibleHeight * 0.34f else visibleHeight * 0.48f
            val maxIconWidth = if (isSpace) visibleWidth * 0.45f else visibleWidth * 0.48f
            val aspect = if (keyIcon.intrinsicHeight > 0) keyIcon.intrinsicWidth.toFloat() / keyIcon.intrinsicHeight else 1f
            val iconH = min(maxIconHeight, maxIconWidth / aspect)
            val iconW = iconH * aspect
            val iconLeft = (visibleCenterX - iconW / 2f).toInt()
            val iconTop = (visibleCenterY - iconH / 2f + pressDy).toInt()
            keyIcon.setTint(textColor)
            keyIcon.setBounds(
                iconLeft,
                iconTop,
                (iconLeft + iconW).toInt(),
                (iconTop + iconH).toInt()
            )
            keyIcon.draw(canvas)
        } else if (!TextUtils.isEmpty(keyLabel)) { // Centered label
            mPaint.color = textColor
            mPaint.alpha = if (isIdleSyllableDelimiter) IDLE_SYLLABLE_LABEL_ALPHA else 255
            mPaint.typeface = if (keyboardFontBold) mMediumTypeface else mRegularTypeface
            val isBottomPunctuation = softKey.code == InputModeSwitcher.USER_KEYCODE_LEFT_COMMA ||
                softKey.code == InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD
            var targetTextSize = when {
                isBottomPunctuation -> mNormalKeyTextSize * 0.78f
                keyLabel.length >= 2 -> mNormalKeyTextSize * FUNCTION_KEY_TEXT_SCALE
                else -> mNormalKeyTextSize.toFloat()
            }
            mPaint.textSize = targetTextSize
            val maxTextWidth = visibleWidth * 0.82f
            val measuredW = mPaint.measureText(keyLabel)
            if (measuredW > maxTextWidth && measuredW > 0) {
                targetTextSize *= (maxTextWidth / measuredW)
                mPaint.textSize = targetTextSize
            }
            if (isBottomPunctuation) {
                mPaint.getTextBounds(keyLabel, 0, keyLabel.length, mGlyphBounds)
                val x = visibleCenterX - (mGlyphBounds.left + mGlyphBounds.right) / 2.0f
                val y = visibleCenterY - (mGlyphBounds.top + mGlyphBounds.bottom) / 2.0f + pressDy
                canvas.drawText(keyLabel, x, y, mPaint)
            } else {
                val x = visibleCenterX - mPaint.measureText(keyLabel) / 2.0f
                val fontMetrics = mPaint.fontMetrics
                val textCenterY = (fontMetrics.ascent + fontMetrics.descent) / 2.0f
                val y = if (keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall)) {
                    visibleCenterY + visibleHeight * MAIN_LABEL_WITH_SMALL_OFFSET_RATIO - textCenterY + pressDy
                } else {
                    visibleCenterY - textCenterY + pressDy
                }
                canvas.drawText(keyLabel, x, y, mPaint)
            }
            mPaint.alpha = 255
        }
        if (InputModeSwitcher.isChinese && !TextUtils.isEmpty(keyMnemonic)) {  // Sub-mnemonic label (Double Pinyin)
            mPaint.color = textColor
            mPaint.typeface = mRegularTypeface
            var targetMnemonicTextSize = mNormalKeyTextSizeSmall.toFloat() * 0.72f
            mPaint.textSize = targetMnemonicTextSize
            val maxMnemonicWidth = visibleWidth * 0.86f
            val measuredW = mPaint.measureText(keyMnemonic)
            if (measuredW > maxMnemonicWidth && measuredW > 0) {
                targetMnemonicTextSize *= (maxMnemonicWidth / measuredW)
                mPaint.textSize = targetMnemonicTextSize
            }
            val x = visibleCenterX - mPaint.measureText(keyMnemonic) / 2.0f
            val fontMetrics = mPaint.fontMetrics
            val textCenterY = (fontMetrics.ascent + fontMetrics.descent) / 2.0f
            val y = visibleTop + visibleHeight * 0.80f - textCenterY + pressDy
            canvas.drawText(keyMnemonic, x, y, mPaint)
        }
    }

    private fun drawLanguageSwitchKey(
        canvas: Canvas,
        visibleWidth: Int,
        visibleHeight: Int,
        centerX: Float,
        baseCenterY: Float,
        textColor: Int,
        pressDy: Int,
    ) {
        val primaryText = if (InputModeSwitcher.isChinese) "中" else "En"
        val secondaryText = if (InputModeSwitcher.isChinese) "英" else "中"
        val keyW = visibleWidth.toFloat()
        val keyH = visibleHeight.toFloat()
        val centerY = baseCenterY + pressDy

        mPaint.color = textColor
        mPaint.alpha = 255
        mPaint.typeface = mMediumTypeface
        mPaint.textSize = mNormalKeyTextSize * LANG_PRIMARY_TEXT_SCALE
        val primaryFm = mPaint.fontMetrics
        val primaryCx = centerX - keyW * 0.14f
        val primaryCy = centerY - keyH * 0.11f
        val primaryX = primaryCx - mPaint.measureText(primaryText) / 2.0f
        val primaryY = primaryCy - (primaryFm.ascent + primaryFm.descent) / 2.0f
        canvas.drawText(primaryText, primaryX, primaryY, mPaint)

        mPaint.typeface = mRegularTypeface
        mPaint.textSize = mNormalKeyTextSize * LANG_SECONDARY_TEXT_SCALE
        mPaint.alpha = LANG_SECONDARY_ALPHA
        val secondaryFm = mPaint.fontMetrics
        val secondaryCx = centerX + keyW * 0.15f
        val secondaryCy = centerY + keyH * 0.14f
        val secondaryX = secondaryCx - mPaint.measureText(secondaryText) / 2.0f
        val secondaryY = secondaryCy - (secondaryFm.ascent + secondaryFm.descent) / 2.0f
        canvas.drawText(secondaryText, secondaryX, secondaryY, mPaint)

        mBracketPaint.color = textColor
        mBracketPaint.alpha = 125
        mBracketPaint.strokeWidth = max(1f, DevicesUtils.dip2px(1.1f).toFloat())
        val topCornerX = centerX + keyW * 0.15f
        val topCornerY = centerY - keyH * 0.15f
        val armLen = min(keyW, keyH) * 0.11f
        canvas.drawLine(topCornerX - armLen, topCornerY, topCornerX, topCornerY, mBracketPaint)
        canvas.drawLine(topCornerX, topCornerY, topCornerX, topCornerY + armLen, mBracketPaint)

        val bottomCornerX = centerX - keyW * 0.15f
        val bottomCornerY = centerY + keyH * 0.16f
        canvas.drawLine(bottomCornerX, bottomCornerY - armLen, bottomCornerX, bottomCornerY, mBracketPaint)
        canvas.drawLine(bottomCornerX, bottomCornerY, bottomCornerX + armLen, bottomCornerY, mBracketPaint)
        mPaint.alpha = 255
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
