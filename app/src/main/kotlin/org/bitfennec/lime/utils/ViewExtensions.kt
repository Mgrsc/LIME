package org.bitfennec.lime.utils

import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

inline infix fun Int.hasFlag(flag: Int): Boolean = (this and flag) == flag

inline fun dp(dpValue: Int): Int = DevicesUtils.dip2px(dpValue)
inline fun dp(dpValue: Float): Int = DevicesUtils.dip2px(dpValue)

inline fun Context.dp(dpValue: Int): Int = (dpValue * resources.displayMetrics.density + 0.5f).toInt()
inline fun Context.dp(dpValue: Float): Int = (dpValue * resources.displayMetrics.density + 0.5f).toInt()

inline fun View.dp(dpValue: Int): Int = context.dp(dpValue)
inline fun View.dp(dpValue: Float): Int = context.dp(dpValue)

@ColorInt
fun Context.styledColor(@AttrRes attr: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attr, typedValue, true)
    return typedValue.data
}

fun Context.resolveThemeAttribute(@AttrRes attr: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attr, typedValue, true)
    return typedValue.resourceId.takeIf { it != 0 } ?: typedValue.data
}

fun Context.drawable(@DrawableRes id: Int): Drawable? = ContextCompat.getDrawable(this, id)

var View.leftPadding: Int
    get() = paddingLeft
    set(value) = setPadding(value, paddingTop, paddingRight, paddingBottom)

var View.topPadding: Int
    get() = paddingTop
    set(value) = setPadding(paddingLeft, value, paddingRight, paddingBottom)

var View.rightPadding: Int
    get() = paddingRight
    set(value) = setPadding(paddingLeft, paddingTop, value, paddingBottom)

var View.bottomPadding: Int
    get() = paddingBottom
    set(value) = setPadding(paddingLeft, paddingTop, paddingRight, value)

var View.backgroundColor: Int
    get() = 0
    set(value) = setBackgroundColor(value)

var TextView.textAppearance: Int
    get() = 0
    set(@StyleRes resId) {
        setTextAppearance(resId)
    }


@android.annotation.SuppressLint("RepeatOnLifecycleWrongUsage")
inline fun <T> LifecycleOwner.collectWhenStarted(
    flow: Flow<T>,
    crossinline action: suspend (T) -> Unit
): Job = lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        flow.collect { action(it) }
    }
}

@Suppress("FunctionName")
fun DarkenColorFilter(percent: Int): ColorFilter {
    val value = percent * 255 / 100
    return PorterDuffColorFilter(Color.argb(value, 0, 0, 0), PorterDuff.Mode.SRC_OVER)
}
