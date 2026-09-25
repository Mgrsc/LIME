package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bitfennec.lime.R
import org.bitfennec.lime.core.runtime.AiBlobDownloader
import org.bitfennec.lime.core.runtime.AiPackageInstaller
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.inputmethod.voice.VoiceModelManager
import org.bitfennec.lime.inputmethod.voice.VoiceRecognitionEngine
import org.bitfennec.lime.utils.dp

/**
 * Offline voice model download and onboarding card container (Material 3).
 */
@SuppressLint("ViewConstructor")
class VoiceDownloadContainer(
    context: Context,
    inputView: InputView
) : BaseContainer(context, inputView) {

    private val rootLayout: LinearLayout
    private val progressBar: ProgressBar
    private val tvStatus: TextView
    private val tvSpeed: TextView
    private val btnDownload: Button
    private var containerScope: CoroutineScope? = null
    private var wasDownloading = false

    init {
        val activeTheme = ThemeManager.activeTheme
        val textColor = activeTheme.keyTextColor
        val accentColor = activeTheme.accentKeyBackgroundColor

        // 1. Root container
        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activeTheme.keyboardColor)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 2. Navigation bar (Height 40dp)
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(12)
            val padV = dp(6)
            setPadding(padH, padV, padH, padV)
            setBackgroundColor("#15000000".toColorInt())
        }

        // Back button
        val ivBack = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back_24)
            contentDescription = context.getString(R.string.voice_btn_cancel)
            setColorFilter(textColor)
            val size = dp(30)
            val pad = dp(5)
            setPadding(pad, pad, pad, pad)
            val backBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#1AFFFFFF".toColorInt())
            }
            background = backBg
            setOnClickListener {
                KeyboardManager.instance.switchKeyboard()
            }
        }
        headerLayout.addView(ivBack, LinearLayout.LayoutParams(dp(30), dp(30)))

        // Title
        val tvTitle = TextView(context).apply {
            text = context.getString(R.string.ime_settings_voice)
            textSize = 15f
            setTextColor(textColor)
            paint.isFakeBoldText = true
            setPadding(dp(10), 0, 0, 0)
        }
        headerLayout.addView(tvTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Right badge
        val tvBadge = TextView(context).apply {
            text = context.getString(R.string.voice_download_container_badge)
            textSize = 11f
            setTextColor(accentColor)
            val badgeBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor("#20FFFFFF".toColorInt())
                setStroke(dp(0.8f), accentColor)
            }
            background = badgeBg
            val padH = dp(8)
            val padV = dp(3)
            setPadding(padH, padV, padH, padV)
        }
        headerLayout.addView(tvBadge)

        rootLayout.addView(headerLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 3. Center card (adaptive height)
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = dp(16)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
            val cardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor("#1E232B".toColorInt())
                setStroke(dp(1), "#26FFFFFF".toColorInt())
            }
            background = cardBg
        }

        // Card header: microphone icon and model details
        val cardHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic_24)
            contentDescription = null
            setColorFilter(Color.WHITE)
            val size = dp(36)
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            val micBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(accentColor)
            }
            background = micBg
        }
        cardHeader.addView(micIcon, LinearLayout.LayoutParams(dp(36), dp(36)))

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val tvModelName = TextView(context).apply {
            text = context.getString(R.string.voice_download_container_model_title)
            textSize = 13.5f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
        }
        val tvModelSub = TextView(context).apply {
            text = context.getString(R.string.voice_download_container_model_sub)
            textSize = 11.5f
            setTextColor("#90A4AE".toColorInt())
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(tvModelName)
        infoCol.addView(tvModelSub)
        cardHeader.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        cardLayout.addView(cardHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Features tag list
        val tagsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            layoutParams = lp
        }

        val tags = listOf(
            context.getString(R.string.voice_download_container_tag_multilang),
            context.getString(R.string.voice_download_container_tag_punctuation),
            context.getString(R.string.voice_download_container_tag_offline)
        )
        for (tag in tags) {
            val tvTag = TextView(context).apply {
                text = tag
                textSize = 10.5f
                setTextColor("#B0BEC5".toColorInt())
                val tagBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor("#15FFFFFF".toColorInt())
                }
                background = tagBg
                val padH = dp(6)
                val padV = dp(2.5f)
                setPadding(padH, padV, padH, padV)
            }
            val tagLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            }
            tagsLayout.addView(tvTag, tagLp)
        }
        cardLayout.addView(tagsLayout)

        // Progress bar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4.5f)
            ).apply {
                bottomMargin = dp(5)
            }
            layoutParams = lp
        }
        cardLayout.addView(progressBar)

        // Status and live download speed
        val statusLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            layoutParams = lp
        }

        tvStatus = TextView(context).apply {
            text = context.getString(R.string.voice_status_ready)
            textSize = 11.5f
            setTextColor(accentColor)
            visibility = View.GONE
        }
        statusLayout.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        tvSpeed = TextView(context).apply {
            textSize = 11.5f
            setTextColor("#90A4AE".toColorInt())
            gravity = Gravity.END
            visibility = View.GONE
        }
        statusLayout.addView(tvSpeed, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        cardLayout.addView(statusLayout)

        // Download action button
        val btnDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(accentColor)
        }

        btnDownload = Button(context).apply {
            text = context.getString(R.string.voice_download_container_btn_download_format)
            setTextColor(Color.WHITE)
            textSize = 13.5f
            paint.isFakeBoldText = true
            background = btnDrawable
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
            )
            layoutParams = lp
            setOnClickListener {
                if (VoiceModelManager.isDownloading()) {
                    VoiceModelManager.cancelDownload(context)
                } else {
                    startDownload(force = true)
                }
            }
        }
        cardLayout.addView(btnDownload)

        // Add card to main layout with margins
        val cardLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val marginH = dp(14)
            val marginV = dp(8)
            setMargins(marginH, marginV, marginH, marginV)
        }
        rootLayout.addView(cardLayout, cardLp)

        addView(rootLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateStatusObserver()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateStatusObserver()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateStatusObserver()
    }

    private fun updateStatusObserver() {
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            if (containerScope == null) attachStatusObserver()
        } else {
            containerScope?.cancel()
            containerScope = null
        }
    }

    override fun onDetachedFromWindow() {
        containerScope?.cancel()
        containerScope = null
        super.onDetachedFromWindow()
    }

    private fun attachStatusObserver() {
        containerScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        containerScope = scope
        scope.launch {
            VoiceModelManager.getStatusFlow().collect { status ->
                when (status) {
                    is AiPackageInstaller.PackageStatus.Downloading -> {
                        wasDownloading = true
                        btnDownload.isEnabled = true
                        btnDownload.alpha = 1.0f
                        btnDownload.text = context.getString(R.string.voice_btn_stop)
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = status.percent
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = if (status.stageDesc.isNotEmpty()) status.stageDesc else status.artifactName
                        tvStatus.setTextColor(ThemeManager.activeTheme.keyTextColor)
                        if (status.stageDesc.isEmpty() && status.speedBps > 0) {
                            tvSpeed.visibility = View.VISIBLE
                            val speedStr = AiBlobDownloader.formatSpeed(status.speedBps)
                            tvSpeed.text = context.getString(R.string.download_status_progress, speedStr, status.percent)
                        } else {
                            tvSpeed.visibility = View.GONE
                        }
                    }
                    is AiPackageInstaller.PackageStatus.Ready -> {
                        btnDownload.isEnabled = false
                        btnDownload.alpha = 0.6f
                        progressBar.visibility = View.GONE
                        tvSpeed.visibility = View.GONE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = context.getString(R.string.voice_status_ready)
                        tvStatus.setTextColor("#00E676".toColorInt())
                        btnDownload.text = context.getString(R.string.voice_download_container_btn_ready)
                        if (wasDownloading) {
                            wasDownloading = false
                            Toast.makeText(context, context.getString(R.string.voice_download_container_toast_ready), Toast.LENGTH_SHORT).show()
                            VoiceRecognitionEngine.reload(context)
                            launch {
                                delay(1000)
                                if (isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
                                    KeyboardManager.instance.currentContainer === this@VoiceDownloadContainer
                                ) {
                                    KeyboardManager.instance.switchKeyboard()
                                }
                            }
                        }
                    }
                    is AiPackageInstaller.PackageStatus.Failed -> {
                        wasDownloading = false
                        btnDownload.isEnabled = true
                        btnDownload.alpha = 1.0f
                        btnDownload.text = context.getString(R.string.voice_btn_redownload)
                        progressBar.visibility = View.GONE
                        progressBar.progress = 0
                        tvSpeed.visibility = View.GONE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = status.error
                        tvStatus.setTextColor("#E53935".toColorInt())
                    }
                    is AiPackageInstaller.PackageStatus.Missing -> {
                        btnDownload.isEnabled = true
                        btnDownload.alpha = 1.0f
                        btnDownload.text = context.getString(R.string.voice_download_container_btn_download_format)
                        progressBar.visibility = View.GONE
                        progressBar.progress = 0
                        tvSpeed.visibility = View.GONE
                        if (wasDownloading) {
                            tvStatus.visibility = View.VISIBLE
                            wasDownloading = false
                            tvStatus.text = context.getString(R.string.voice_status_cancelled)
                            tvStatus.setTextColor(ThemeManager.activeTheme.keyTextColor)
                        } else {
                            tvStatus.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun startDownload(force: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.hasNotificationPermission(context)) {
            org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.requestNotificationPermission(context)
        }
        VoiceModelManager.startDownload(context, force = force)
    }
}
