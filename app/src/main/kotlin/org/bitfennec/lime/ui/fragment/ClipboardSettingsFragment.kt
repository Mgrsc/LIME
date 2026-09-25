package org.bitfennec.lime.ui.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.bitfennec.lime.R
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.styledColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern card-style clipboard settings page.
 * Manages clipboard monitoring, storage limits, suggestions bar, and history data cleanup.
 */
class ClipboardSettingsFragment : Fragment() {

    private lateinit var switchListening: SwitchCompat
    private lateinit var rowListening: View
    private lateinit var rowLimit: View
    private lateinit var tvLimitValue: TextView

    private lateinit var switchSuggestion: SwitchCompat
    private lateinit var rowSuggestion: View
    private lateinit var rowTimeout: View
    private lateinit var tvTimeoutValue: TextView
    private lateinit var rowLayout: View
    private lateinit var tvLayoutValue: TextView

    private lateinit var tvCountValue: TextView
    private lateinit var rowClear: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_clipboard_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val clipPrefs = AppPrefs.getInstance().clipboard

        switchListening = view.findViewById(R.id.switch_clipboard_listening)
        rowListening = view.findViewById(R.id.row_clipboard_listening)
        rowLimit = view.findViewById(R.id.row_clipboard_limit)
        tvLimitValue = view.findViewById(R.id.tv_clipboard_limit_value)

        switchListening.isChecked = clipPrefs.clipboardListening.getValue()
        switchListening.setOnCheckedChangeListener { _, isChecked ->
            clipPrefs.clipboardListening.setValue(isChecked)
            updateDependenciesState()
        }
        rowListening.setOnClickListener { switchListening.toggle() }

        rowLimit.setOnClickListener {
            if (switchListening.isChecked) {
                showLimitDialog()
            }
        }

        switchSuggestion = view.findViewById(R.id.switch_clipboard_suggestion)
        rowSuggestion = view.findViewById(R.id.row_clipboard_suggestion)
        rowTimeout = view.findViewById(R.id.row_clipboard_timeout)
        tvTimeoutValue = view.findViewById(R.id.tv_clipboard_timeout_value)
        rowLayout = view.findViewById(R.id.row_clipboard_layout)
        tvLayoutValue = view.findViewById(R.id.tv_clipboard_layout_value)

        switchSuggestion.isChecked = clipPrefs.clipboardSuggestion.getValue()
        switchSuggestion.setOnCheckedChangeListener { _, isChecked ->
            clipPrefs.clipboardSuggestion.setValue(isChecked)
            updateDependenciesState()
        }
        rowSuggestion.setOnClickListener {
            if (switchListening.isChecked) {
                switchSuggestion.toggle()
            }
        }

        rowTimeout.setOnClickListener {
            if (switchListening.isChecked && switchSuggestion.isChecked) {
                showTimeoutDialog()
            }
        }

        rowLayout.setOnClickListener {
            if (switchListening.isChecked) {
                showLayoutDialog()
            }
        }

        tvCountValue = view.findViewById(R.id.tv_clipboard_count_value)
        rowClear = view.findViewById(R.id.row_clear_clipboard)
        rowClear.setOnClickListener {
            showClearConfirmDialog()
        }

        updateUiValues()
        updateDependenciesState()
        refreshClipboardStats()
    }

    override fun onResume() {
        super.onResume()
        updateUiValues()
        updateDependenciesState()
        refreshClipboardStats()
    }

    private fun updateDependenciesState() {
        val listening = switchListening.isChecked
        val suggestion = switchSuggestion.isChecked

        rowLimit.isEnabled = listening
        rowLimit.alpha = if (listening) 1.0f else 0.45f

        rowSuggestion.isEnabled = listening
        rowSuggestion.alpha = if (listening) 1.0f else 0.45f
        switchSuggestion.isEnabled = listening

        val timeoutEnabled = listening && suggestion
        rowTimeout.isEnabled = timeoutEnabled
        rowTimeout.alpha = if (timeoutEnabled) 1.0f else 0.45f

        rowLayout.isEnabled = listening
        rowLayout.alpha = if (listening) 1.0f else 0.45f
    }

    private fun updateUiValues() {
        val clipPrefs = AppPrefs.getInstance().clipboard

        val limit = clipPrefs.clipboardHistoryLimit.getValue().coerceIn(20, 1000)
        tvLimitValue.text = if (limit == 200) {
            resources.getQuantityString(R.plurals.clipboard_item_count_default_format, limit, limit)
        } else {
            resources.getQuantityString(R.plurals.clipboard_item_count_format, limit, limit)
        }

        val timeout = clipPrefs.clipboardItemTimeout.getValue()
        tvTimeoutValue.text = if (timeout == 30) {
            resources.getQuantityString(R.plurals.clipboard_timeout_default_format, timeout, timeout)
        } else {
            resources.getQuantityString(R.plurals.clipboard_timeout_format, timeout, timeout)
        }

        val mode = clipPrefs.clipboardLayoutCompact.getValue()
        tvLayoutValue.text = when (mode) {
            ClipboardLayoutMode.GridView -> getString(R.string.clipboard_layout_mode_grid_default)
            ClipboardLayoutMode.ListView -> getString(R.string.clipboard_layout_mode_list_plain)
            ClipboardLayoutMode.FlexboxView -> getString(R.string.clipboard_layout_mode_flexbox)
        }
    }

    private fun refreshClipboardStats() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val allList = AppDatabase.instance.clipboardDao().getAll()
            val total = allList.size
            val pinned = allList.count { it.isKeep == 1 }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    tvCountValue.text = resources.getQuantityString(R.plurals.clipboard_total_records_format, total, total, pinned)
                }
            }
        }
    }

    private fun showLimitDialog() {
        val current = AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue().coerceIn(20, 1000)
        var selectedValue = current

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padH = DevicesUtils.dip2px(24)
            val padV = DevicesUtils.dip2px(16)
            setPadding(padH, padV, padH, DevicesUtils.dip2px(8))
        }

        val tvValue = TextView(requireContext()).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            text = if (selectedValue == 200) {
                resources.getQuantityString(R.plurals.clipboard_item_count_default_format, selectedValue, selectedValue)
            } else {
                resources.getQuantityString(R.plurals.clipboard_item_count_format, selectedValue, selectedValue)
            }
        }
        container.addView(tvValue)

        val slider = Slider(requireContext()).apply {
            valueFrom = 20f
            valueTo = 1000f
            stepSize = 1f
            value = selectedValue.toFloat()
            addOnChangeListener { _, rawValue, fromUser ->
                if (fromUser) {
                    val rawInt = rawValue.toInt().coerceIn(20, 1000)
                    if (selectedValue != rawInt) {
                        selectedValue = rawInt
                        tvValue.text = if (rawInt == 200) {
                            resources.getQuantityString(R.plurals.clipboard_item_count_default_format, rawInt, rawInt)
                        } else {
                            resources.getQuantityString(R.plurals.clipboard_item_count_format, rawInt, rawInt)
                        }
                    }
                }
            }
        }
        container.addView(slider)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clipboard_limit)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppPrefs.getInstance().clipboard.clipboardHistoryLimit.setValue(selectedValue)
                updateUiValues()
            }
            .setNeutralButton(R.string.settings_reset_default) { _, _ ->
                AppPrefs.getInstance().clipboard.clipboardHistoryLimit.setValue(200)
                updateUiValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTimeoutDialog() {
        val timeoutValues = intArrayOf(10, 30, 60, 120, 300)
        val options = timeoutValues.map { timeout ->
            timeout to if (timeout == 30) {
                resources.getQuantityString(R.plurals.clipboard_timeout_default_format, timeout, timeout)
            } else {
                resources.getQuantityString(R.plurals.clipboard_timeout_format, timeout, timeout)
            }
        }.toTypedArray()
        val current = AppPrefs.getInstance().clipboard.clipboardItemTimeout.getValue()
        var selectedIndex = options.indexOfFirst { it.first == current }
        if (selectedIndex < 0) selectedIndex = 1

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clipboard_timeout_title)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selectedIndex) { dialog, which ->
                val chosen = options[which].first
                AppPrefs.getInstance().clipboard.clipboardItemTimeout.setValue(chosen)
                updateUiValues()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLayoutDialog() {
        val options = listOf(
            Triple(ClipboardLayoutMode.FlexboxView, getString(R.string.clipboard_layout_flexbox_title), getString(R.string.clipboard_layout_flexbox_desc)),
            Triple(ClipboardLayoutMode.GridView, getString(R.string.clipboard_layout_grid_title), getString(R.string.clipboard_layout_grid_desc)),
            Triple(ClipboardLayoutMode.ListView, getString(R.string.clipboard_layout_list_title), getString(R.string.clipboard_layout_list_desc))
        )
        var selectedMode = AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()

        val context = requireContext()
        val primaryColor = runCatching {
            context.styledColor(android.R.attr.colorPrimary)
        }.getOrDefault("#1976D2".toColorInt())
        val secondaryColor = runCatching {
            context.styledColor(android.R.attr.textColorSecondary)
        }.getOrDefault("#808080".toColorInt())

        val selectedBgColor = Color.argb(30, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        val unselectedBgColor = Color.TRANSPARENT
        val cardRadius = DevicesUtils.dip2px(10).toFloat()
        val unselectedStrokeColor = Color.argb(51, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor))

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padH = DevicesUtils.dip2px(16)
            val padV = DevicesUtils.dip2px(20)
            setPadding(padH, padV, padH, padV)
        }

        val cardViews = mutableListOf<Pair<ClipboardLayoutMode, LinearLayout>>()

        fun updateCardStyles() {
            cardViews.forEach { (mode, layout) ->
                val isSelected = mode == selectedMode
                layout.isSelected = isSelected
                layout.background = GradientDrawable().apply {
                    cornerRadius = cardRadius
                    setColor(if (isSelected) selectedBgColor else unselectedBgColor)
                    setStroke(
                        if (isSelected) DevicesUtils.dip2px(2) else DevicesUtils.dip2px(1),
                        if (isSelected) primaryColor else unselectedStrokeColor
                    )
                }
            }
        }

        options.forEach { (mode, title, desc) ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = DevicesUtils.dip2px(8)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val margin = DevicesUtils.dip2px(4)
                    setMargins(margin, 0, margin, 0)
                }
                isClickable = true
                isFocusable = true

                val thumbnail = ClipboardLayoutThumbnailView(context, mode).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        DevicesUtils.dip2px(52)
                    ).apply {
                        bottomMargin = DevicesUtils.dip2px(8)
                    }
                }
                addView(thumbnail)

                val tvTitle = TextView(context).apply {
                    text = title
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(tvTitle)

                val tvDesc = TextView(context).apply {
                    text = desc
                    textSize = 10f
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(secondaryColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(tvDesc)

                setOnClickListener {
                    selectedMode = mode
                    updateCardStyles()
                }
            }
            cardViews.add(mode to card)
            rootLayout.addView(card)
        }

        updateCardStyles()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.clipboard_layout_compact_mode)
            .setView(rootLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppPrefs.getInstance().clipboard.clipboardLayoutCompact.setValue(selectedMode)
                updateUiValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class ClipboardLayoutThumbnailView @JvmOverloads constructor(
        context: Context,
        private val mode: ClipboardLayoutMode,
        attrs: AttributeSet? = null
    ) : View(context, attrs) {
        private val secondaryColor = runCatching {
            context.styledColor(android.R.attr.textColorSecondary)
        }.getOrDefault("#808080".toColorInt())

        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor))
            style = Paint.Style.FILL
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(68, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor))
            style = Paint.Style.FILL
        }
        private val rectF = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cardRadius = DevicesUtils.dip2px(3).toFloat()

            when (mode) {
                ClipboardLayoutMode.GridView -> {
                    val pad = DevicesUtils.dip2px(3).toFloat()
                    val gap = DevicesUtils.dip2px(3).toFloat()
                    val colW = (w - pad * 2 - gap) / 2f
                    val rowH = (h - pad * 2 - gap) / 2f
                    for (r in 0..1) {
                        for (c in 0..1) {
                            val l = pad + c * (colW + gap)
                            val t = pad + r * (rowH + gap)
                            rectF.set(l, t, l + colW, t + rowH)
                            canvas.drawRoundRect(rectF, cardRadius, cardRadius, cardPaint)

                            rectF.set(l + DevicesUtils.dip2px(2), t + DevicesUtils.dip2px(3), l + colW - DevicesUtils.dip2px(4), t + DevicesUtils.dip2px(5))
                            canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)
                            rectF.set(l + DevicesUtils.dip2px(2), t + DevicesUtils.dip2px(7), l + colW * 0.65f, t + DevicesUtils.dip2px(9))
                            canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)
                        }
                    }
                }
                ClipboardLayoutMode.ListView -> {
                    val pad = DevicesUtils.dip2px(3).toFloat()
                    val gap = DevicesUtils.dip2px(3).toFloat()
                    val rowW = w - pad * 2
                    val rowH = (h - pad * 2 - gap * 2) / 3f
                    for (r in 0..2) {
                        val t = pad + r * (rowH + gap)
                        rectF.set(pad, t, pad + rowW, t + rowH)
                        canvas.drawRoundRect(rectF, cardRadius, cardRadius, cardPaint)

                        rectF.set(pad + DevicesUtils.dip2px(3), t + DevicesUtils.dip2px(3), pad + rowW - DevicesUtils.dip2px(6), t + DevicesUtils.dip2px(5))
                        canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)
                        rectF.set(pad + DevicesUtils.dip2px(3), t + DevicesUtils.dip2px(7), pad + rowW * 0.5f, t + DevicesUtils.dip2px(9))
                        canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)
                    }
                }
                ClipboardLayoutMode.FlexboxView -> {
                    val pad = DevicesUtils.dip2px(3).toFloat()
                    val gap = DevicesUtils.dip2px(3).toFloat()
                    val colW = (w - pad * 2 - gap) / 2f
                    val totalH = h - pad * 2 - gap

                    val h0_0 = totalH * 0.58f
                    val h1_0 = totalH * 0.38f

                    // Col 0
                    rectF.set(pad, pad, pad + colW, pad + h0_0)
                    canvas.drawRoundRect(rectF, cardRadius, cardRadius, cardPaint)
                    rectF.set(pad + DevicesUtils.dip2px(2), pad + DevicesUtils.dip2px(3), pad + colW - DevicesUtils.dip2px(4), pad + DevicesUtils.dip2px(5))
                    canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)
                    rectF.set(pad + DevicesUtils.dip2px(2), pad + DevicesUtils.dip2px(7), pad + colW * 0.65f, pad + DevicesUtils.dip2px(9))
                    canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)

                    rectF.set(pad, pad + h0_0 + gap, pad + colW, pad + totalH + gap)
                    canvas.drawRoundRect(rectF, cardRadius, cardRadius, cardPaint)
                    rectF.set(pad + DevicesUtils.dip2px(2), pad + h0_0 + gap + DevicesUtils.dip2px(3), pad + colW - DevicesUtils.dip2px(4), pad + h0_0 + gap + DevicesUtils.dip2px(5))
                    canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)

                    // Col 1
                    val c1X = pad + colW + gap
                    rectF.set(c1X, pad, c1X + colW, pad + h1_0)
                    canvas.drawRoundRect(rectF, cardRadius, cardRadius, cardPaint)
                    rectF.set(c1X + DevicesUtils.dip2px(2), pad + DevicesUtils.dip2px(3), c1X + colW - DevicesUtils.dip2px(4), pad + DevicesUtils.dip2px(5))
                    canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)

                    rectF.set(c1X, pad + h1_0 + gap, c1X + colW, pad + totalH + gap)
                    canvas.drawRoundRect(rectF, cardRadius, cardRadius, cardPaint)
                    rectF.set(c1X + DevicesUtils.dip2px(2), pad + h1_0 + gap + DevicesUtils.dip2px(3), c1X + colW - DevicesUtils.dip2px(4), pad + h1_0 + gap + DevicesUtils.dip2px(5))
                    canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)
                    rectF.set(c1X + DevicesUtils.dip2px(2), pad + h1_0 + gap + DevicesUtils.dip2px(7), c1X + colW * 0.65f, pad + h1_0 + gap + DevicesUtils.dip2px(9))
                    canvas.drawRoundRect(rectF, DevicesUtils.dip2px(1).toFloat(), DevicesUtils.dip2px(1).toFloat(), linePaint)
                }
            }
        }
    }

    private fun showClearConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clipboard_clear_confirm_title)
            .setMessage(R.string.clipboard_clear_confirm_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.instance.clipboardDao().deleteAllExceptKeep()
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(requireContext(), R.string.clipboard_clear_success, Toast.LENGTH_SHORT).show()
                            refreshClipboardStats()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
