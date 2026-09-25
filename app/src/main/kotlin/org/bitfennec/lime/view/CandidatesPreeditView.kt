package org.bitfennec.lime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.inputmethod.EngineAction
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.inputmethod.RimeEngine
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.service.DecodingInfo
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.PreeditCursorUtils
import org.bitfennec.lime.utils.dp

/**
 * Preedit composition text container above candidate list.
 */
class CandidatesPreeditView(private val context: Context) {

    val chip: FrameLayout = FrameLayout(context).apply {
        isClickable = true
        visibility = View.GONE
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var isDragging = false
    private var lastDisplayCursorPos = 0
    private var lastDispatchedRawCursor: Int? = null
    private var composingScrollGeneration = 0L

    @SuppressLint("ClickableViewAccessibility")
    val composingView: TextView = AppCompatTextView(context).apply {
        includeFontPadding = true
        typeface = Typeface.DEFAULT
        isFallbackLineSpacing = true
        isSingleLine = true
        maxLines = 1
        setHorizontallyScrolling(true)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(context.dp(10), context.dp(1), context.dp(10), context.dp(1))
        isClickable = true
        isFocusable = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeEnvironment.headerComposingTextSize)
        setOnTouchListener { view, event ->
            val textView = view as TextView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.x
                    isDragging = false
                    updateCursorFromTouch(textView, event.x)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging && abs(event.x - downX) >= touchSlop) {
                        isDragging = true
                    }
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    updateCursorFromTouch(textView, event.x)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    lastDispatchedRawCursor = null
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lastDispatchedRawCursor = null
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    init {
        chip.addView(
            composingView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun updateTheme(theme: Theme) {
        composingView.setTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 230))
    }

    fun updateTextSizeAndHeight(useHandwritingLayout: Boolean = false) {
        composingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeEnvironment.headerComposingTextSize)
        val preeditHeight = if (useHandwritingLayout) 0 else ImeEnvironment.heightForcomposingHeader
        val lp = chip.layoutParams
        if (lp != null && lp.height != preeditHeight) {
            lp.height = preeditHeight
            chip.layoutParams = lp
        }
    }

    fun render(composing: String) {
        val scrollGeneration = ++composingScrollGeneration
        if (composing.isEmpty()) {
            composingView.text = ""
            composingView.scrollTo(0, 0)
            lastDisplayCursorPos = 0
            return
        }
        val state = EnginePipeline.stateFlow.value
        val rawPreedit = RimeEngine.rawPreedit
        val displayCursorPos = if (rawPreedit.isEmpty()) {
            composing.length
        } else {
            PreeditCursorUtils.rawCursorPosToDisplayCursorPos(
                rawPreedit = rawPreedit,
                rawCursorPos = state.compositionCursorPos,
                showComposition = composing
            )
        }.coerceIn(0, composing.length)
        lastDisplayCursorPos = displayCursorPos

        val text = SpannableStringBuilder().apply {
            append(composing.substring(0, displayCursorPos))
            val cursorStart = length
            append("▎")
            setSpan(
                ForegroundColorSpan(activeTheme.accentKeyBackgroundColor),
                cursorStart,
                cursorStart + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append(composing.substring(displayCursorPos))
        }
        composing.forEachIndexed { index, char ->
            if (char == '\'') {
                val spanStart = if (index < displayCursorPos) index else index + 1
                text.setSpan(
                    ForegroundColorSpan(ColorUtils.setAlphaComponent(activeTheme.keyTextColor, 120)),
                    spanStart,
                    spanStart + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val isT9 = state.currentSchema == CustomConstant.SCHEMA_ZH_T9 || InputModeSwitcher.isChineseT9
        if (isT9 && state.t9LockedCount > 0) {
            val segments = mutableListOf<Pair<Int, Int>>()
            var segStart = 0
            composing.forEachIndexed { index, char ->
                if (char == '\'') {
                    if (index > segStart) {
                        segments.add(segStart to index)
                    }
                    segStart = index + 1
                }
            }
            if (segStart < composing.length) {
                segments.add(segStart to composing.length)
            }

            val effectiveLockedCount = minOf(state.t9LockedCount, segments.size)
            for (k in 0 until effectiveLockedCount) {
                val (start, end) = segments[k]
                fun applyLockedSpan(spanStart: Int, spanEnd: Int) {
                    if (spanStart >= spanEnd) return
                    text.setSpan(UnderlineSpan(), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(StyleSpan(Typeface.BOLD), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(
                        ForegroundColorSpan(activeTheme.accentKeyBackgroundColor),
                        spanStart,
                        spanEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (end <= displayCursorPos) {
                    applyLockedSpan(start, end)
                } else if (start >= displayCursorPos) {
                    applyLockedSpan(start + 1, end + 1)
                } else {
                    applyLockedSpan(start, displayCursorPos)
                    applyLockedSpan(displayCursorPos + 1, end + 1)
                }
            }
        }

        composingView.text = text
        keepComposingCursorVisible(displayCursorPos, scrollGeneration)
    }

    fun clearSynchronous() {
        composingScrollGeneration++
        composingView.text = ""
        composingView.scrollTo(0, 0)
        updateVisibility(isComposing = false, usesHandwritingLayout = false)
        lastDisplayCursorPos = 0
    }

    fun updateVisibility(isComposing: Boolean, usesHandwritingLayout: Boolean) {
        val targetVisibility = if (usesHandwritingLayout || !isComposing) View.GONE else View.VISIBLE
        if (chip.visibility != targetVisibility) {
            chip.visibility = targetVisibility
        }
    }

    private fun keepComposingCursorVisible(displayCursorPos: Int, scrollGeneration: Long) {
        composingView.post {
            if (scrollGeneration != composingScrollGeneration || chip.visibility != View.VISIBLE) return@post
            val layout = composingView.layout ?: return@post
            val availableWidth = composingView.width - composingView.totalPaddingLeft - composingView.totalPaddingRight
            if (availableWidth <= 0) return@post
            val cursorX = try {
                layout.getPrimaryHorizontal(displayCursorPos.coerceIn(0, layout.text.length)).toInt()
            } catch (_: IndexOutOfBoundsException) {
                return@post
            }
            val currentLeft = composingView.scrollX
            val currentRight = currentLeft + availableWidth
            val padding = context.dp(8)
            val targetLeft = when {
                cursorX < currentLeft + padding -> (cursorX - padding).coerceAtLeast(0)
                cursorX > currentRight - padding -> cursorX - availableWidth + padding
                else -> return@post
            }
            composingView.scrollTo(targetLeft.coerceAtLeast(0), 0)
        }
    }

    private fun updateCursorFromTouch(textView: TextView, touchX: Float) {
        val layout = textView.layout ?: return
        val rawTarget = PreeditCursorUtils.findNearestRawIndex(
            layout = layout,
            touchX = touchX - textView.totalPaddingLeft + textView.scrollX,
            displayCursorPos = lastDisplayCursorPos,
            showComposition = DecodingInfo.composingStrForDisplay,
            rawPreedit = RimeEngine.rawPreedit
        )
        val currentCursorPos = EnginePipeline.stateFlow.value.compositionCursorPos
        if (rawTarget != currentCursorPos && rawTarget != lastDispatchedRawCursor) {
            lastDispatchedRawCursor = rawTarget
            EnginePipeline.send(EngineAction.MoveCompositionCursor(rawTarget))
            DevicesUtils.tryVibrate(textView)
        }
    }
}
