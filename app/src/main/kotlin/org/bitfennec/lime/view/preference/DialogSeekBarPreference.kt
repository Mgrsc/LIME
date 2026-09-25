package org.bitfennec.lime.view.preference

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.DialogPreference
import org.bitfennec.lime.R
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.utils.resolveThemeAttribute
import org.bitfennec.lime.utils.textAppearance
import org.bitfennec.lime.view.widget.setOnChangeListener

/**
 * Custom preference which represents a seek bar which shows the current value in the summary. The
 * value can be changed by clicking on the preference, which brings up a dialog which a seek bar.
 * This implementation also allows for a min / max step value, while being backwards compatible.
 *
 * @property min The minimum value of the seek bar. Must not be greater or equal than [max].
 * @property max The maximum value of the seek bar. Must not be lesser or equal than [min].
 * @property step The step in which the seek bar increases per move. If the provided value is less
 * than 1, 1 will be used as step.
 * @property unit The unit to show after the value. Set to an empty string to disable this feature.
 */
class DialogSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : DialogPreference(context, attrs, defStyleAttr) {

    private var value = 0
    var min: Int
    var max: Int
    var step: Int
    var unit: String

    var default: Int? = null
    var defaultLabel: String? = null

    init {
        context.theme.obtainStyledAttributes(attrs, R.styleable.DialogSeekBarPreference, 0, 0).run {
            try {
                min = getInteger(R.styleable.DialogSeekBarPreference_min, 0)
                max = getInteger(R.styleable.DialogSeekBarPreference_max, 100)
                step = getInteger(R.styleable.DialogSeekBarPreference_step, 1)
                unit = getString(R.styleable.DialogSeekBarPreference_unit) ?: ""
                if (getBoolean(R.styleable.DialogSeekBarPreference_useSimpleSummaryProvider, false)) {
                    summaryProvider = SimpleSummaryProvider
                }
            } finally {
                recycle()
            }
        }
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedInt(defaultValue as? Int ?: 0)
    }

    override fun onClick() {
        showSeekBarDialog()
    }

    /**
     * Shows the seek bar dialog.
     */
    private fun showSeekBarDialog() {
        val ctx = context
        val textView = TextView(ctx).apply {
            text = textForValue(value)
            textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val seekBar = SeekBar(ctx).apply {
            max = progressForValue(this@DialogSeekBarPreference.max)
            progress = progressForValue(value)
            setOnChangeListener {
                textView.text = textForValue(valueForProgress(it))
            }
        }
        val dialogContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (dialogMessage != null) {
                val messageText = TextView(ctx).apply {
                    text = dialogMessage
                }
                val lpMessage = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(24), dp(8), dp(24), dp(8))
                }
                addView(messageText, lpMessage)
            }
            val lpText = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(24), 0, dp(24))
            }
            addView(textView, lpText)

            val lpSeek = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), 0, dp(10), dp(10))
            }
            addView(seekBar, lpSeek)
        }

        AlertDialog.Builder(context)
            .setTitle(this@DialogSeekBarPreference.dialogTitle)
            .setView(dialogContent)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = valueForProgress(seekBar.progress)
                setValue(value)
            }
            .setNeutralButton(R.string.default_) { _, _ ->
                default?.let {
                    setValue(it)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setValue(value: Int) {
        if (callChangeListener(value)) {
            persistInt(value)
            notifyChanged()
        }
    }

    private fun progressForValue(value: Int) = (value - min) / step

    private fun valueForProgress(progress: Int) = (progress * step) + min

    private fun textForValue(value: Int = this@DialogSeekBarPreference.value): String =
        if (value == default && defaultLabel != null) defaultLabel!! else "$value $unit"

    object SimpleSummaryProvider : SummaryProvider<DialogSeekBarPreference> {
        override fun provideSummary(preference: DialogSeekBarPreference): CharSequence {
            return preference.textForValue()
        }
    }
}
