package org.bitfennec.lime.view.widget

import android.animation.ValueAnimator
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import org.bitfennec.lime.R
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.utils.resolveThemeAttribute
import org.bitfennec.lime.utils.textAppearance
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("FunctionName")
fun Context.ProgressBarDialogIndeterminate(@StringRes title: Int): AlertDialog.Builder {
    val container = FrameLayout(this).apply {
        val padH = dp(26)
        val padV = dp(20)
        setPadding(padH, padV, padH, padV)
        val shouldAnimate = ValueAnimator.areAnimatorsEnabled()
        val child = if (shouldAnimate) {
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            }
        } else {
            TextView(context).apply {
                setText(R.string.please_wait)
                textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
            }
        }
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return AlertDialog.Builder(this)
        .setTitle(title)
        .setView(container)
        .setCancelable(false)
}

fun LifecycleCoroutineScope.withLoadingDialog(context: Context, @StringRes title: Int = R.string.loading, threshold: Long = 200L, action: suspend () -> Unit) {
    var loadingDialog: AlertDialog? = null
    val loadingJob = launch {
        delay(threshold)
        loadingDialog = context.ProgressBarDialogIndeterminate(title).show()
    }
    launch {
        action()
        loadingJob.cancelAndJoin()
        loadingDialog?.dismiss()
    }
}