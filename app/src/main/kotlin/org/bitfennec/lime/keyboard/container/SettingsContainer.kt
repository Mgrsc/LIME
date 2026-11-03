package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.MenuAdapter
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.data.menuSkbFunsPreset
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.database.entity.SkbFun
import org.bitfennec.lime.entity.SkbFunItem
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.view.widget.layout.CustomGridLayoutManager
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.InputFeedbacks.HapticLevel
import org.bitfennec.lime.prefs.InputFeedbacks
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.utils.dp
import java.util.ArrayList

/**
 * In-keyboard settings container.
 * 1. Quick settings grid menu (showSettingsView)
 * 2. Haptic and audio feedback adjustment (showFeedbackSettingView)
 * 3. 2x2 keyboard layout picker (showSkbSelelctModeView)
 * 4. Custom toolbar arrangement (enableDragItem)
 */
@SuppressLint("ViewConstructor")
class SettingsContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private var mRootLayout: LinearLayout? = null
    private var mHeaderLayout: LinearLayout? = null
    private var mIvBack: ImageView? = null
    private var mTvTitle: TextView? = null
    private var mTvDone: TextView? = null

    private var mContentContainer: FrameLayout? = null
    private var mRVMenuLayout: RecyclerView? = null
    private var mFeedbackLayout: View? = null
    private var mModeSelectLayout: ScrollView? = null

    private var mTheme: Theme? = null
    private var adapter: MenuAdapter? = null
    val funItems: MutableList<SkbFunItem> = ArrayList()

    init {
        initView(context)
    }

    private fun initView(context: Context) {
        mTheme = activeTheme
        val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat().coerceAtLeast(dp(8f).toFloat())

        mRootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // 1. Navigation header
        mHeaderLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
            visibility = GONE
        }

        mIvBack = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back_24)
            drawable.setTint(mTheme?.keyTextColor ?: 0xFF333333.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val btnBg = GradientDrawable().apply {
                setColor(mTheme?.keyBackgroundColor ?: 0x1A000000)
                cornerRadius = keyRadius
            }
            background = btnBg
            contentDescription = context.getString(R.string.text_edit_return)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(26)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_DEL)
                DevicesUtils.tryVibrate(this)
                showSettingsView()
            }
        }

        mTvTitle = TextView(context).apply {
            text = ""
            setTextColor(mTheme?.keyTextColor ?: 0xFF333333.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        mTvDone = TextView(context).apply {
            text = context.getString(R.string.done)
            setTextColor(mTheme?.accentKeyBackgroundColor ?: 0xFF2196F3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = GONE
            setOnClickListener {
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(this)
                enableDragItem(false)
                KeyboardManager.instance.switchKeyboard()
            }
        }

        mHeaderLayout?.addView(mIvBack)
        mHeaderLayout?.addView(mTvTitle)
        mHeaderLayout?.addView(mTvDone)
        mRootLayout?.addView(mHeaderLayout)

        // 2. Body container
        mContentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // 3. Menu grid
        mRVMenuLayout = object : RecyclerView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val availableWidth = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                val minItemWidth = (dp(72) * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
                val maxColumns = if (ImeEnvironment.keyboardModeFloat) 4 else 8
                val columns = (availableWidth / minItemWidth).coerceIn(1, maxColumns)
                (layoutManager as? CustomGridLayoutManager)?.let { grid ->
                    if (grid.spanCount != columns) grid.spanCount = columns
                }
                super.onMeasure(widthSpec, heightSpec)
            }
        }.apply {
            setHasFixedSize(true)
            setItemAnimator(null)
            layoutManager = CustomGridLayoutManager(context, 1)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        mContentContainer?.addView(mRVMenuLayout)

        mRootLayout?.addView(mContentContainer)
        this.addView(mRootLayout)
    }

    /**
     * Displays main settings grid.
     */
    fun showSettingsView() {
        mHeaderLayout?.visibility = GONE
        mRVMenuLayout?.visibility = VISIBLE
        mFeedbackLayout?.visibility = GONE
        mModeSelectLayout?.visibility = GONE

        funItems.clear()
        adapter = MenuAdapter(context, funItems)
        adapter?.setOnItemClickListener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            val curItems = funItems
            if (position in curItems.indices) {
                inputView.onSettingsMenuClick(curItems[position].skbMenuMode)
            }
        }
        mRVMenuLayout?.adapter = adapter

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val barNames = AppDatabase.instance.skbFunDao().getALlBarMenu().map { it.name }.toSet()
                val allMenu = AppDatabase.instance.skbFunDao().getAllMenu()
                val unaddedItems = allMenu.filter { !barNames.contains(it.name) }
                val itemsToShow = if (unaddedItems.isNotEmpty()) unaddedItems else allMenu

                val addedModes = mutableSetOf<SkbMenuMode>()
                val newItems = mutableListOf<SkbFunItem>()
                for (item in itemsToShow) {
                    val mode = SkbMenuMode.decodeOrNull(item.name) ?: continue
                    if (addedModes.add(mode)) {
                        val skbFunItem = menuSkbFunsPreset[mode]
                        if (skbFunItem != null) newItems.add(skbFunItem)
                    }
                }
                adapter?.updateItems(newItems, barNames)
            } catch (_: Exception) {}
        }
        inputView.mSkbCandidatesBarView.refreshBarMenus()
    }

    /**
     * Displays in-keyboard haptic and audio feedback panel.
     */
    fun showFeedbackSettingView() {
        mHeaderLayout?.visibility = VISIBLE
        mIvBack?.visibility = VISIBLE
        mTvTitle?.text = context.getString(R.string.keyboard_feedback)
        mTvDone?.visibility = GONE

        mRVMenuLayout?.visibility = GONE
        mModeSelectLayout?.visibility = GONE

        if (mFeedbackLayout == null) {
            mFeedbackLayout = createFeedbackLayout()
            mContentContainer?.addView(mFeedbackLayout)
        } else {
            updateFeedbackViewValues()
        }
        mFeedbackLayout?.visibility = VISIBLE
    }

    private var soundPills: List<TextView> = emptyList()
    private var vibratePills: List<RadioButton> = emptyList()
    private var hapticDescription: TextView? = null

    private fun createFeedbackLayout(): View {
        val theme = activeTheme
        val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat().coerceAtLeast(dp(6f).toFloat())

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(4))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        fun createCard(): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val cardBg = GradientDrawable().apply {
                    setColor(theme.keyBackgroundColor)
                    cornerRadius = keyRadius
                    val strokeAlpha = if (theme.isDark) 24 else 16
                    setStroke(DevicesUtils.dip2px(0.5f).coerceAtLeast(1), ColorUtils.setAlphaComponent(theme.keyTextColor, strokeAlpha))
                }
                background = cardBg
                setPadding(dp(10), dp(5), dp(10), dp(6))
            }
        }

        // 1. Sound feedback card
        val soundCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val soundTitle = TextView(context).apply {
            text = context.getString(R.string.settings_sound_title, context.getString(R.string.button_sound_volume))
            setTextColor(theme.keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
        }
        soundCard.addView(soundTitle)

        val soundRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
                topMargin = dp(4)
            }
        }

        val soundLevels = listOf(
            Pair(context.getString(R.string.settings_sound_mute), 10),
            Pair(context.getString(R.string.settings_default), 0),
            Pair(context.getString(R.string.settings_sound_medium), 25),
            Pair(context.getString(R.string.settings_sound_loud), 40)
        )

        val soundPillViews = mutableListOf<TextView>()
        for ((label, value) in soundLevels) {
            val pill = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                layoutParams = lp
                setOnClickListener {
                    AppPrefs.getInstance().internal.soundOnKeyPress.setValue(value)
                    DevicesUtils.tryPlayKeyDown()
                    DevicesUtils.tryVibrate(this)
                    updateFeedbackViewValues()
                }
            }
            soundRow.addView(pill)
            soundPillViews.add(pill)
        }
        soundPills = soundPillViews
        soundCard.addView(soundRow)

        // 2. Haptic feedback card
        val vibrateCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val vibrateTitle = TextView(context).apply {
            text = context.getString(R.string.settings_haptic_title, context.getString(R.string.button_vibration_amplitude))
            setTextColor(theme.keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
        }
        vibrateCard.addView(vibrateTitle)

        val vibrateScroll = HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val vibrateRow = RadioGroup(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(4), 0, 0)
        }

        val vibrateLevels = listOf(
            Pair(context.getString(R.string.settings_vibrate_off), HapticLevel.OFF),
            Pair(context.getString(R.string.settings_haptic_system), HapticLevel.SYSTEM),
            Pair(context.getString(R.string.settings_vibrate_light), HapticLevel.LIGHT),
            Pair(context.getString(R.string.settings_vibrate_medium), HapticLevel.MEDIUM),
            Pair(context.getString(R.string.settings_vibrate_strong), HapticLevel.STRONG)
        )

        val vibratePillViews = mutableListOf<RadioButton>()
        for ((label, level) in vibrateLevels) {
            val pill = RadioButton(context).apply {
                text = label
                isSoundEffectsEnabled = false
                buttonDrawable = null
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), dp(4))
                minHeight = dp(48)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    AppPrefs.getInstance().internal.vibrationAmplitude.setValue(level.value)
                    if (level == HapticLevel.OFF) InputFeedbacks.cancelHapticFeedback()
                    DevicesUtils.tryVibrate(this)
                    updateFeedbackViewValues()
                }
            }
            vibrateRow.addView(pill)
            vibratePillViews.add(pill)
        }
        vibratePills = vibratePillViews
        vibrateScroll.addView(vibrateRow)
        vibrateCard.addView(vibrateScroll)
        vibrateCard.addView(Button(context).apply {
            text = context.getString(R.string.settings_haptic_preview)
            isSoundEffectsEnabled = false
            setTextColor(theme.accentKeyTextColor)
            background = GradientDrawable().apply {
                setColor(theme.accentKeyBackgroundColor)
                cornerRadius = keyRadius
            }
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                DevicesUtils.tryVibrate(this)
                updateFeedbackViewValues()
            }
        })
        hapticDescription = TextView(context).apply {
            setTextColor(theme.keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(2))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        vibrateCard.addView(hapticDescription)

        layout.addView(soundCard)
        layout.addView(vibrateCard)

        scrollView.addView(layout)

        updateFeedbackViewValues()
        return scrollView
    }

    private fun updateFeedbackViewValues() {
        val theme = activeTheme
        val pillRadius = dp(6f).toFloat()

        fun applyPillState(tv: TextView, isSelected: Boolean) {
            if (isSelected) {
                val bg = GradientDrawable().apply {
                    setColor(theme.accentKeyBackgroundColor)
                    cornerRadius = pillRadius
                }
                tv.background = bg
                tv.setTextColor(theme.accentKeyTextColor)
                tv.typeface = Typeface.DEFAULT_BOLD
            } else {
                val bg = GradientDrawable().apply {
                    setColor(0x18888888)
                    cornerRadius = pillRadius
                }
                tv.background = bg
                tv.setTextColor(theme.keyTextColor)
                tv.typeface = Typeface.DEFAULT
            }
        }

        val soundVal = AppPrefs.getInstance().internal.soundOnKeyPress.getValue()
        val soundSelectedIdx = InputFeedbacks.getSoundLevelIndex(soundVal)
        soundPills.forEachIndexed { index, textView ->
            applyPillState(textView, index == soundSelectedIdx)
        }

        val vibrateVal = HapticLevel.fromValue(AppPrefs.getInstance().internal.vibrationAmplitude.getValue()).value
        val vibrateSelectedIdx = when (vibrateVal) {
            1 -> 0
            0 -> 1
            2 -> 2
            3 -> 3
            else -> 4
        }
        vibratePills.forEachIndexed { index, textView ->
            applyPillState(textView, index == vibrateSelectedIdx)
            textView.isChecked = index == vibrateSelectedIdx
        }
        hapticDescription?.setText(InputFeedbacks.hapticStatus(this))
    }

    fun isShowingModeSelect(): Boolean = mModeSelectLayout?.visibility == VISIBLE

    /**
     * Displays 2x2 keyboard layout selection card.
     */
    fun showSkbSelelctModeView() {
        mHeaderLayout?.visibility = GONE
        mRVMenuLayout?.visibility = GONE
        mFeedbackLayout?.visibility = GONE

        if (mModeSelectLayout == null) {
            mModeSelectLayout = createModeSelectLayout()
            mContentContainer?.addView(mModeSelectLayout)
        } else {
            updateModeSelectCards()
        }
        mModeSelectLayout?.visibility = VISIBLE
    }

    private var modeCards: List<View> = emptyList()

    private fun createModeSelectLayout(): ScrollView {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val modes = listOf(
            Triple(R.string.keyboard_name_t9, R.string.keyboard_type_t9_desc, R.drawable.select_input_mode_py9) to SkbMenuMode.PinyinT9,
            Triple(R.string.keyboard_name_cn26, R.string.keyboard_type_qwerty_desc, R.drawable.select_input_mode_py26) to SkbMenuMode.Pinyin26Jian,
            Triple(R.string.keyboard_name_hand, R.string.keyboard_type_handwriting_desc, R.drawable.select_input_mode_handwriting) to SkbMenuMode.PinyinHandWriting,
            Triple(R.string.double_pinyin_flypy_plus, R.string.keyboard_type_double_pinyin_desc, R.drawable.select_input_mode_dpy26) to SkbMenuMode.Pinyin26Double
        )
        modeCards = modes.map { (resources, mode) ->
            createKeyboardModeCard(context.getString(resources.first), context.getString(resources.second), resources.third, mode)
        }
        modeCards.chunked(2).forEach { cards ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }
            cards.forEach { row.addView(it) }
            layout.addView(row)
        }
        scroll.addView(layout)
        var compactLayout: Boolean? = null
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val minimumCardHeight = modeCards.maxOf { card ->
                val labels = (card as LinearLayout).getChildAt(1) as LinearLayout
                dp(48) + (labels.getChildAt(0) as TextView).lineHeight + (labels.getChildAt(1) as TextView).lineHeight
            }
            val compact = scroll.height < minimumCardHeight * 2 + dp(24)
            if (compactLayout != compact) {
                compactLayout = compact
                modeCards.forEach { view ->
                    val card = view as LinearLayout
                    card.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    card.gravity = if (compact) Gravity.CENTER_VERTICAL else Gravity.CENTER
                    card.minimumHeight = if (compact) dp(48) else 0
                    val icon = card.getChildAt(0)
                    icon.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                        marginEnd = if (compact) dp(8) else 0
                    }
                    val labels = card.getChildAt(1) as LinearLayout
                    labels.gravity = if (compact) Gravity.START else Gravity.CENTER_HORIZONTAL
                    labels.layoutParams = LinearLayout.LayoutParams(
                        if (compact) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        if (compact) 1f else 0f
                    )
                    labels.setPadding(0, if (compact) 0 else dp(5), 0, 0)
                }
            }
        }
        updateModeSelectCards()
        return scroll
    }

    private fun createKeyboardModeCard(title: String, subtitle: String, iconRes: Int, mode: SkbMenuMode): View {
        val theme = activeTheme

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = mode
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            layoutParams = lp
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val iconIv = ImageView(context).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        val titleTv = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.keyTextColor)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val subTv = TextView(context).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(theme.keyTextColor)
            alpha = 0.75f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
        }

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(titleTv)
            addView(subTv)
        }
        card.addView(iconIv)
        card.addView(labels)

        card.setOnClickListener {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(this)
            onKeyboardMenuClick(SkbFunItem(title, iconRes, mode))
        }

        return card
    }

    private fun updateModeSelectCards() {
        val theme = activeTheme
        val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat().coerceAtLeast(dp(10f).toFloat())
        val rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        val isHandwriting = InputModeSwitcher.isChineseHandWriting

        for (card in modeCards) {
            val mode = card.tag as? SkbMenuMode ?: continue
            val isSelected = when (mode) {
                SkbMenuMode.PinyinT9 -> !isHandwriting && rimeValue == CustomConstant.SCHEMA_ZH_T9
                SkbMenuMode.Pinyin26Jian -> !isHandwriting && rimeValue == CustomConstant.SCHEMA_ZH_QWERTY
                SkbMenuMode.PinyinHandWriting -> isHandwriting
                SkbMenuMode.Pinyin26Double -> !isHandwriting && rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY
                else -> false
            }

            val cardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = keyRadius
                if (isSelected) {
                    setColor(ColorUtils.blendARGB(theme.keyBackgroundColor, theme.accentKeyBackgroundColor, 0.12f))
                    setStroke(DevicesUtils.dip2px(1.5f), theme.accentKeyBackgroundColor)
                } else {
                    setColor(theme.keyBackgroundColor)
                    val strokeAlpha = if (theme.isDark) 24 else 16
                    setStroke(DevicesUtils.dip2px(0.5f).coerceAtLeast(1), ColorUtils.setAlphaComponent(theme.keyTextColor, strokeAlpha))
                }
            }
            card.background = cardBg

            val titleTv = ((card as? LinearLayout)?.getChildAt(1) as? LinearLayout)?.getChildAt(0) as? TextView
            val iconIv = (card as? LinearLayout)?.getChildAt(0) as? ImageView

            if (isSelected) {
                titleTv?.setTextColor(theme.accentKeyBackgroundColor)
                iconIv?.drawable?.setTint(theme.accentKeyBackgroundColor)
            } else {
                titleTv?.setTextColor(theme.keyTextColor)
                iconIv?.drawable?.setTint(theme.keyTextColor)
            }
        }
    }

    fun isCustomDragMode(): Boolean = adapter?.dragOverListener != null

    fun isShowingFeedback(): Boolean = mFeedbackLayout?.visibility == VISIBLE

    fun refreshCustomPool() {
        if (!isCustomDragMode()) return
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val barNames = AppDatabase.instance.skbFunDao().getALlBarMenu().map { it.name }.toSet()
                val allMenu = AppDatabase.instance.skbFunDao().getAllMenu()
                val unadded = allMenu.filter { !barNames.contains(it.name) }
                val addedModes = mutableSetOf<SkbMenuMode>()
                val newItems = mutableListOf<SkbFunItem>()
                for (item in unadded) {
                    val mode = SkbMenuMode.decodeOrNull(item.name) ?: continue
                    if (addedModes.add(mode)) {
                        val skbFunItem = menuSkbFunsPreset[mode]
                        if (skbFunItem != null) newItems.add(skbFunItem)
                    }
                }
                adapter?.updateItems(newItems, barNames)
            } catch (_: Exception) {}
        }
    }

    fun enableDragItem(enable: Boolean) {
        mHeaderLayout?.visibility = if (enable) VISIBLE else GONE
        mIvBack?.visibility = GONE
        mTvTitle?.text = context.getString(R.string.toolbar_custom_drag_hint)
        mTvDone?.visibility = if (enable) VISIBLE else GONE

        mRVMenuLayout?.visibility = VISIBLE
        mFeedbackLayout?.visibility = GONE
        mModeSelectLayout?.visibility = GONE

        if (enable) {
            funItems.clear()
            adapter = MenuAdapter(context, funItems)
            mRVMenuLayout?.adapter = adapter
            adapter?.dragOverListener = object : MenuAdapter.DragOverListener {
                override fun onOptionClick(parent: RecyclerView.Adapter<*>?, v: SkbFunItem, position: Int) {
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        try {
                            val dao = AppDatabase.instance.skbFunDao()
                            val barMenu = dao.getBarMenu(v.skbMenuMode.name)
                            if (barMenu == null) {
                                val currentBar = dao.getALlBarMenu()
                                if (currentBar.size >= 5) return@launch
                                val nextPos = currentBar.size
                                dao.insert(SkbFun(name = v.skbMenuMode.name, isKeep = 1, position = nextPos))
                                inputView.mSkbCandidatesBarView.refreshBarMenus()

                                val removeIdx = funItems.indexOfFirst { it.skbMenuMode == v.skbMenuMode }
                                if (removeIdx != -1) {
                                    funItems.removeAt(removeIdx)
                                    adapter?.notifyItemRemoved(removeIdx)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            refreshCustomPool()
        } else {
            adapter?.dragOverListener = null
            adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
        }
        inputView.mSkbCandidatesBarView.refreshBarMenus()
    }

    private fun onKeyboardMenuClick(data: SkbFunItem) {
        val value = when (data.skbMenuMode) {
            SkbMenuMode.Pinyin26Jian -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_QWERTY)
            SkbMenuMode.PinyinHandWriting -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, InputModeSwitcher.currentRimeSchema())
            SkbMenuMode.Pinyin26Double -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY)
            else -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, CustomConstant.SCHEMA_ZH_T9)
        }
        inputView.resetToIdleState()
        InputModeSwitcher.switchModeForSetting(value)
    }
}
