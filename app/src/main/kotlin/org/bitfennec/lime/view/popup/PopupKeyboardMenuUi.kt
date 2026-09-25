package org.bitfennec.lime.view.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.prefs.behavior.PopupMenuMode
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

class PopupKeyboardMenuUi(
    bounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    private val keyWidth: Int,
    private var isSelect: Boolean,
    private var popupMenuPair: Pair<PopupMenuMode, String>
) : PopupContainerUi(Launcher.instance.context, bounds, onDismissSelf) {

    class PopupKeyUi(val ctx: Context, val text: String) {
        val textView = AutoScaleTextView(ctx).apply {
            text = this@PopupKeyUi.text
            scaleMode = AutoScaleTextView.Mode.Proportional
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ImeEnvironment.keyTextSize * 1.2f)
            setTextColor(ThemeManager.activeTheme.keyTextColor)
        }

        val root = FrameLayout(ctx).apply {
            addView(textView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private val inactiveBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(ThemeManager.activeTheme.popupBackgroundColor)
    }

    private val focusBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(ThemeManager.activeTheme.accentKeyBackgroundColor)
    }

    private val focusBackgroundClear = GradientDrawable().apply {
        cornerRadius = radius
        setColor(ctx.getColor(R.color.red_400))
    }

    private val rowCount: Int
    private val columnCount: Int

    private var lastX: Float
    private var lastY: Float

    private val focusRow: Int
    private val focusColumn: Int

    private var keys: Array<String> = arrayOf(popupMenuPair.second)

    init {
        val keyCount: Float = keys.size.toFloat()
        rowCount = ceil(keyCount / 5).toInt()
        columnCount = (keyCount / rowCount).roundToInt()
        focusRow = 0
        focusColumn = calcInitialFocusedColumn(columnCount, keyWidth, bounds)
        lastX = 0f
        lastY = 0f
    }

    override val offsetY = 0 - bounds.height() * (rowCount - 1)

    private val columnOrder = createColumnOrder(columnCount, focusColumn)

    private val keyOrders = Array(rowCount) { row ->
        IntArray(columnCount) { col -> row * columnCount + columnOrder[col] }
    }

    private var focusedIndex = keyOrders[focusRow][focusColumn]

    private var keyUis = keys.map {
        PopupKeyUi(ctx, it)
    }

    init {
        if (isSelect) markFocus(focusedIndex)
    }

    override val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = inactiveBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f).toFloat()

        for (i in rowCount - 1 downTo 0) {
            val order = keyOrders[i]
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                for (j in 0 until columnCount) {
                    val keyUi = keyUis.getOrNull(order[j])
                    if (keyUi == null) {
                        gravity = if (j == 0) Gravity.END else Gravity.START
                    } else {
                        addView(keyUi.root, LinearLayout.LayoutParams(keyWidth, bounds.height()))
                    }
                }
            }
            addView(rowLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun markFocus(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = if (popupMenuPair.first == PopupMenuMode.Clear) focusBackgroundClear else focusBackground
            textView.setTextColor(ThemeManager.activeTheme.keyTextColor)
        }
    }

    private fun markInactive() {
        keyUis.getOrNull(0)?.apply {
            root.background = null
            textView.setTextColor(ThemeManager.activeTheme.keyTextColor)
        }
    }

    override fun onGestureEvent(distance: Float) {
        isSelect = distance > 5
        if (isSelect) markFocus(0) else markInactive()
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        return false
    }

    override fun onTrigger(): Pair<PopupMenuMode, String> {
        return if (isSelect) popupMenuPair else Pair(PopupMenuMode.None, "")
    }
}
