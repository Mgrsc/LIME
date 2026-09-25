package org.bitfennec.lime.ui.fragment

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.bitfennec.lime.R
import org.bitfennec.lime.core.runtime.AiBlobDownloader
import org.bitfennec.lime.core.runtime.AiPackageInstaller.PackageStatus
import org.bitfennec.lime.inputmethod.voice.VoiceModelManager
import org.bitfennec.lime.inputmethod.voice.VoiceRecognitionEngine
import org.bitfennec.lime.inputmethod.voice.ui.VoiceDownloadDialog
import org.bitfennec.lime.prefs.AppPrefs

/**
 * Modern card-style voice settings page.
 */
class VoiceSettingsFragment : Fragment() {

    private lateinit var switchRemovePunctuation: SwitchCompat
    private lateinit var tvModelBadge: TextView
    private lateinit var tvModelDesc: TextView
    private lateinit var btnDeleteModel: TextView
    private lateinit var btnDownloadModel: TextView
    private lateinit var tvCurrentMirror: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_voice_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchRemovePunctuation = view.findViewById(R.id.switch_remove_punctuation)
        val pref = AppPrefs.getInstance().voice.removeTrailingPunctuation
        switchRemovePunctuation.isChecked = pref.getValue()
        switchRemovePunctuation.setOnCheckedChangeListener { _, isChecked ->
            pref.setValue(isChecked)
        }
        view.findViewById<View>(R.id.row_remove_punctuation)?.setOnClickListener {
            switchRemovePunctuation.toggle()
        }

        tvModelBadge = view.findViewById(R.id.tv_model_badge)
        tvModelDesc = view.findViewById(R.id.tv_model_desc)
        btnDeleteModel = view.findViewById(R.id.btn_delete_model)
        btnDownloadModel = view.findViewById(R.id.btn_download_model)

        btnDownloadModel.setOnClickListener {
            openDownloadDialog()
        }

        btnDeleteModel.setOnClickListener {
            showDeleteConfirmDialog()
        }

        view.findViewById<View>(R.id.row_tech_specs)?.setOnClickListener {
            showTechSpecsDialog()
        }

        tvCurrentMirror = view.findViewById(R.id.tv_current_mirror)
        view.findViewById<View>(R.id.row_mirrors)?.setOnClickListener {
            showMirrorsDialog()
        }

        updateMirrorSummary()
        updateModelCardUi()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceModelManager.getStatusFlow().collect { status ->
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
                            tvModelBadge.text = getString(R.string.voice_model_downloading, status.percent)
                            tvModelBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.settings_accent_color))
                            tvModelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.settings_chip_badge_bg))
                            tvModelDesc.text = stageInfo
                            btnDeleteModel.visibility = View.GONE
                            btnDownloadModel.text = getString(R.string.voice_btn_view_progress)
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
        if (!VoiceModelManager.isDownloading()) {
            updateModelCardUi()
        }
    }

    private fun getMirrorName(index: Int): String {
        return when (index) {
            1 -> getString(R.string.voice_mirror_modelscope)
            2 -> getString(R.string.voice_mirror_hf_mirror)
            3 -> getString(R.string.voice_mirror_huggingface)
            else -> getString(R.string.voice_mirror_auto)
        }
    }

    private fun updateMirrorSummary() {
        val index = try {
            AppPrefs.getInstance().voice.mirrorSource.getValue()
        } catch (_: Throwable) {
            0
        }
        tvCurrentMirror.text = getMirrorName(index)
    }

    private fun updateModelCardUi() {
        val context = context ?: return
        if (VoiceModelManager.isDownloading()) {
            return
        }
        val isReady = VoiceModelManager.isModelReady(context)

        if (isReady) {
            val sizeMb = VoiceModelManager.getModelFile(context).length() / 1_000_000L
            tvModelBadge.text = getString(R.string.voice_model_ready)
            tvModelBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.settings_badge_active_text))
            tvModelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.settings_badge_active_bg))
            tvModelDesc.text = getString(R.string.voice_model_desc_ready, sizeMb)
            btnDeleteModel.visibility = View.VISIBLE
            btnDownloadModel.text = getString(R.string.voice_btn_redownload)
        } else {
            tvModelBadge.text = getString(R.string.voice_model_not_ready)
            tvModelBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.settings_badge_warn_text))
            tvModelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.settings_badge_warn_bg))
            tvModelDesc.text = getString(R.string.voice_model_desc_not_ready)
            btnDeleteModel.visibility = View.GONE
            btnDownloadModel.text = getString(R.string.voice_btn_download_now)
        }
    }

    private fun showDeleteConfirmDialog() {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.voice_delete_model_title)
            .setMessage(R.string.voice_delete_model_message)
            .setPositiveButton(R.string.voice_delete_model_confirm) { _, _ ->
                VoiceModelManager.deleteModel(context)
                VoiceRecognitionEngine.release()
                updateModelCardUi()
                Toast.makeText(context, R.string.voice_model_deleted_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.voice_btn_cancel, null)
            .show()
    }

    private fun openDownloadDialog() {
        val context = requireContext()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.hasNotificationPermission(context)) {
            org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.requestNotificationPermission(context)
        }
        val dialog = VoiceDownloadDialog(context) {
            VoiceRecognitionEngine.init(context)
            updateModelCardUi()
        }
        dialog.show()
    }

    private fun showTechSpecsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_tech_specs_title)
            .setMessage(R.string.voice_tech_specs_content)
            .setPositiveButton(R.string.voice_btn_got_it, null)
            .show()
    }

    private fun showMirrorsDialog() {
        val mirrors = arrayOf(
            getString(R.string.voice_mirror_auto),
            getString(R.string.voice_mirror_modelscope),
            getString(R.string.voice_mirror_hf_mirror),
            getString(R.string.voice_mirror_huggingface)
        )

        val currentSelected = try {
            AppPrefs.getInstance().voice.mirrorSource.getValue().coerceIn(0, 3)
        } catch (_: Throwable) {
            0
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_mirror_source)
            .setSingleChoiceItems(mirrors, currentSelected) { dialog, which ->
                AppPrefs.getInstance().voice.mirrorSource.setValue(which)
                updateMirrorSummary()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.voice_btn_cancel, null)
            .show()
    }
}
