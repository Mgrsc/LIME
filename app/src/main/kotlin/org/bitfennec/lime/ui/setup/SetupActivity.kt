package org.bitfennec.lime.ui.setup

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.bitfennec.lime.R
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.ui.activity.SettingsActivity
import org.bitfennec.lime.utils.InputMethodUtil
import org.bitfennec.lime.utils.startActivity

/**
 * Modern setup and onboarding wizard.
 * 1. Senses system IME enabled/selected state;
 * 2. Provides immediate feedback on step completion;
 * 3. Interactive typing sandbox;
 * 4. Transparent offline privacy commitment.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var cardStep1: View
    private lateinit var btnStep1Enable: View
    private lateinit var layoutStep1Done: View

    private lateinit var cardStep2: View
    private lateinit var btnStep2Select: View
    private lateinit var layoutStep2Done: View

    private lateinit var cardStep3: View
    private lateinit var etTypingSandbox: View

    private lateinit var btnStartUsing: TextView
    private lateinit var tvPrivacyPolicyLink: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_setup)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            windowInsets
        }

        cardStep1 = findViewById(R.id.card_step1)
        btnStep1Enable = findViewById(R.id.btn_step1_enable)
        layoutStep1Done = findViewById(R.id.layout_step1_done)

        cardStep2 = findViewById(R.id.card_step2)
        btnStep2Select = findViewById(R.id.btn_step2_select)
        layoutStep2Done = findViewById(R.id.layout_step2_done)

        cardStep3 = findViewById(R.id.card_step3)
        etTypingSandbox = findViewById(R.id.et_typing_sandbox)

        btnStartUsing = findViewById(R.id.btn_start_using)
        tvPrivacyPolicyLink = findViewById(R.id.tv_privacy_policy_link)

        btnStep1Enable.setOnClickListener {
            InputMethodUtil.startSettingsActivity(this)
        }

        btnStep2Select.setOnClickListener {
            InputMethodUtil.showPicker()
        }

        btnStartUsing.setOnClickListener {
            AppPrefs.getInstance().internal.privacyPolicySure.setValue(true)
            startActivity<SettingsActivity>()
            finish()
        }

        tvPrivacyPolicyLink.setOnClickListener {
            showPrivacyPolicyDialog()
        }

        onBackPressedDispatcher.addCallback(this) {
            if (InputMethodUtil.isSelected() || AppPrefs.getInstance().internal.privacyPolicySure.getValue()) {
                startActivity<SettingsActivity>()
            }
            finish()
        }

        updateSetupState()
    }

    override fun onResume() {
        super.onResume()
        updateSetupState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateSetupState()
        }
    }

    private fun updateSetupState() {
        val isEnabled = InputMethodUtil.isEnabled()
        val isSelected = InputMethodUtil.isSelected()

        if (isEnabled && isSelected) {
            AppPrefs.getInstance().internal.privacyPolicySure.setValue(true)
        }

        if (isEnabled) {
            btnStep1Enable.visibility = View.GONE
            layoutStep1Done.visibility = View.VISIBLE
            cardStep1.alpha = 1.0f
        } else {
            btnStep1Enable.visibility = View.VISIBLE
            layoutStep1Done.visibility = View.GONE
            cardStep1.alpha = 1.0f
        }

        if (isSelected) {
            btnStep2Select.visibility = View.GONE
            layoutStep2Done.visibility = View.VISIBLE
            cardStep2.alpha = 1.0f
        } else {
            btnStep2Select.visibility = View.VISIBLE
            layoutStep2Done.visibility = View.GONE
            cardStep2.alpha = if (isEnabled) 1.0f else 0.5f
            btnStep2Select.isEnabled = isEnabled
        }

        cardStep3.alpha = if (isSelected) 1.0f else 0.55f
        etTypingSandbox.isEnabled = isSelected

        if (isSelected) {
            btnStartUsing.text = getString(R.string.onboarding_btn_start)
            btnStartUsing.setBackgroundResource(R.drawable.bg_btn_primary)
        } else {
            btnStartUsing.text = getString(R.string.onboarding_btn_skip)
            btnStartUsing.setBackgroundResource(R.drawable.bg_btn_primary)
        }
    }

    private fun showPrivacyPolicyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_privacy_link)
            .setMessage(R.string.onboarding_privacy_detail)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        fun shouldShowUp(): Boolean {
            val isReady = InputMethodUtil.isEnabled() && InputMethodUtil.isSelected()
            if (isReady) {
                AppPrefs.getInstance().internal.privacyPolicySure.setValue(true)
                return false
            }
            return !AppPrefs.getInstance().internal.privacyPolicySure.getValue()
        }
    }
}
