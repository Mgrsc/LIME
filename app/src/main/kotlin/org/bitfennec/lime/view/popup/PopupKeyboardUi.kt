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
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.prefs.behavior.PopupMenuMode
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.StringUtils
import org.bitfennec.lime.utils.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * @param theme [Theme]
 * @param bounds bound [Rect] of popup trigger view. Used to calculate free space of both sides and
 * determine column order. See [focusColumn].
 * @param onDismissSelf callback when popup keyboard wants to close
 * @param radius popup keyboard and key radius
 * @param keyWidth key width in popup keyboard
 * trigger view to popup keyboard view. See [offsetX] and [offsetY].
 * @param keys character to commit when triggered
 */
class PopupKeyboardUi(
    bounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    private val keyWidth: Int,
    private val keys: Array<String>
) : PopupContainerUi(Launcher.instance.context, bounds, onDismissSelf) {

    class PopupKeyUi(val ctx: Context, val theme: Theme, val text: String) {

        val textView = AutoScaleTextView(ctx).apply {
            text = this@PopupKeyUi.text
            scaleMode = AutoScaleTextView.Mode.Proportional
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ImeEnvironment.keyTextSize * 1.2f)
            setTextColor(theme.keyTextColor)
        }

        val textViewSdb = AutoScaleTextView(ctx).apply {
            text = ctx.getString(R.string.symbol_half_width_badge)
            val p = ctx.dp(3)
            setPadding(p, p, p, p)
            scaleMode = AutoScaleTextView.Mode.Proportional
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ImeEnvironment.keyTextSmallSize * 0.8f)
            setTextColor(theme.keyTextColor)
        }

        val root = FrameLayout(ctx).apply {
            addView(textView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            if (StringUtils.isDBCSymbol(this@PopupKeyUi.text)) {
                addView(textViewSdb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            }
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

    private val rowCount: Int
    private val columnCount: Int

    private var lastX: Float
    private var lastY: Float

    private val focusRow: Int
    private val focusColumn: Int

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

    private val keyUis = keys.map {
        PopupKeyUi(ctx, ThemeManager.activeTheme, it)
    }

    init {
        markFocus(focusedIndex)
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
            root.background = focusBackground
            textView.setTextColor(theme.keyTextColor)
            textViewSdb.setTextColor(theme.keyTextColor)
        }
    }

    private fun markInactive(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = null
            textView.setTextColor(theme.keyTextColor)
            textViewSdb.setTextColor(theme.keyTextColor)
        }
    }

    override fun onGestureEvent(distance: Float) {}

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        if (lastX == 0f) {
            lastX = x
            lastY = y
            return false
        }
        var newRow = focusRow - ((y - lastY) / bounds.height() - 0.2).roundToInt()
        var newColumn = focusColumn + floor((x - lastX) / keyWidth).toInt()
        if (newRow < -2 || newRow > rowCount + 1 || newColumn < -2 || newColumn > columnCount + 1) {
            onDismissSelf(this)
            return true
        }
        newRow = limitIndex(newRow, rowCount)
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = keyOrders[newRow][newColumn]
        if (newFocus < keyUis.size) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    override fun onTrigger(): Pair<PopupMenuMode, String> {
        return Pair(PopupMenuMode.Text, keys[focusedIndex])
    }
}
