package org.bitfennec.lime.ui.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.data.theme.ThemePreset
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.KeyboardPreviewView
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.ui.fragment.theme.CustomThemeActivity
import org.bitfennec.lime.ui.fragment.theme.ThemeCardAdapter
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import kotlinx.coroutines.launch

class ThemeFragment : Fragment() {

    private lateinit var previewUi: KeyboardPreviewView
    private var previewTheme: Theme = activeTheme
    private lateinit var previewLabel: TextView
    private lateinit var editThemeButton: View
    private lateinit var imageLauncher: ActivityResultLauncher<Theme.Custom?>

    private lateinit var switchFollowSystem: SwitchCompat
    private lateinit var llSystemModes: LinearLayout
    private lateinit var llCustomModes: LinearLayout
    private lateinit var rvAllThemes: RecyclerView

    private lateinit var lightAdapter: ThemeCardAdapter
    private lateinit var darkAdapter: ThemeCardAdapter
    private lateinit var allAdapter: ThemeCardAdapter

    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            ImeEnvironment.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            if (::previewUi.isInitialized) {
                previewUi.setTheme(previewTheme)
            }
        }
    }

    private fun applyCustomTheme(theme: Theme.Custom) {
        ThemeManager.saveTheme(theme)
        if (ThemeManager.prefs.followSystemDayNightTheme.getValue()) {
            ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        }
        if (::switchFollowSystem.isInitialized) {
            switchFollowSystem.isChecked = false
            llSystemModes.visibility = View.GONE
            llCustomModes.visibility = View.VISIBLE
        }
        ThemeManager.setNormalModeTheme(theme)
        refreshAllThemes()
        if (::rvAllThemes.isInitialized) {
            rvAllThemes.scrollToPosition(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageLauncher = registerForActivityResult(CustomThemeActivity.Contract()) { result ->
            if (result == null) return@registerForActivityResult
            when (result) {
                is CustomThemeActivity.BackgroundResult.Created -> {
                    applyCustomTheme(result.theme)
                }
                is CustomThemeActivity.BackgroundResult.Deleted -> {
                    val name = result.name
                    ThemeManager.deleteTheme(name)
                    refreshAllThemes()
                }
                is CustomThemeActivity.BackgroundResult.Updated -> {
                    applyCustomTheme(result.theme)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_theme_modern, container, false)
        initViews(root)
        return root
    }

    override fun onResume() {
        super.onResume()
        if (::switchFollowSystem.isInitialized) {
            val isFollow = ThemeManager.prefs.followSystemDayNightTheme.getValue()
            if (switchFollowSystem.isChecked != isFollow) {
                switchFollowSystem.isChecked = isFollow
            }
            llSystemModes.visibility = if (isFollow) View.VISIBLE else View.GONE
            llCustomModes.visibility = if (isFollow) View.GONE else View.VISIBLE
        }
        showPreview(activeTheme)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
    }

    override fun onPause() {
        super.onPause()
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
    }

    @SuppressLint("SetTextI18n")
    private fun initViews(root: View) {
        previewUi = root.findViewById(R.id.keyboard_preview_view)
        previewLabel = root.findViewById(R.id.theme_preview_label)
        editThemeButton = root.findViewById(R.id.edit_preview_theme)
        editThemeButton.setOnClickListener {
            (previewTheme as? Theme.Custom)?.let { imageLauncher.launch(it) }
        }
        showPreview(activeTheme)

        // 1. Color schemes and themes
        switchFollowSystem = root.findViewById(R.id.switch_follow_system)
        val rowFollowSystem = root.findViewById<LinearLayout>(R.id.row_follow_system)
        llSystemModes = root.findViewById(R.id.ll_system_modes)
        llCustomModes = root.findViewById(R.id.ll_custom_modes)
        val rvLightThemes = root.findViewById<RecyclerView>(R.id.rv_light_themes)
        val rvDarkThemes = root.findViewById<RecyclerView>(R.id.rv_dark_themes)
        rvAllThemes = root.findViewById(R.id.rv_all_themes)
        val btnNewCustom = root.findViewById<LinearLayout>(R.id.btn_new_custom_theme)

        val lightThemes = listOf(
            ThemePreset.MonetLight,
            ThemePreset.MaterialLight,
            ThemePreset.PixelLight,
            ThemePreset.NordLight
        )
        val darkThemes = listOf(
            ThemePreset.MonetDark,
            ThemePreset.MaterialDark,
            ThemePreset.AMOLEDBlack,
            ThemePreset.PixelDark,
            ThemePreset.NordDark,
            ThemePreset.Monokai
        )

        lightAdapter = ThemeCardAdapter(
            themes = lightThemes,
            selectedThemeName = ThemeManager.prefs.lightModeTheme.getValue().name,
            onThemeSelected = { theme ->
                showPreview(theme)
                ThemeManager.setLightModeTheme(theme)
            }
        )
        darkAdapter = ThemeCardAdapter(
            themes = darkThemes,
            selectedThemeName = ThemeManager.prefs.darkModeTheme.getValue().name,
            onThemeSelected = { theme ->
                showPreview(theme)
                ThemeManager.setDarkModeTheme(theme)
            }
        )
        allAdapter = ThemeCardAdapter(
            themes = ThemeManager.getAllThemes(),
            selectedThemeName = ThemeManager.prefs.normalModeTheme.getValue().name,
            onThemeSelected = { theme ->
                showPreview(theme)
                ThemeManager.setNormalModeTheme(theme)
            }
        )

        rvLightThemes.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvLightThemes.adapter = lightAdapter

        rvDarkThemes.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvDarkThemes.adapter = darkAdapter

        rvAllThemes.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvAllThemes.adapter = allAdapter

        val isFollow = ThemeManager.prefs.followSystemDayNightTheme.getValue()
        switchFollowSystem.isChecked = isFollow
        llSystemModes.visibility = if (isFollow) View.VISIBLE else View.GONE
        llCustomModes.visibility = if (isFollow) View.GONE else View.VISIBLE

        rowFollowSystem.setOnClickListener { switchFollowSystem.toggle() }
        switchFollowSystem.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.prefs.followSystemDayNightTheme.setValue(isChecked)
            llSystemModes.visibility = if (isChecked) View.VISIBLE else View.GONE
            llCustomModes.visibility = if (isChecked) View.GONE else View.VISIBLE
            showPreview(activeTheme)
        }

        btnNewCustom.setOnClickListener {
            imageLauncher.launch(null)
        }

        // 2. Key contours and geometry
        val switchKeyBorder = root.findViewById<SwitchCompat>(R.id.switch_key_border)
        val rowKeyBorder = root.findViewById<LinearLayout>(R.id.row_key_border)
        val switchNumberLine = root.findViewById<SwitchCompat>(R.id.switch_number_line)
        val rowNumberLine = root.findViewById<LinearLayout>(R.id.row_number_line)
        val switchQwerty9Geometry = root.findViewById<SwitchCompat>(R.id.switch_qwerty9_geometry)
        val rowQwerty9Geometry = root.findViewById<LinearLayout>(R.id.row_qwerty9_geometry)
        val seekbarKeyRadius = root.findViewById<SeekBar>(R.id.seekbar_key_radius)
        val tvKeyRadiusValue = root.findViewById<TextView>(R.id.tv_key_radius_value)
        val seekbarKeyMarginX = root.findViewById<SeekBar>(R.id.seekbar_key_margin_x)
        val tvKeyMarginXValue = root.findViewById<TextView>(R.id.tv_key_margin_x_value)
        val seekbarKeyMarginY = root.findViewById<SeekBar>(R.id.seekbar_key_margin_y)
        val tvKeyMarginYValue = root.findViewById<TextView>(R.id.tv_key_margin_y_value)

        switchKeyBorder.isChecked = ThemeManager.prefs.keyBorder.getValue()
        rowKeyBorder.setOnClickListener { switchKeyBorder.toggle() }
        switchKeyBorder.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.prefs.keyBorder.setValue(isChecked)
            previewUi.updateKeyBorderPreview(isChecked)
            previewUi.setTheme(previewTheme)
        }

        switchNumberLine.isChecked = ThemeManager.prefs.abcNumberLine.getValue()
        rowNumberLine.setOnClickListener { switchNumberLine.toggle() }
        switchNumberLine.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.prefs.abcNumberLine.setValue(isChecked)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            previewUi.updateNumberLinePreview()
            previewUi.setTheme(previewTheme)
        }

        val qwerty9GeometryPref = AppPrefs.getInstance().keyboardSetting.qwerty9Geometry
        switchQwerty9Geometry.isChecked = qwerty9GeometryPref.getValue()
        rowQwerty9Geometry.setOnClickListener { switchQwerty9Geometry.toggle() }
        switchQwerty9Geometry.setOnCheckedChangeListener { _, isChecked ->
            qwerty9GeometryPref.setValue(isChecked)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            previewUi.updateNumberLinePreview()
            previewUi.setTheme(previewTheme)
        }

        val curRadius = ThemeManager.prefs.keyRadius.getValue()
        seekbarKeyRadius.progress = curRadius
        tvKeyRadiusValue.text = getString(R.string.theme_radius_value, curRadius / resources.displayMetrics.density)
        seekbarKeyRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var trackingTouch = false

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvKeyRadiusValue.text = getString(R.string.theme_radius_value, progress / resources.displayMetrics.density)
                    if (!trackingTouch) ThemeManager.prefs.keyRadius.setValue(progress)
                    previewUi.updateKeyRadiusPreview(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { trackingTouch = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                trackingTouch = false
                seekBar?.let {
                    ThemeManager.prefs.keyRadius.setValue(it.progress)
                }
            }
        })

        val curMarginX = ThemeManager.prefs.keyXMargin.getValue()
        seekbarKeyMarginX.progress = curMarginX
        tvKeyMarginXValue.text = "$curMarginX"
        seekbarKeyMarginX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var trackingTouch = false

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvKeyMarginXValue.text = "$progress"
                    if (!trackingTouch) ThemeManager.prefs.keyXMargin.setValue(progress)
                    previewUi.updateKeyMarginXPreview(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { trackingTouch = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                trackingTouch = false
                seekBar?.let {
                    ThemeManager.prefs.keyXMargin.setValue(it.progress)
                }
            }
        })

        val curMarginY = ThemeManager.prefs.keyYMargin.getValue()
        seekbarKeyMarginY.progress = curMarginY
        tvKeyMarginYValue.text = "$curMarginY"
        seekbarKeyMarginY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var trackingTouch = false

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvKeyMarginYValue.text = "$progress"
                    if (!trackingTouch) ThemeManager.prefs.keyYMargin.setValue(progress)
                    previewUi.updateKeyMarginYPreview(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { trackingTouch = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                trackingTouch = false
                seekBar?.let {
                    ThemeManager.prefs.keyYMargin.setValue(it.progress)
                }
            }
        })

        // 3. Typography and symbols
        val switchFontBold = root.findViewById<SwitchCompat>(R.id.switch_font_bold)
        val rowFontBold = root.findViewById<LinearLayout>(R.id.row_font_bold)
        val switchChineseUppercase = root.findViewById<SwitchCompat>(R.id.switch_chinese_uppercase)
        val rowChineseUppercase = root.findViewById<LinearLayout>(R.id.row_chinese_uppercase)
        val seekbarFontSize = root.findViewById<SeekBar>(R.id.seekbar_font_size)
        val tvFontSizeValue = root.findViewById<TextView>(R.id.tv_font_size_value)
        val seekbarCandidateSize = root.findViewById<SeekBar>(R.id.seekbar_candidate_size)
        val tvCandidateSizeValue = root.findViewById<TextView>(R.id.tv_candidate_size_value)
        val switchKeyboardBalloon = root.findViewById<SwitchCompat>(R.id.switch_keyboard_balloon)
        val rowKeyboardBalloon = root.findViewById<LinearLayout>(R.id.row_keyboard_balloon)
        val switchKeyboardSymbol = root.findViewById<SwitchCompat>(R.id.switch_keyboard_symbol)
        val rowKeyboardSymbol = root.findViewById<LinearLayout>(R.id.row_keyboard_symbol)

        switchFontBold.isChecked = ThemeManager.prefs.keyboardFontBold.getValue()
        rowFontBold.setOnClickListener { switchFontBold.toggle() }
        switchFontBold.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.prefs.keyboardFontBold.setValue(isChecked)
            previewUi.updateFontBoldPreview(isChecked)
        }

        switchChineseUppercase.isChecked = ThemeManager.prefs.keyboardChineseUppercase.getValue()
        rowChineseUppercase.setOnClickListener { switchChineseUppercase.toggle() }
        switchChineseUppercase.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.prefs.keyboardChineseUppercase.setValue(isChecked)
            previewUi.updateChineseUppercasePreview(isChecked)
        }

        val curFontSize = ThemeManager.prefs.keyboardFontSize.getValue()
        seekbarFontSize.progress = curFontSize
        tvFontSizeValue.text = "$curFontSize%"
        seekbarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var trackingTouch = false

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvFontSizeValue.text = "$progress%"
                    if (!trackingTouch) ThemeManager.prefs.keyboardFontSize.setValue(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { trackingTouch = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                trackingTouch = false
                seekBar?.let {
                    ThemeManager.prefs.keyboardFontSize.setValue(it.progress)
                }
            }
        })

        val curCandidateSize = ThemeManager.prefs.candidateTextSize.getValue()
        seekbarCandidateSize.progress = curCandidateSize
        tvCandidateSizeValue.text = getString(R.string.theme_candidate_size_value, 20f + (curCandidateSize.coerceIn(25, 100) - 55) / 15f)
        seekbarCandidateSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var trackingTouch = false

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCandidateSizeValue.text = getString(R.string.theme_candidate_size_value, 20f + (progress.coerceIn(25, 100) - 55) / 15f)
                    if (!trackingTouch) {
                        ThemeManager.prefs.candidateTextSize.setValue(progress)
                        ImeEnvironment.initData()
                        if (::previewUi.isInitialized) {
                            previewUi.setTheme(previewTheme)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { trackingTouch = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                trackingTouch = false
                seekBar?.let {
                    ThemeManager.prefs.candidateTextSize.setValue(it.progress)
                    ImeEnvironment.initData()
                    if (::previewUi.isInitialized) {
                        previewUi.setTheme(previewTheme)
                    }
                }
            }
        })

        switchKeyboardBalloon.isChecked = ThemeManager.prefs.keyboardBalloonShow.getValue()
        rowKeyboardBalloon.setOnClickListener { switchKeyboardBalloon.toggle() }
        switchKeyboardBalloon.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.prefs.keyboardBalloonShow.setValue(isChecked)
            previewUi.updateBalloonPreview(isChecked)
        }

        switchKeyboardSymbol.isChecked = ThemeManager.prefs.keyboardSymbol.getValue()
        rowKeyboardSymbol.setOnClickListener { switchKeyboardSymbol.toggle() }
        switchKeyboardSymbol.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.prefs.keyboardSymbol.setValue(isChecked)
            previewUi.updateSymbolPreview(isChecked)
        }
    }

    private fun showPreview(theme: Theme) {
        previewTheme = theme
        previewUi.setTheme(theme)
        val name = ThemeCardAdapter.getLocalizedThemeName(requireContext(), theme.name)
        previewLabel.text = getString(R.string.theme_preview_label, name)
        editThemeButton.visibility = if (theme is Theme.Custom) View.VISIBLE else View.GONE
    }

    private fun refreshAllThemes() {
        ThemeManager.refreshThemes()
        allAdapter.updateThemes(ThemeManager.getAllThemes(), ThemeManager.prefs.normalModeTheme.getValue().name)
        showPreview(activeTheme)
    }
}
