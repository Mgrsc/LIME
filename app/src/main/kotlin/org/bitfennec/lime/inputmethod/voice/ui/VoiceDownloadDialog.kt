package org.bitfennec.lime.inputmethod.voice.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bitfennec.lime.R
import org.bitfennec.lime.core.runtime.AiBlobDownloader
import org.bitfennec.lime.core.runtime.AiPackageInstaller
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.service.ImeService
import org.bitfennec.lime.inputmethod.voice.VoiceModelManager
import org.bitfennec.lime.inputmethod.voice.VoiceRecognitionEngine

/**
 * Material 3 centered dialog for voice model download and onboarding.
 */
class VoiceDownloadDialog(
    context: Context,
    private val onDownloadCompleted: (() -> Unit)? = null
) : Dialog(context) {

    private var dialogScope: CoroutineScope? = null
    private val progressBar: ProgressBar
    private val tvStatus: TextView
    private val tvSpeed: TextView
    private val btnDownload: Button
    private val btnCancel: Button

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window?.let { win ->
            win.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            val lp = win.attributes
            val imeService = context as? ImeService
            val imeToken = imeService?.window?.window?.attributes?.token
            if (imeToken != null) {
                lp.token = imeToken
                lp.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
            }
            win.attributes = lp
            win.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.6f)
        }

        // Dynamically construct Material 3 dialog layout
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_bg)
            val padH = dp2px(24f).toInt()
            val padV = dp2px(20f).toInt()
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Title
        val tvTitle = TextView(context).apply {
            text = context.getString(R.string.voice_download_dialog_title)
            textSize = 18f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        rootLayout.addView(tvTitle)

        // Description and model size
        val tvDesc = TextView(context).apply {
            text = context.getString(R.string.voice_download_dialog_desc)
            textSize = 13.5f
            setTextColor("#CFD8DC".toColorInt())
            setLineSpacing(dp2px(3f), 1f)
            val pad = dp2px(14f).toInt()
            setPadding(0, pad, 0, pad)
        }
        rootLayout.addView(tvDesc)

        // Progress bar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(6f).toInt()
            ).apply {
                bottomMargin = dp2px(8f).toInt()
            }
            layoutParams = lp
        }
        rootLayout.addView(progressBar)

        // Status and download speed indicator
        val statusContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp2px(14f).toInt()
            }
            layoutParams = lp
        }

        tvStatus = TextView(context).apply {
            text = context.getString(R.string.voice_status_ready)
            textSize = 12.5f
            setTextColor(ThemeManager.activeTheme.accentKeyBackgroundColor)
            visibility = View.GONE
        }
        statusContainer.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        tvSpeed = TextView(context).apply {
            textSize = 12.5f
            setTextColor("#90A4AE".toColorInt())
            gravity = Gravity.END
            visibility = View.GONE
        }
        statusContainer.addView(tvSpeed, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        rootLayout.addView(statusContainer)

        // Bottom action buttons
        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp2px(8f).toInt()
            }
            layoutParams = lp
        }

        btnCancel = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = context.getString(R.string.voice_btn_close)
            setTextColor("#90A4AE".toColorInt())
            textSize = 13.5f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            val padH = dp2px(14f).toInt()
            val padV = dp2px(8f).toInt()
            setPadding(padH, padV, padH, padV)
            val cancelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(18f)
                setColor(Color.TRANSPARENT)
            }
            background = cancelBg
            setOnClickListener {
                dismiss()
            }
        }
        btnBar.addView(btnCancel)

        val spacer = View(context)
        btnBar.addView(spacer, LinearLayout.LayoutParams(dp2px(12f).toInt(), 1))

        val accentColor = ThemeManager.activeTheme.accentKeyBackgroundColor
        val normalBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(18f)
            setColor(accentColor)
        }
        val disabledBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(18f)
            val alphaColor = Color.argb(128, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            setColor(alphaColor)
        }
        val btnStateBg = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabledBtnBg)
            addState(intArrayOf(), normalBtnBg)
        }

        btnDownload = Button(context).apply {
            text = context.getString(R.string.voice_btn_download_now)
            setTextColor(Color.WHITE)
            textSize = 13.5f
            paint.isFakeBoldText = true
            background = btnStateBg
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            val padH = dp2px(18f).toInt()
            val padV = dp2px(8f).toInt()
            setPadding(padH, padV, padH, padV)
            isSingleLine = true
            maxLines = 1
            setOnClickListener {
                if (VoiceModelManager.isDownloading()) {
                    VoiceModelManager.cancelDownload(context)
                } else {
                    startDownloadFlow(force = true)
                }
            }
        }
        btnBar.addView(btnDownload)

        rootLayout.addView(btnBar)
        setContentView(rootLayout)

        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        setCanceledOnTouchOutside(false)
    }

    override fun onStart() {
        super.onStart()
        dialogScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        dialogScope = scope
        scope.launch {
            var wasDownloading = false
            VoiceModelManager.getStatusFlow().collect { status ->
                when (status) {
                    is AiPackageInstaller.PackageStatus.Downloading -> {
                        wasDownloading = true
                        btnDownload.isEnabled = true
                        btnDownload.text = context.getString(R.string.voice_btn_stop)
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = status.percent
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = if (status.stageDesc.isNotEmpty()) status.stageDesc else status.artifactName
                        tvStatus.setTextColor(ThemeManager.activeTheme.accentKeyBackgroundColor)
                        if (status.stageDesc.isEmpty() && status.speedBps > 0) {
                            tvSpeed.visibility = View.VISIBLE
                            tvSpeed.text = AiBlobDownloader.formatSpeed(status.speedBps)
                        } else {
                            tvSpeed.visibility = View.GONE
                        }
                    }
                    is AiPackageInstaller.PackageStatus.Ready -> {
                        if (wasDownloading) {
                            btnDownload.isEnabled = false
                            progressBar.visibility = View.GONE
                            tvSpeed.visibility = View.GONE
                            VoiceRecognitionEngine.reload(context) {
                                onDownloadCompleted?.invoke()
                            }
                            dismiss()
                        } else {
                            btnDownload.isEnabled = true
                            btnDownload.text = context.getString(R.string.voice_btn_redownload)
                            progressBar.visibility = View.GONE
                            tvSpeed.visibility = View.GONE
                            tvStatus.visibility = View.VISIBLE
                            tvStatus.text = context.getString(R.string.voice_status_ready)
                            tvStatus.setTextColor(ThemeManager.activeTheme.accentKeyBackgroundColor)
                        }
                    }
                    is AiPackageInstaller.PackageStatus.Failed -> {
                        btnDownload.isEnabled = true
                        btnDownload.text = context.getString(R.string.voice_btn_redownload)
                        progressBar.visibility = View.GONE
                        tvSpeed.visibility = View.GONE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = status.error
                        tvStatus.setTextColor("#E53935".toColorInt())
                    }
                    is AiPackageInstaller.PackageStatus.Missing -> {
                        btnDownload.isEnabled = true
                        btnDownload.text = context.getString(R.string.voice_btn_download_now)
                        progressBar.visibility = View.GONE
                        tvSpeed.visibility = View.GONE
                        if (wasDownloading) {
                            tvStatus.visibility = View.VISIBLE
                            tvStatus.text = context.getString(R.string.voice_status_cancelled)
                            tvStatus.setTextColor(ThemeManager.activeTheme.accentKeyBackgroundColor)
                        } else {
                            tvStatus.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun startDownloadFlow(force: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !VoicePermissionActivity.hasNotificationPermission(context)) {
            VoicePermissionActivity.requestNotificationPermission(context)
        }
        VoiceModelManager.startDownload(context, force = force)
    }

    private fun dp2px(dp: Float): Float {
        return context.resources.displayMetrics.density * dp
    }

    override fun onStop() {
        dialogScope?.cancel()
        dialogScope = null
        super.onStop()
    }
}
