package org.bitfennec.lime.environment

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.WindowManager
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.theme.ThemeManager.prefs
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.utils.DevicesUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Global keyboard metrics and environment configuration.
 */
object ImeEnvironment {
    var systemNavbarWindowsBottom = 0
    var screenWidth = 0
    var screenHeight = 0
    var inputAreaHeight = 0
    var inputAreaWidth = 0
    var skbWidth = 0
        private set
    var skbHeight = 0
        private set
    var holderWidth = 0
        private set
    var heightForCandidatesArea = 0
    var candidateRowHeight = 0
    var heightForcomposingHeader = 0
    var heightForcomposing = 0
    var hardwareCandidatesRowHeight = 0
    var hardwareCandidatesAreaHeight = 0
    var heightForKeyboardMove = 0
    var keyTextSize = 0
    var keyTextSmallSize = 0
    var composingTextSize = 0f
    var headerComposingTextSize = 13f
    var candidateTextSizeSp = 20f
    var isLandscape = false
    var keyXMargin = 0
    var keyYMargin = 0
    private var keyboardHeightRatio = 0f

    init {
        initData()
    }

    fun initData(context: Context = Launcher.instance.context) {
        val dm = context.resources.displayMetrics
        var measuredScreenWidth = dm.widthPixels
        var measuredScreenHeight = dm.heightPixels

        val wm = when (context) {
            is Activity -> context.windowManager
            is android.inputmethodservice.InputMethodService -> context.window.window?.windowManager ?: context.getSystemService(WindowManager::class.java)
            else -> context.getSystemService(WindowManager::class.java)
        }
        val bounds = wm?.currentWindowMetrics?.bounds ?: wm?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            measuredScreenWidth = bounds.width()
            measuredScreenHeight = bounds.height()
        }
        screenWidth = measuredScreenWidth
        screenHeight = measuredScreenHeight
        val orientation = context.resources.configuration.orientation
        isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE || (orientation == Configuration.ORIENTATION_UNDEFINED && screenHeight <= screenWidth)
        val internalPrefs = AppPrefs.getInstance().internal
        var screenWidthVertical = screenWidth
        var screenHeightVertical = screenHeight

        keyboardHeightRatio = if (isLandscape) {
            internalPrefs.keyboardHeightRatioLandscape.getValue()
        } else {
            internalPrefs.keyboardHeightRatio.getValue()
        }

        if (keyboardModeFloat) {
            val baseShortSide = min(screenWidth, screenHeight)
            if (isLandscape) {
                screenWidthVertical = (baseShortSide * 0.85f).toInt().coerceIn(DevicesUtils.dip2px(350), DevicesUtils.dip2px(430))
                screenHeightVertical = baseShortSide
                val baseLandscapeFloatHeight = DevicesUtils.dip2px(142)
                val effectiveRatio = keyboardHeightRatio.coerceIn(0.22f, 0.38f)
                skbHeight = (baseLandscapeFloatHeight * (effectiveRatio / 0.34f)).toInt()
            } else {
                screenWidthVertical = (baseShortSide * 3f / 4).toInt()
                screenHeightVertical = (max(screenWidth, screenHeight) * 3f / 4).toInt()
                skbHeight = (screenHeightVertical * keyboardHeightRatio).toInt()
            }
        } else {
            skbHeight = (screenHeightVertical * keyboardHeightRatio).toInt()
        }
        val oneHandedMod = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        // Keyboard placeholder width (for one-handed mode), relative to portrait width / landscape height.
        holderWidth = if (oneHandedMod && !keyboardModeFloat) (screenWidthVertical * 0.2f).toInt() else 0
        skbWidth = screenWidthVertical - holderWidth
        inputAreaWidth = skbWidth
        val keyboardFontSizeRatio = prefs.keyboardFontSize.getValue() / 100f
        composingTextSize = 13f * keyboardFontSizeRatio
        // Preserve the stored slider value: default 55 maps to 20sp, range 25..100 to 18..23sp.
        candidateTextSizeSp = 20f + (prefs.candidateTextSize.getValue().coerceIn(25, 100) - 55) / 15f
        headerComposingTextSize = 13f + (candidateTextSizeSp - 20f) / 3f

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                composingTextSize,
                context.resources.displayMetrics
            )
            typeface = Typeface.DEFAULT
        }
        val fm = paint.fontMetricsInt
        val glyphBox = fm.descent - fm.ascent
        val isFloating = keyboardModeFloat
        val isLandscapeFloat = isFloating && isLandscape
        heightForcomposing = max(
            DevicesUtils.dip2px(if (isLandscapeFloat) 16f else if (isFloating) 20f else 18f),
            glyphBox + DevicesUtils.dip2px(if (isLandscapeFloat) 1f else 3f)
        )

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                headerComposingTextSize,
                context.resources.displayMetrics
            )
            typeface = Typeface.DEFAULT
        }
        val headerFm = headerPaint.fontMetricsInt
        // Reserve fallback-font and cursor metrics before composing, keeping Insets stable.
        val headerSample = "Agjypq想着▎"
        val headerLayout = StaticLayout.Builder.obtain(headerSample, 0, headerSample.length, headerPaint, Int.MAX_VALUE)
            .setIncludePad(true)
            .setUseLineSpacingFromFallbacks(true)
            .build()
        val headerGlyphBox = max(headerFm.bottom - headerFm.top, headerLayout.height)
        heightForcomposingHeader = max(
            DevicesUtils.dip2px(18f),
            headerGlyphBox + 2 * DevicesUtils.dip2px(1f)
        )

        val candPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                candidateTextSizeSp,
                context.resources.displayMetrics
            )
            typeface = Typeface.DEFAULT
        }
        val candFm = candPaint.fontMetricsInt
        val candGlyphBox = candFm.bottom - candFm.top
        candidateRowHeight = max(
            DevicesUtils.dip2px(36f),
            candGlyphBox + DevicesUtils.dip2px(4f)
        )

        heightForCandidatesArea = candidateRowHeight + heightForcomposingHeader
        hardwareCandidatesRowHeight = DevicesUtils.dip2px(40f)
        val hardwareMinimumArea = when {
            isLandscapeFloat -> DevicesUtils.dip2px(56f)
            isFloating -> DevicesUtils.dip2px(64f)
            else -> DevicesUtils.dip2px(58f)
        }
        hardwareCandidatesAreaHeight = max(hardwareMinimumArea, heightForcomposing + hardwareCandidatesRowHeight)
        val keyboardMoveMinHeight = if (isLandscapeFloat) DevicesUtils.dip2px(18) else DevicesUtils.dip2px(16)
        heightForKeyboardMove = (heightForCandidatesArea * 0.25f).toInt().coerceAtLeast(keyboardMoveMinHeight)

        keyTextSize = (skbHeight * 0.092f * keyboardFontSizeRatio).toInt()
        keyTextSmallSize = (skbHeight * 0.055f * keyboardFontSizeRatio).toInt()
        keyXMargin = (prefs.keyXMargin.getValue() / 1000f * skbWidth).toInt()
        keyYMargin = (prefs.keyYMargin.getValue() / 1000f * skbHeight).toInt()
        inputAreaHeight = skbHeight + heightForCandidatesArea
    }

    var keyBoardHeightRatio: Float
        get() = keyboardHeightRatio
        set(value) {
            keyboardHeightRatio = value
            if (isLandscape) AppPrefs.getInstance().internal.keyboardHeightRatioLandscape.setValue(value)
            else AppPrefs.getInstance().internal.keyboardHeightRatio.setValue(value)
        }

    var keyboardModeFloat: Boolean
        get() = if (isLandscape) {
            AppPrefs.getInstance().internal.keyboardModeFloatLandscape.getValue()
        } else {
            AppPrefs.getInstance().internal.keyboardModeFloat.getValue()
        }
        set(isFloatMode) {
            val internalPrefs = AppPrefs.getInstance().internal
            if (isLandscape) internalPrefs.keyboardModeFloatLandscape.setValue(isFloatMode)
            else internalPrefs.keyboardModeFloat.setValue(isFloatMode)
        }

    val skbAreaHeight: Int
        get() = inputAreaHeight + if (!keyboardModeFloat) {
            systemNavbarWindowsBottom
        } else heightForKeyboardMove

    val leftMarginWidth: Int
        get() = if (!keyboardModeFloat) (inputAreaWidth - skbWidth) / 2 else 0
}
