package org.bitfennec.lime.ui.fragment

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.bitfennec.lime.R
import org.bitfennec.lime.core.runtime.AiBlobDownloader
import org.bitfennec.lime.core.runtime.AiDownloadManager
import org.bitfennec.lime.core.runtime.AiModuleManager
import org.bitfennec.lime.core.runtime.AiPackageInstaller
import org.bitfennec.lime.core.runtime.AiPackageInstaller.PackageStatus
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.view.widget.HandwritingStrokePreviewView

/**
 * Modern card-style handwriting settings page.
 * Provides stroke thickness adjustment with live preview, offline model status, and engine specs.
 */
class HandwritingSettingsFragment : Fragment() {

    private lateinit var tvPaintThicknessValue: TextView
    private lateinit var tvDiscernSensitiveValue: TextView
    private lateinit var tvHwModelBadge: TextView
    private lateinit var tvHwModelDesc: TextView
    private lateinit var btnDeleteHwModel: TextView
    private lateinit var btnDownloadHwModel: TextView
    private lateinit var tvHwCurrentMirror: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_handwriting_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvPaintThicknessValue = view.findViewById(R.id.tv_paint_thickness_value)
        tvDiscernSensitiveValue = view.findViewById(R.id.tv_discern_sensitive_value)
        tvHwModelBadge = view.findViewById(R.id.tv_hw_model_badge)
        tvHwModelDesc = view.findViewById(R.id.tv_hw_model_desc)

        view.findViewById<View>(R.id.row_paint_thickness)?.setOnClickListener {
            showThicknessDialog()
        }

        view.findViewById<View>(R.id.row_discern_sensitive)?.setOnClickListener {
            showSpeedDialog()
        }

        btnDeleteHwModel = view.findViewById(R.id.btn_delete_hw_model)
        btnDeleteHwModel.setOnClickListener {
            confirmDeleteHwModel()
        }

        btnDownloadHwModel = view.findViewById(R.id.btn_download_hw_model)
        btnDownloadHwModel.setOnClickListener {
            val context = requireContext()
            if (AiDownloadManager.isDownloading("handwriting")) {
                startDownloadHwModel(force = false)
            } else if (AiModuleManager.isHandwritingReady(context)) {
                startDownloadHwModel(force = true)
            } else {
                startDownloadHwModel(force = false)
            }
        }

        view.findViewById<View>(R.id.row_hw_tech_specs)?.setOnClickListener {
            showTechSpecsDialog()
        }

        tvHwCurrentMirror = view.findViewById(R.id.tv_hw_current_mirror)
        view.findViewById<View>(R.id.row_hw_mirrors)?.setOnClickListener {
            showMirrorsDialog()
        }

        updateMirrorSummary()
        updateThicknessAndSpeedUi()
        updateModelCardUi()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AiDownloadManager.getStatusFlow("handwriting").collect { status ->
                    when (status) {
                        is PackageStatus.Downloading -> {
                            val stageInfo = if (status.stageDesc.isNotEmpty()) {
                                status.stageDesc
                            } else if (status.speedBps > 0) {
                                val speedStr = AiBlobDownloader.formatSpeed(status.speedBps)
                                "${status.artifactName} ($speedStr)"
                            } else {
                                status.artifactName
                            }
                            tvHwModelBadge.text = getString(R.string.voice_model_downloading, status.percent)
                            tvHwModelBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.settings_accent_color))
                            tvHwModelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.settings_chip_badge_bg))
                            tvHwModelDesc.text = stageInfo
                            btnDeleteHwModel.visibility = View.GONE
                            btnDownloadHwModel.text = getString(R.string.voice_btn_view_progress)
                        }
                        is PackageStatus.Ready,
                        is PackageStatus.Failed,
                        is PackageStatus.Missing -> {
                            updateModelCardUi()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateMirrorSummary()
        updateThicknessAndSpeedUi()
        if (!AiDownloadManager.isDownloading("handwriting")) {
            updateModelCardUi()
        }
    }

    private fun updateThicknessAndSpeedUi() {
        val hwPrefs = AppPrefs.getInstance().handwriting
        val thickness = hwPrefs.handWritingWidth.getValue().coerceIn(0, 100)
        val speed = hwPrefs.handWritingSpeed.getValue().coerceIn(300, 1300)

        val defSuffix = getString(R.string.handwriting_default_suffix)
        tvPaintThicknessValue.text = if (thickness == 35) "$thickness% ($defSuffix)" else "$thickness%"
        tvDiscernSensitiveValue.text = if (speed == 500) {
            "${getString(R.string.handwriting_unit_ms, speed)} ($defSuffix)"
        } else {
            getString(R.string.handwriting_unit_ms, speed)
        }
    }

    private fun updateModelCardUi() {
        val context = context ?: return
        if (AiDownloadManager.isDownloading("handwriting")) {
            return
        }
        val isReady = AiModuleManager.isHandwritingReady(context)
        if (isReady) {
            tvHwModelBadge.text = getString(R.string.handwriting_model_ready)
            tvHwModelBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.settings_badge_active_text))
            tvHwModelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.settings_badge_active_bg))
            tvHwModelDesc.text = getString(R.string.handwriting_model_desc)
            btnDeleteHwModel.visibility = View.VISIBLE
            btnDownloadHwModel.text = getString(R.string.voice_btn_redownload)
        } else {
            tvHwModelBadge.text = getString(R.string.handwriting_model_not_installed)
            tvHwModelBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.settings_badge_warn_text))
            tvHwModelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.settings_badge_warn_bg))
            tvHwModelDesc.text = getString(R.string.handwriting_model_desc_not_installed)
            btnDeleteHwModel.visibility = View.GONE
            btnDownloadHwModel.text = getString(R.string.voice_btn_download_now)
        }
    }

    private fun showThicknessDialog() {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_handwriting_thickness_picker, null)
        val previewView = dialogView.findViewById<HandwritingStrokePreviewView>(R.id.stroke_preview_view)
        val tvVal = dialogView.findViewById<TextView>(R.id.tv_dialog_thickness_val)
        val seekbar = dialogView.findViewById<SeekBar>(R.id.seekbar_thickness)

        val currentVal = AppPrefs.getInstance().handwriting.handWritingWidth.getValue().coerceIn(0, 100)

        val defSuffix = getString(R.string.handwriting_default_suffix)
        seekbar.max = 100
        seekbar.progress = currentVal
        previewView.setThicknessPercent(currentVal)
        tvVal.text = if (currentVal == 35) "$currentVal% ($defSuffix)" else "$currentVal%"

        var selectedVal = currentVal

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val effectiveVal = progress.coerceIn(0, 100)
                selectedVal = effectiveVal
                previewView.setThicknessPercent(effectiveVal)
                tvVal.text = if (effectiveVal == 35) "$effectiveVal% ($defSuffix)" else "$effectiveVal%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton(R.string.voice_btn_confirm) { _, _ ->
                AppPrefs.getInstance().handwriting.handWritingWidth.setValue(selectedVal)
                updateThicknessAndSpeedUi()
            }
            .setNeutralButton(R.string.handwriting_reset_default) { _, _ ->
                AppPrefs.getInstance().handwriting.handWritingWidth.setValue(35)
                updateThicknessAndSpeedUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
    }

    private fun showSpeedDialog() {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_handwriting_speed_picker, null)
        val tvVal = dialogView.findViewById<TextView>(R.id.tv_dialog_speed_val)
        val seekbar = dialogView.findViewById<SeekBar>(R.id.seekbar_speed)

        val currentSpeed = AppPrefs.getInstance().handwriting.handWritingSpeed.getValue().coerceIn(300, 1300)
        seekbar.max = 1000
        seekbar.progress = currentSpeed - 300
        val defSuffix = getString(R.string.handwriting_default_suffix)
        tvVal.text = if (currentSpeed == 500) {
            "${getString(R.string.handwriting_unit_ms, currentSpeed)} ($defSuffix)"
        } else {
            getString(R.string.handwriting_unit_ms, currentSpeed)
        }

        var selectedSpeed = currentSpeed

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress + 300
                selectedSpeed = speed
                tvVal.text = if (speed == 500) {
                    "${getString(R.string.handwriting_unit_ms, speed)} ($defSuffix)"
                } else {
                    getString(R.string.handwriting_unit_ms, speed)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton(R.string.voice_btn_confirm) { _, _ ->
                AppPrefs.getInstance().handwriting.handWritingSpeed.setValue(selectedSpeed)
                updateThicknessAndSpeedUi()
            }
            .setNeutralButton(R.string.handwriting_reset_default) { _, _ ->
                AppPrefs.getInstance().handwriting.handWritingSpeed.setValue(500)
                updateThicknessAndSpeedUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteHwModel() {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.voice_delete_model_title)
            .setMessage(R.string.handwriting_delete_model_message)
            .setPositiveButton(R.string.voice_delete_model_confirm) { _, _ ->
                AiPackageInstaller.uninstallPackage(context, "handwriting")
                AiModuleManager.invalidateCache()
                updateModelCardUi()
                Toast.makeText(context, getString(R.string.handwriting_delete_model_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownloadHwModel(force: Boolean = false) {
        val context = requireContext()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.hasNotificationPermission(context)) {
            org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.requestNotificationPermission(context)
        }
        val density = context.resources.displayMetrics.density
        val padding = (density * 20).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val tvStatus = TextView(context).apply {
            text = getString(R.string.handwriting_connecting_title)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.settings_primary_text))
        }
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (density * 6).toInt().coerceAtLeast(1)
            ).apply {
                topMargin = (density * 16).toInt()
                bottomMargin = (density * 10).toInt()
            }
            layoutParams = lp
        }
        val tvSpeed = TextView(context).apply {
            text = ""
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.settings_secondary_text))
        }
        layout.addView(tvStatus)
        layout.addView(progressBar)
        layout.addView(tvSpeed)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.handwriting_download_title)
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton(R.string.handwriting_btn_background, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                AiDownloadManager.cancelDownload(context)
            }
            .create()

        dialog.show()

        AiDownloadManager.downloadHandwriting(
            context = context,
            force = force,
            onProgress = { percent, speedDesc, stepDesc ->
                progressBar.progress = percent
                if (speedDesc.isNotEmpty()) {
                    tvStatus.text = getString(R.string.download_status_progress, stepDesc, percent)
                    tvSpeed.visibility = View.VISIBLE
                    tvSpeed.text = speedDesc
                } else {
                    tvStatus.text = stepDesc
                    tvSpeed.visibility = View.GONE
                }
            },
            onComplete = { success, errorMsg ->
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
                updateModelCardUi()
                if (success) {
                    Toast.makeText(context, getString(R.string.handwriting_download_success), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, errorMsg ?: getString(R.string.ai_download_error_generic), Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showTechSpecsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handwriting_tech_specs_title)
            .setMessage(R.string.handwriting_tech_specs_content)
            .setPositiveButton(R.string.voice_btn_got_it, null)
            .show()
    }

    private fun getMirrorName(index: Int): String {
        return when (index) {
            1 -> getString(R.string.handwriting_mirror_github)
            2 -> getString(R.string.handwriting_mirror_ghproxy)
            else -> getString(R.string.handwriting_mirror_auto)
        }
    }

    private fun getSanitizedMirrorIndex(): Int {
        val raw = try {
            AppPrefs.getInstance().handwriting.mirrorSource.getValue()
        } catch (_: Throwable) {
            0
        }
        return if (raw in 0..2) raw else 0
    }

    private fun updateMirrorSummary() {
        tvHwCurrentMirror.text = getMirrorName(getSanitizedMirrorIndex())
    }

    private fun showMirrorsDialog() {
        val mirrors = arrayOf(
            getString(R.string.handwriting_mirror_auto),
            getString(R.string.handwriting_mirror_github),
            getString(R.string.handwriting_mirror_ghproxy)
        )

        val currentSelected = getSanitizedMirrorIndex()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handwriting_mirror_source)
            .setSingleChoiceItems(mirrors, currentSelected) { dialog, which ->
                AppPrefs.getInstance().handwriting.mirrorSource.setValue(which)
                updateMirrorSummary()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
    }
}