package org.bitfennec.lime.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.prefs.behavior.KeyboardSymbolSlideUpMod
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.InputFeedbacks
import org.bitfennec.lime.prefs.InputFeedbacks.HapticLevel
import org.bitfennec.lime.prefs.behavior.DeleteGestureAction
import org.bitfennec.lime.prefs.behavior.HalfWidthSymbolsMode
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.utils.DevicesUtils

/**
 * Modern card-style input settings page.
 * Provides Chinese input, English input, and symbol / emoji configuration.
 */
class InputSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_input_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val inputPrefs = AppPrefs.getInstance().input
        val keyboardPrefs = AppPrefs.getInstance().keyboardSetting

        val switchFanti = view.findViewById<SwitchCompat>(R.id.switch_fanti)
        val rowFanti = view.findViewById<View>(R.id.row_fanti)
        switchFanti.isChecked = inputPrefs.chineseFanTi.getValue()
        switchFanti.setOnCheckedChangeListener { _, isChecked ->
            inputPrefs.chineseFanTi.setValue(isChecked)
            EnginePipeline.syncImeOptions()
        }
        rowFanti?.setOnClickListener {
            switchFanti.toggle()
        }

        val switchChinesePrediction = view.findViewById<SwitchCompat>(R.id.switch_chinese_prediction)
        val rowChinesePrediction = view.findViewById<View>(R.id.row_chinese_prediction)
        switchChinesePrediction.isChecked = inputPrefs.chinesePrediction.getValue()
        switchChinesePrediction.setOnCheckedChangeListener { _, isChecked ->
            inputPrefs.chinesePrediction.setValue(isChecked)
            EnginePipeline.setChinesePredictionEnabled(isChecked)
        }
        rowChinesePrediction?.setOnClickListener {
            switchChinesePrediction.toggle()
        }

        val switchEnglishCompletion = view.findViewById<SwitchCompat>(R.id.switch_english_completion)
        val rowEnglishCompletion = view.findViewById<View>(R.id.row_english_completion)
        val switchSpaceAuto = view.findViewById<SwitchCompat>(R.id.switch_space_auto)
        val rowSpaceAuto = view.findViewById<View>(R.id.row_space_auto)

        fun updateSpaceAutoEnabled(enabled: Boolean) {
            rowSpaceAuto?.isEnabled = enabled
            rowSpaceAuto?.alpha = if (enabled) 1.0f else 0.45f
            switchSpaceAuto?.isEnabled = enabled
        }

        switchEnglishCompletion.isChecked = inputPrefs.abcSearchEnglishCell.getValue()
        updateSpaceAutoEnabled(switchEnglishCompletion.isChecked)
        switchEnglishCompletion.setOnCheckedChangeListener { _, isChecked ->
            inputPrefs.abcSearchEnglishCell.setValue(isChecked)
            updateSpaceAutoEnabled(isChecked)
        }
        rowEnglishCompletion?.setOnClickListener {
            switchEnglishCompletion.toggle()
        }

        switchSpaceAuto.isChecked = inputPrefs.abcSpaceAuto.getValue()
        switchSpaceAuto.setOnCheckedChangeListener { _, isChecked ->
            inputPrefs.abcSpaceAuto.setValue(isChecked)
        }
        rowSpaceAuto?.setOnClickListener {
            if (switchSpaceAuto.isEnabled) {
                switchSpaceAuto.toggle()
            }
        }

        val switchSwipeDownCaps = view.findViewById<SwitchCompat>(R.id.switch_swipe_down_caps)
        val rowSwipeDownCaps = view.findViewById<View>(R.id.row_swipe_down_caps)
        switchSwipeDownCaps?.isChecked = keyboardPrefs.swipeDownCaps.getValue()
        switchSwipeDownCaps?.setOnCheckedChangeListener { _, isChecked ->
            keyboardPrefs.swipeDownCaps.setValue(isChecked)
        }
        rowSwipeDownCaps?.setOnClickListener {
            switchSwipeDownCaps?.toggle()
        }

        val switchLockEnglish = view.findViewById<SwitchCompat>(R.id.switch_lock_english)
        val rowLockEnglish = view.findViewById<View>(R.id.row_lock_english)
        switchLockEnglish?.isChecked = keyboardPrefs.keyboardLockEnglish.getValue()
        switchLockEnglish?.setOnCheckedChangeListener { _, isChecked ->
            keyboardPrefs.keyboardLockEnglish.setValue(isChecked)
        }
        rowLockEnglish?.setOnClickListener {
            switchLockEnglish?.toggle()
        }

        val switchEmojiInput = view.findViewById<SwitchCompat>(R.id.switch_emoji_input)
        val rowEmojiInput = view.findViewById<View>(R.id.row_emoji_input)
        switchEmojiInput.isChecked = inputPrefs.emojiInput.getValue()
        switchEmojiInput.setOnCheckedChangeListener { _, isChecked ->
            inputPrefs.emojiInput.setValue(isChecked)
            EnginePipeline.syncImeOptions()
        }
        rowEmojiInput?.setOnClickListener {
            switchEmojiInput.toggle()
        }

        val switchSymbolPairInput = view.findViewById<SwitchCompat>(R.id.switch_symbol_pair_input)
        val rowSymbolPairInput = view.findViewById<View>(R.id.row_symbol_pair_input)
        switchSymbolPairInput.isChecked = inputPrefs.symbolPairInput.getValue()
        switchSymbolPairInput.setOnCheckedChangeListener { _, isChecked ->
            inputPrefs.symbolPairInput.setValue(isChecked)
        }
        rowSymbolPairInput?.setOnClickListener {
            switchSymbolPairInput.toggle()
        }

        val halfWidthSymbolsModes = listOf(
            HalfWidthSymbolsMode.All,
            HalfWidthSymbolsMode.OnlyUsed,
            HalfWidthSymbolsMode.None
        )
        val halfWidthSymbolsLabels = arrayOf(
            getString(R.string.half_width_symbols_tips_all),
            getString(R.string.half_width_symbols_tips_only_used),
            getString(R.string.half_width_symbols_tips_none)
        )
        val rowHalfWidth = view.findViewById<View>(R.id.row_half_width_symbols)
        val tvHalfWidthSymbolsValue = view.findViewById<TextView>(R.id.tv_half_width_symbols_value)
        fun updateHalfWidthSymbolsUi(index: Int) {
            val label = halfWidthSymbolsLabels[index]
            tvHalfWidthSymbolsValue?.text = label
            rowHalfWidth?.contentDescription = "${getString(R.string.half_width_symbols_tips)}, ${getString(R.string.half_width_symbols_tips_desc)}, $label"
        }
        val currentHalfWidthIndex = halfWidthSymbolsModes.indexOf(keyboardPrefs.halfWidthSymbolsMode.getValue()).coerceAtLeast(0)
        updateHalfWidthSymbolsUi(currentHalfWidthIndex)
        rowHalfWidth?.setOnClickListener {
            val selectedIndex = halfWidthSymbolsModes.indexOf(keyboardPrefs.halfWidthSymbolsMode.getValue()).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.half_width_symbols_tips)
                .setSingleChoiceItems(halfWidthSymbolsLabels, selectedIndex) { dialog, which ->
                    keyboardPrefs.halfWidthSymbolsMode.setValue(halfWidthSymbolsModes[which])
                    updateHalfWidthSymbolsUi(which)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.row_sidebar_symbols)?.setOnClickListener {
            findNavController().navigate(R.id.action_inputSettingsFragment_to_sidebarSymbolFragment)
        }

        val switchCalc = view.findViewById<SwitchCompat>(R.id.switch_keyboard_calculator)
        val rowCalc = view.findViewById<View>(R.id.row_keyboard_calculator)
        switchCalc?.isChecked = keyboardPrefs.keyboardCalculator.getValue()
        switchCalc?.setOnCheckedChangeListener { _, isChecked ->
            keyboardPrefs.keyboardCalculator.setValue(isChecked)
        }
        rowCalc?.setOnClickListener {
            switchCalc?.toggle()
        }

        val switchPhysicalKeyboard = view.findViewById<SwitchCompat>(R.id.switch_physical_keyboard)
        val rowPhysicalKeyboard = view.findViewById<View>(R.id.row_physical_keyboard)
        switchPhysicalKeyboard?.isChecked = keyboardPrefs.showVirtualKeyboardOnPhysicalKeyboard.getValue()
        switchPhysicalKeyboard?.setOnCheckedChangeListener { _, isChecked ->
            keyboardPrefs.showVirtualKeyboardOnPhysicalKeyboard.setValue(isChecked)
        }
        rowPhysicalKeyboard?.setOnClickListener {
            switchPhysicalKeyboard?.toggle()
        }

        val longPressValue = view.findViewById<TextView>(R.id.tv_long_press_timeout)
        val seekBarLongPress = view.findViewById<SeekBar>(R.id.seekbar_long_press_timeout)
        // yagni: range 100..700 step 50 matches AppPrefs.longPressTimeout; upgrade when prefs metadata exposes step generator
        val minTimeout = 100
        val stepTimeout = 50
        val maxStep = 12
        val currentTimeout = keyboardPrefs.longPressTimeout.getValue()
        val currentProgress = ((currentTimeout - minTimeout) / stepTimeout).coerceIn(0, maxStep)
        val actualTimeout = minTimeout + currentProgress * stepTimeout
        longPressValue.text = getString(R.string.input_timeout_ms, actualTimeout)
        seekBarLongPress?.max = maxStep
        seekBarLongPress?.progress = currentProgress
        seekBarLongPress?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = minTimeout + progress * stepTimeout
                longPressValue.text = getString(R.string.input_timeout_ms, value)
                if (fromUser) {
                    keyboardPrefs.longPressTimeout.setValue(value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val value = minTimeout + it.progress * stepTimeout
                    keyboardPrefs.longPressTimeout.setValue(value)
                }
            }
        })

        val slideValue = view.findViewById<TextView>(R.id.tv_symbol_slide_up)
        // yagni: slideLabels array matches KeyboardSymbolSlideUpMod entries; upgrade when enum exposes label strings directly
        val slideModes = KeyboardSymbolSlideUpMod.entries
        val slideLabels = arrayOf(
            getString(R.string.keyboard_symbol_slide_up_short),
            getString(R.string.keyboard_symbol_slide_up_medium),
            getString(R.string.keyboard_symbol_slide_up_long),
        )
        val currentSlideIndex = slideModes.indexOf(ThemeManager.prefs.symbolSlideUpMod.getValue()).coerceAtLeast(0)
        slideValue.text = slideLabels[currentSlideIndex]
        view.findViewById<View>(R.id.row_symbol_slide_up).setOnClickListener {
            val selectedSlideIndex = slideModes.indexOf(ThemeManager.prefs.symbolSlideUpMod.getValue()).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keyboard_symbol_slide_up_mod)
                .setSingleChoiceItems(slideLabels, selectedSlideIndex) { dialog, which ->
                    ThemeManager.prefs.symbolSlideUpMod.setValue(slideModes[which])
                    slideValue.text = slideLabels[which]
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        setupDeleteGestureCompass(view)

        val soundLevels = listOf(
            Pair(getString(R.string.settings_sound_mute), 10),
            Pair(getString(R.string.settings_default), 0),
            Pair(getString(R.string.settings_sound_medium), 25),
            Pair(getString(R.string.settings_sound_loud), 40)
        )
        val soundLabels = soundLevels.map { it.first }.toTypedArray()
        val tvSoundValue = view.findViewById<TextView>(R.id.tv_sound_feedback_value)
        val rowSoundFeedback = view.findViewById<View>(R.id.row_sound_feedback)
        fun updateSoundFeedbackUi(index: Int) {
            val label = soundLabels[index]
            tvSoundValue?.text = label
            rowSoundFeedback?.contentDescription = "${getString(R.string.button_sound_volume)}, ${getString(R.string.button_sound_volume_desc)}, $label"
        }
        updateSoundFeedbackUi(InputFeedbacks.getSoundLevelIndex())
        rowSoundFeedback?.setOnClickListener {
            val currentIdx = InputFeedbacks.getSoundLevelIndex()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.button_sound_volume)
                .setSingleChoiceItems(soundLabels, currentIdx) { dialog, which ->
                    val selectedValue = soundLevels[which].second
                    AppPrefs.getInstance().internal.soundOnKeyPress.setValue(selectedValue)
                    updateSoundFeedbackUi(which)
                    DevicesUtils.tryPlayKeyDown()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        val vibrateLevels = getVibrateLevels()
        val vibrateLabels = vibrateLevels.map { it.first }.toTypedArray()
        updateHapticUi(view)
        view.findViewById<View>(R.id.row_haptic_feedback)?.setOnClickListener { rowView ->
            val currentLevel = HapticLevel.fromValue(AppPrefs.getInstance().internal.vibrationAmplitude.getValue())
            val currentIdx = vibrateLevels.indexOfFirst { it.second == currentLevel }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.button_vibration_amplitude)
                .setSingleChoiceItems(vibrateLabels, currentIdx) { dialog, which ->
                    val selectedLevel = vibrateLevels[which].second
                    AppPrefs.getInstance().internal.vibrationAmplitude.setValue(selectedLevel.value)
                    if (selectedLevel == HapticLevel.OFF) {
                        InputFeedbacks.cancelHapticFeedback()
                    } else {
                        DevicesUtils.tryVibrate(rowView)
                    }
                    updateHapticUi(view)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private enum class GestureDirection {
        UP, LEFT, DOWN
    }

    private fun setupDeleteGestureCompass(root: View) {
        val keyboardPrefs = AppPrefs.getInstance().keyboardSetting

        val btnUp = root.findViewById<View>(R.id.btn_gesture_swipe_up)
        val ivUpIcon = root.findViewById<ImageView>(R.id.iv_gesture_up_icon)
        val tvUpTitle = root.findViewById<TextView>(R.id.tv_gesture_up_title)

        val btnLeft = root.findViewById<View>(R.id.btn_gesture_swipe_left)
        val ivLeftIcon = root.findViewById<ImageView>(R.id.iv_gesture_left_icon)
        val tvLeftTitle = root.findViewById<TextView>(R.id.tv_gesture_left_title)

        val btnDown = root.findViewById<View>(R.id.btn_gesture_swipe_down)
        val ivDownIcon = root.findViewById<ImageView>(R.id.iv_gesture_down_icon)
        val tvDownTitle = root.findViewById<TextView>(R.id.tv_gesture_down_title)

        fun updateUI() {
            bindDirection(ivUpIcon, tvUpTitle, keyboardPrefs.deleteSwipeUpAction.getValue())
            bindDirection(ivLeftIcon, tvLeftTitle, keyboardPrefs.deleteSwipeLeftAction.getValue())
            bindDirection(ivDownIcon, tvDownTitle, keyboardPrefs.deleteSwipeDownAction.getValue())
        }

        btnUp?.setOnClickListener {
            showActionPicker(GestureDirection.UP) { updateUI() }
        }
        btnLeft?.setOnClickListener {
            showActionPicker(GestureDirection.LEFT) { updateUI() }
        }
        btnDown?.setOnClickListener {
            showActionPicker(GestureDirection.DOWN) { updateUI() }
        }

        updateUI()
    }

    private fun bindDirection(ivIcon: ImageView?, tvTitle: TextView?, action: DeleteGestureAction) {
        if (ivIcon == null || tvTitle == null) return
        val (iconRes, titleRes, _) = actionDetails(action)
        ivIcon.setImageResource(iconRes)
        tvTitle.setText(titleRes)
        val colorRes = when (action) {
            DeleteGestureAction.CLEAR_ALL -> R.color.gesture_clear
            DeleteGestureAction.SWIPE_SELECT -> R.color.gesture_select
            DeleteGestureAction.UNDO_REVERT -> R.color.gesture_undo
            DeleteGestureAction.TO_PUNCTUATION -> R.color.gesture_punctuation
            DeleteGestureAction.NONE -> R.color.settings_secondary_text
        }
        ivIcon.imageTintList = ContextCompat.getColorStateList(ivIcon.context, colorRes)
    }

    private fun actionDetails(action: DeleteGestureAction): Triple<Int, Int, Int> = when (action) {
        DeleteGestureAction.CLEAR_ALL -> Triple(R.drawable.ic_gesture_clear, R.string.delete_action_clear_all, R.string.delete_action_clear_all_desc)
        DeleteGestureAction.SWIPE_SELECT -> Triple(R.drawable.ic_gesture_select, R.string.delete_action_swipe_select, R.string.delete_action_swipe_select_desc)
        DeleteGestureAction.UNDO_REVERT -> Triple(R.drawable.ic_gesture_undo, R.string.delete_action_undo_revert, R.string.delete_action_undo_revert_desc)
        DeleteGestureAction.TO_PUNCTUATION -> Triple(R.drawable.ic_gesture_punctuation, R.string.delete_action_to_punctuation, R.string.delete_action_to_punctuation_desc)
        DeleteGestureAction.NONE -> Triple(R.drawable.ic_gesture_none, R.string.delete_action_none, R.string.delete_action_none_desc)
    }

    private fun showActionPicker(direction: GestureDirection, onSelected: () -> Unit) {
        val context = context ?: return
        val keyboardPrefs = AppPrefs.getInstance().keyboardSetting

        val currentAction = when (direction) {
            GestureDirection.UP -> keyboardPrefs.deleteSwipeUpAction.getValue()
            GestureDirection.LEFT -> keyboardPrefs.deleteSwipeLeftAction.getValue()
            GestureDirection.DOWN -> keyboardPrefs.deleteSwipeDownAction.getValue()
        }

        val dirName = when (direction) {
            GestureDirection.UP -> getString(R.string.delete_gesture_dir_up)
            GestureDirection.LEFT -> getString(R.string.delete_gesture_dir_left)
            GestureDirection.DOWN -> getString(R.string.delete_gesture_dir_down)
        }

        val actions = listOf(
            DeleteGestureAction.CLEAR_ALL,
            DeleteGestureAction.SWIPE_SELECT,
            DeleteGestureAction.UNDO_REVERT,
            DeleteGestureAction.TO_PUNCTUATION,
            DeleteGestureAction.NONE,
        )

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_delete_gesture_picker, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val container = dialogView.findViewById<LinearLayout>(R.id.ll_choices_container)
        val btnCancel = dialogView.findViewById<View>(R.id.btn_dialog_cancel)

        tvTitle.text = getString(R.string.delete_gesture_choose_action, dirName)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        val inflater = LayoutInflater.from(context)
        actions.forEach { action ->
            val itemView = inflater.inflate(R.layout.item_gesture_action_choice, container, false)
            val (iconRes, titleRes, descRes) = actionDetails(action)

            val isSelected = action == currentAction
            itemView.stateDescription = if (isSelected) getString(R.string.accessibility_candidate_selected) else null
            itemView.findViewById<ImageView>(R.id.iv_action_icon).setImageResource(iconRes)
            itemView.findViewById<TextView>(R.id.tv_action_title).setText(titleRes)
            itemView.findViewById<TextView>(R.id.tv_action_desc).setText(descRes)
            val ivCheck = itemView.findViewById<ImageView>(R.id.iv_action_check)

            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_dialog_item_selected)
                ivCheck.setImageResource(R.drawable.ic_check_circle_filled)
            } else {
                itemView.setBackgroundResource(R.drawable.bg_dialog_item_normal)
                ivCheck.setImageResource(R.drawable.ic_circle_outline)
            }

            itemView.setOnClickListener {
                if (action != DeleteGestureAction.NONE) {
                    val duplicateDirections = mutableListOf<String>()
                    if (direction != GestureDirection.UP && keyboardPrefs.deleteSwipeUpAction.getValue() == action) {
                        duplicateDirections.add("↑")
                    }
                    if (direction != GestureDirection.LEFT && keyboardPrefs.deleteSwipeLeftAction.getValue() == action) {
                        duplicateDirections.add("←")
                    }
                    if (direction != GestureDirection.DOWN && keyboardPrefs.deleteSwipeDownAction.getValue() == action) {
                        duplicateDirections.add("↓")
                    }
                    if (duplicateDirections.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            getString(R.string.delete_gesture_already_assigned, duplicateDirections.joinToString(" ")),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                when (direction) {
                    GestureDirection.UP -> keyboardPrefs.deleteSwipeUpAction.setValue(action)
                    GestureDirection.LEFT -> keyboardPrefs.deleteSwipeLeftAction.setValue(action)
                    GestureDirection.DOWN -> keyboardPrefs.deleteSwipeDownAction.setValue(action)
                }
                AppPrefs.getInstance().internal.deleteGestureGuideShown.setValue(true)

                dialog.dismiss()
                onSelected()
            }

            container.addView(itemView)
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        view?.let { updateHapticUi(it) }
    }

    private fun updateHapticUi(targetView: View) {
        val rowHaptic = targetView.findViewById<View>(R.id.row_haptic_feedback)
        val tvHapticValue = targetView.findViewById<TextView>(R.id.tv_haptic_feedback_value)
        val tvHapticDesc = targetView.findViewById<TextView>(R.id.tv_haptic_feedback_desc)
        val currentLevel = HapticLevel.fromValue(AppPrefs.getInstance().internal.vibrationAmplitude.getValue())
        val vibrateLevels = getVibrateLevels()
        val selectedIdx = vibrateLevels.indexOfFirst { it.second == currentLevel }.coerceAtLeast(0)
        val valueText = vibrateLevels[selectedIdx].first
        tvHapticValue?.text = valueText
        val descRes = InputFeedbacks.hapticStatus(targetView)
        tvHapticDesc?.setText(descRes)
        rowHaptic?.contentDescription = "${targetView.context.getString(R.string.button_vibration_amplitude)}, ${targetView.context.getString(descRes)}, $valueText"
    }

    private fun getVibrateLevels(): List<Pair<String, HapticLevel>> =
        HAPTIC_LEVELS.map { (resId, level) -> getString(resId) to level }

    companion object {
        private val HAPTIC_LEVELS = listOf(
            R.string.settings_vibrate_off to HapticLevel.OFF,
            R.string.settings_haptic_system to HapticLevel.SYSTEM,
            R.string.settings_vibrate_light to HapticLevel.LIGHT,
            R.string.settings_vibrate_medium to HapticLevel.MEDIUM,
            R.string.settings_vibrate_strong to HapticLevel.STRONG,
        )
    }

    override fun onStop() {
        super.onStop()
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
    }
}