package org.bitfennec.lime.view

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitfennec.lime.R
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.core.Rime
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.inputmethod.predict.CandidateStripMode
import org.bitfennec.lime.service.DecodingInfo
import org.bitfennec.lime.utils.dp

internal object PredictionDeletePopup {

    suspend fun resolveCandidateDeleteTextRes(candidate: CandidateListItem): Int? =
        EnginePipeline.readState { state ->
            val target = candidate.identity
            if (target == null || !state.acceptsCandidate(target)) return@readState null
            when {
                state.stripMode == CandidateStripMode.PREDICT && DecodingInfo.canRemoveUserPrediction(candidate) ->
                    R.string.delete_prediction
                state.stripMode == CandidateStripMode.DECODING -> when (DecodingInfo.getCandidateDeletableType(candidate)) {
                    Rime.DELETABLE_CUSTOM_WORD -> R.string.delete_candidate
                    Rime.DELETABLE_USER_TUNED -> R.string.delete_candidate_freq
                    else -> null
                }
                else -> null
            }
        }

    fun showIfDeletable(
        anchor: View,
        candidate: CandidateListItem,
        activePopupRef: () -> PopupWindow?,
        onPopupCreated: (PopupWindow?) -> Unit,
        onDelete: () -> Unit,
        isRequestValid: () -> Boolean = { true },
    ) {
        val target = candidate.identity ?: return
        CoroutineScope(Dispatchers.Main.immediate).launch {
            val textRes = resolveCandidateDeleteTextRes(candidate) ?: return@launch
            if (!anchor.isAttachedToWindow || !anchor.isShown || anchor.windowVisibility != View.VISIBLE) return@launch
            if (!isRequestValid() || !EnginePipeline.stateFlow.value.acceptsCandidate(target)) return@launch
            activePopupRef()?.dismiss()
            val popup = show(anchor, textRes) {
                if (EnginePipeline.stateFlow.value.acceptsCandidate(target)) onDelete()
            }
            popup.setOnDismissListener {
                if (activePopupRef() === popup) onPopupCreated(null)
            }
            if (anchor.isAttachedToWindow && anchor.isShown && anchor.windowVisibility == View.VISIBLE && isRequestValid()) {
                onPopupCreated(popup)
            } else {
                popup.dismiss()
            }
        }
    }

    fun show(
        anchor: View,
        @androidx.annotation.StringRes textRes: Int,
        onDelete: () -> Unit,
    ): PopupWindow {
        val context = anchor.context
        val theme = ThemeManager.activeTheme
        val destructiveColor = 0xffdf5a5a.toInt()
        val popupBackground = GradientDrawable().apply {
            setColor(theme.popupBackgroundColor)
            cornerRadius = context.dp(18).toFloat()
            setStroke(context.dp(1), ColorUtils.setAlphaComponent(destructiveColor, 150))
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = context.dp(18).toFloat()
        }
        val content = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(context.dp(14), 0, context.dp(16), 0)
            minimumHeight = context.dp(40)
            background = RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor),
                popupBackground,
                mask,
            )
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_menu_delete)
            drawable?.setTint(destructiveColor)
            contentDescription = null
        }
        val label = TextView(context).apply {
            text = context.getString(textRes)
            setTextColor(destructiveColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(icon, LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply {
            marginEnd = context.dp(7)
        })
        content.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        lateinit var popup: PopupWindow
        content.setOnClickListener {
            popup.dismiss()
            onDelete()
        }
        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            context.dp(40),
            false,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            elevation = context.dp(8).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val anchorX = location[0]
        val screenWidth = context.resources.displayMetrics.widthPixels
        val desiredX = anchorX + (anchor.width - content.measuredWidth) / 2
        val clampedX = desiredX.coerceIn(context.dp(8), screenWidth - content.measuredWidth - context.dp(8))
        val xOff = clampedX - anchorX

        popup.showAsDropDown(
            anchor,
            xOff,
            -anchor.height - content.measuredHeight - context.dp(6),
        )
        return popup
    }
}
