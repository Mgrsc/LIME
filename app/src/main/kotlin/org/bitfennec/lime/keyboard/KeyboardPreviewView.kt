package org.bitfennec.lime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.core.graphics.withSave
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.bitfennec.lime.view.CandidatesBar

import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.view.popup.PopupEntryUi
import org.bitfennec.lime.utils.dp

class KeyboardPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var qwerTextContainer: TextKeyboard? = null
    private var candidatesBar: CandidatesBar? = null
    private var mSkbRoot: View? = null
    private var previewTheme: Theme = ThemeManager.activeTheme
    private var previewBalloon: PopupEntryUi? = null
    private var previewBalloonKey: SoftKey? = null
    private val balloonDismissRunnable = Runnable {
        dismissBalloonPreview()
    }

    private fun initView() {
        dismissBalloonPreview()
        removeAllViews()
        val root = LayoutInflater.from(context).inflate(R.layout.skb_preview, this, false)
        mSkbRoot = root
        candidatesBar = root.findViewById(R.id.candidates_bar)
        val previewUi = root.findViewById<RelativeLayout>(R.id.skb_input_keyboard_view)
        qwerTextContainer = TextKeyboard(context)
        val softKeyboard = KeyboardLoaderUtil.instance.getSoftKeyboard(
            AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT
        )
        qwerTextContainer!!.setSoftKeyboard(softKeyboard)
        previewUi.addView(qwerTextContainer, RelativeLayout.LayoutParams(ImeEnvironment.skbWidth, ImeEnvironment.skbHeight))
        addView(root)
        candidatesBar?.showPreviewCandidates(theme = previewTheme)
    }

    fun setTheme(theme: Theme, background: Drawable) {
        previewTheme = theme
        initView()
        qwerTextContainer?.setTheme(theme)
        candidatesBar?.updateTheme(theme.keyTextColor)
        candidatesBar?.showPreviewCandidates(theme = previewTheme)
        setBackground(background)
    }

    fun setTheme(theme: Theme) {
        previewTheme = theme
        initView()
        qwerTextContainer?.setTheme(theme)
        candidatesBar?.updateTheme(theme.keyTextColor)
        candidatesBar?.showPreviewCandidates(theme = previewTheme)
        background = theme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
    }

    fun updateKeyRadiusPreview(radius: Int) {
        qwerTextContainer?.setKeyRadiusPreview(radius)
    }

    fun updateKeyMarginXPreview(progress: Int) {
        val marginPx = (progress / 1000f * ImeEnvironment.skbWidth).toInt()
        qwerTextContainer?.setKeyMarginPreview(marginX = marginPx)
    }

    fun updateKeyMarginYPreview(progress: Int) {
        val marginPx = (progress / 1000f * ImeEnvironment.skbHeight).toInt()
        qwerTextContainer?.setKeyMarginPreview(marginY = marginPx)
    }

    fun updateKeyBorderPreview(border: Boolean) {
        qwerTextContainer?.setKeyBorderPreview(border)
    }

    fun updateFontBoldPreview(bold: Boolean) {
        qwerTextContainer?.setFontBoldPreview(bold)
    }

    fun updateChineseUppercasePreview(uppercase: Boolean) {
        qwerTextContainer?.setChineseUppercasePreview(uppercase)
    }

    fun updateSymbolPreview(show: Boolean) {
        qwerTextContainer?.setSymbolPreview(show)
    }

    fun updateNumberLinePreview() {
        initView()
        setTheme(previewTheme)
    }

    fun updateBalloonPreview(show: Boolean) {
        removeCallbacks(balloonDismissRunnable)
        if (!show) {
            dismissBalloonPreview()
            return
        }
        val qwer = qwerTextContainer ?: return
        val key = qwer.getFlattenedKeys().firstOrNull { it.label.equals("G", ignoreCase = true) }
            ?: qwer.getFlattenedKeys().firstOrNull { it.label.isNotEmpty() }
            ?: return

        dismissBalloonPreview()
        previewBalloonKey = key
        key.onPressed()
        qwer.invalidate()

        val balloon = PopupEntryUi(context).apply {
            setBackground(previewTheme, ThemeManager.prefs.keyRadius.getValue().toFloat())
            setText(key.label.uppercase())
        }
        previewBalloon = balloon

        val previewUi = mSkbRoot?.findViewById<RelativeLayout>(R.id.skb_input_keyboard_view) ?: this
        val keyBounds = android.graphics.Rect(key.mLeft, key.mTop, key.mRight, key.mBottom)
        val balloonWidth = (keyBounds.width() * 1.2f).toInt()
        val balloonHeight = (keyBounds.height() * 1.2f).toInt()
        val lp = RelativeLayout.LayoutParams(balloonWidth, balloonHeight).apply {
            leftMargin = keyBounds.centerX() - balloonWidth / 2
            topMargin = keyBounds.top - balloonHeight - dp(6f)
        }
        previewUi.addView(balloon.root, lp)

        postDelayed(balloonDismissRunnable, 1500L)
    }

    private fun dismissBalloonPreview() {
        previewBalloonKey?.let {
            it.onReleased()
            qwerTextContainer?.invalidate()
            previewBalloonKey = null
        }
        previewBalloon?.let {
            val previewUi = mSkbRoot?.findViewById<RelativeLayout>(R.id.skb_input_keyboard_view) ?: this
            previewUi.removeView(it.root)
            previewBalloon = null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(balloonDismissRunnable)
        dismissBalloonPreview()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val skbW = if (ImeEnvironment.skbWidth > 0) ImeEnvironment.skbWidth else availableWidth
        val skbH = if (ImeEnvironment.skbHeight > 0) ImeEnvironment.skbHeight + ImeEnvironment.heightForCandidatesArea else 800

        mSkbRoot?.measure(
            MeasureSpec.makeMeasureSpec(skbW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(skbH, MeasureSpec.EXACTLY)
        )

        if (availableWidth > 0 && skbW > 0) {
            val scale = availableWidth.toFloat() / skbW.toFloat()
            val targetHeight = (skbH * scale).toInt()
            setMeasuredDimension(availableWidth, targetHeight)
        } else {
            setMeasuredDimension(skbW, skbH)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val skbW = if (ImeEnvironment.skbWidth > 0) ImeEnvironment.skbWidth else (right - left)
        val skbH = if (ImeEnvironment.skbHeight > 0) ImeEnvironment.skbHeight + ImeEnvironment.heightForCandidatesArea else (bottom - top)
        mSkbRoot?.layout(0, 0, skbW, skbH)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val skbW = if (ImeEnvironment.skbWidth > 0) ImeEnvironment.skbWidth else width
        if (skbW > 0 && width > 0 && width != skbW) {
            val scale = width.toFloat() / skbW.toFloat()
            canvas.withSave {
                canvas.scale(scale, scale)
                super.dispatchDraw(canvas)
            }
        } else {
            super.dispatchDraw(canvas)
        }
    }

    // Preview keyboard is read-only; consumes all touch events
    // to prevent touch interactions from invoking input actions.
    // performClick would emit TYPE_VIEW_CLICKED on a non-clickable preview.
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = true

    init {
        initView()
    }
}
