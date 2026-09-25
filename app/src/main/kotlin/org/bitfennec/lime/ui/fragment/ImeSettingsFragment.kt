package org.bitfennec.lime.ui.fragment

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.bitfennec.lime.R
import org.bitfennec.lime.utils.InputMethodUtil

class ImeSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeroCard(view)
        setupNavigationItems(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { setupHeroCard(it) }
    }

    private fun setupHeroCard(root: View) {
        val tvStatus = root.findViewById<TextView>(R.id.tv_hero_status)
        val btnSetup = root.findViewById<TextView>(R.id.btn_hero_setup)
        val heroCard = root.findViewById<View>(R.id.ll_hero_card)
        val context = requireContext()

        val isReady = InputMethodUtil.isEnabled() && InputMethodUtil.isSelected()
        if (!isReady) {
            tvStatus.text = getString(R.string.settings_status_disabled)
            tvStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.settings_badge_warn_bg)
            )
            tvStatus.setTextColor(
                ContextCompat.getColor(context, R.color.settings_badge_warn_text)
            )
            btnSetup.visibility = View.VISIBLE
            btnSetup.setOnClickListener {
                findNavController().navigate(R.id.action_settingsFragment_to_setupActivity)
            }
            heroCard.setOnClickListener {
                findNavController().navigate(R.id.action_settingsFragment_to_setupActivity)
            }
        } else {
            tvStatus.text = getString(R.string.settings_status_enabled)
            tvStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.settings_badge_active_bg)
            )
            tvStatus.setTextColor(
                ContextCompat.getColor(context, R.color.settings_badge_active_text)
            )
            btnSetup.visibility = View.GONE
            heroCard.setOnClickListener(null)
            heroCard.isClickable = false
        }
    }

    private fun setupNavigationItems(root: View) {
        val navController = findNavController()

        root.findViewById<View>(R.id.item_input_settings)?.setOnClickListener {
            navController.navigate(R.id.action_settingsFragment_to_inputSettingsFragment)
        }
        root.findViewById<View>(R.id.item_voice_settings)?.setOnClickListener {
            navController.navigate(R.id.action_settingsFragment_to_voiceSettingsFragment)
        }
        root.findViewById<View>(R.id.item_handwriting_settings)?.setOnClickListener {
            navController.navigate(R.id.action_settingsFragment_to_handwritingSettingsFragment)
        }

        root.findViewById<View>(R.id.item_theme_settings)?.setOnClickListener {
            navController.navigate(R.id.action_settingsFragment_to_themeSettingsFragment)
        }
        root.findViewById<View>(R.id.item_clipboard_settings)?.setOnClickListener {
            navController.navigate(R.id.action_settingsFragment_to_clipboardSettingsFragment)
        }

        root.findViewById<View>(R.id.item_data_settings)?.setOnClickListener {
            navController.navigate(R.id.action_settingsFragment_to_otherSettingsFragment)
        }
        root.findViewById<View>(R.id.item_about_settings)?.setOnClickListener {
            navController.navigate(R.id.action_settingsFragment_to_aboutFragment)
        }
    }
}
