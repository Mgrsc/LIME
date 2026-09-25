package org.bitfennec.lime.view.preference

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.DialogPreference
import org.bitfennec.lime.R
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.utils.resolveThemeAttribute
import org.bitfennec.lime.utils.textAppearance
import org.bitfennec.lime.view.widget.setOnChangeListener

class TwinSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : DialogPreference(context, attrs) {

    var min: Int = 0
    var max: Int = 100
    var step: Int = 1
    var unit: String = ""

    var label: String = ""
    var secondaryKey: String = ""
    var secondaryLabel: String = ""

    var default: Int? = null
    var secondaryDefault: Int? = null
    var defaultLabel: String? = null

    var value = 0
        private set
    var secondaryValue = 0
        private set

    override fun onSetInitialValue(defaultValue: Any?) {
        preferenceDataStore?.apply {
            value = getInt(key, 0)
            secondaryValue = getInt(secondaryKey, 0)
        } ?: sharedPreferences?.apply {
            value = getInt(key, 0)
            secondaryValue = getInt(secondaryKey, 0)
        }
    }

    private fun persistValues(primary: Int, secondary: Int) {
        value = primary
        secondaryValue = secondary
        preferenceDataStore?.apply {
            putInt(key, primary)
            putInt(secondaryKey, secondary)
        } ?: sharedPreferences?.edit {
            putInt(key, primary)
            putInt(secondaryKey, secondary)
        }
    }

    override fun onClick() {
        showDialog()
    }

    private fun createSeekBarSection(
        label: String,
        initialValue: Int,
        defaultValue: Int? = null
    ): Pair<LinearLayout, SeekBar> {
        val ctx = context
        val textLabel = TextView(ctx).apply {
            text = label
            textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        }
        val valueLabel = TextView(ctx).apply {
            text = textForValue(initialValue, defaultValue)
            textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        }
        val seekBar = SeekBar(ctx).apply {
            max = progressForValue(this@TwinSeekBarPreference.max)
            progress = progressForValue(initialValue)
            setOnChangeListener {
                valueLabel.text = textForValue(valueForProgress(it), defaultValue)
            }
        }
        val headerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(textLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val pad = dp(24)
            setPadding(pad, dp(16), pad, 0)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val lpSeek = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), dp(8), dp(10), dp(8))
            }
            addView(seekBar, lpSeek)
        }
        return Pair(container, seekBar)
    }

    private fun showDialog() {
        val ctx = context
        val (primaryView, primarySeekBar) = createSeekBarSection(label, value, default)
        val (secondaryView, secondarySeekBar) = createSeekBarSection(secondaryLabel, secondaryValue, secondaryDefault)

        val dialogContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (dialogMessage != null) {
                val messageText = TextView(ctx).apply {
                    text = dialogMessage
                }
                val lpMessage = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(24), dp(8), dp(24), dp(8))
                }
                addView(messageText, lpMessage)
            }
            addView(primaryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(secondaryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(context)
            .setTitle(this@TwinSeekBarPreference.dialogTitle)
            .setView(dialogContent)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val primary = valueForProgress(primarySeekBar.progress)
                val secondary = valueForProgress(secondarySeekBar.progress)
                setValue(primary, secondary)
            }
            .setNeutralButton(R.string.default_) { _, _ ->
                default?.let { p ->
                    secondaryDefault?.let { s ->
                        setValue(p, s)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setValue(primary: Int, secondary: Int) {
        if (callChangeListener(primary to secondary)) {
            persistValues(primary, secondary)
            notifyChanged()
        }
    }

    private fun progressForValue(value: Int) = (value - min) / step

    private fun valueForProgress(progress: Int) = (progress * step) + min

    private fun textForValue(value: Int, default: Int? = null): String =
        if (value == default && defaultLabel != null) defaultLabel!! else "$value $unit"

    object SimpleSummaryProvider : SummaryProvider<TwinSeekBarPreference> {
        override fun provideSummary(preference: TwinSeekBarPreference): CharSequence {
            return preference.run {
                val primary = textForValue(value, default)
                val secondary = textForValue(secondaryValue, secondaryDefault)
                "$primary / $secondary"
            }
        }
    }
}
