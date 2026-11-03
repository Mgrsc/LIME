package org.bitfennec.lime.inputmethod.voice.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withScale
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.inputmethod.voice.AudioRecordHelper
import org.bitfennec.lime.utils.DevicesUtils
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device voice interaction overlay view.
 * Implements planar scrim, discrete floating targets, vertical gate, hysteresis touch rejection, and long-speech lock.
 */
class VoiceInputOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class VoiceActionState {
        NORMAL, // Default recording (release to send)
        CANCEL, // In cancel zone (release to cancel)
        LOCK    // In lock zone (release to lock recording)
    }

    private var currentState = VoiceActionState.NORMAL
    var isLocked = false
        private set

    var onLockFinishListener: (() -> Unit)? = null
    var onToggleModeListener: (() -> Unit)? = null

    val visualizerView = WaveformVisualizerView(context)

    // Real-time transcription cache
    private var liveTranscribeText: String = ""
    private var isEnginePreparing: Boolean = false
    private var isRecognizing: Boolean = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bgPath = Path()
    private val bgRect = RectF()

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val hintTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val anchorBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val lockBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val vectorIconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val modeBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var overlayLayout = VoiceOverlayLayout.calculate(242f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        overlayLayout = VoiceOverlayLayout.calculate(h / resources.displayMetrics.density)
        updateControlBounds()
        val waveformBottom = min(overlayLayout.targetTop, overlayLayout.lockedStatusBaseline - 16f) - 4f
        val waveformHeight = 24f
        visualizerView.visibility = if (waveformBottom - overlayLayout.waveformTop >= waveformHeight) VISIBLE else INVISIBLE
        visualizerView.layoutParams = LayoutParams(min(dp2px(180f).toInt(), w), dp2px(waveformHeight).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp2px(overlayLayout.waveformTop).toInt()
        }
    }

    private fun updateControlBounds() {
        val w = width.toFloat()
        val margin = min(dp2px(28f), w * 0.08f)
        val pillWidth = min(dp2px(80f), (w - margin * 3f) / 2f)
        val pillTop = dp2px(overlayLayout.targetTop)
        val pillHeight = dp2px(VoiceOverlayLayout.TARGET_HEIGHT_DP)
        leftPillRect.set(margin, pillTop, margin + pillWidth, pillTop + pillHeight)
        rightPillRect.set(w - margin - pillWidth, pillTop, w - margin, pillTop + pillHeight)
        val finishWidth = min(dp2px(140f), (w - dp2px(16f)).coerceAtLeast(0f))
        val finishTop = dp2px(overlayLayout.finishTop)
        lockFinishRect.set((w - finishWidth) / 2f, finishTop, (w + finishWidth) / 2f,
            finishTop + dp2px(VoiceOverlayLayout.FINISH_HEIGHT_DP))
        val modeBaseline = dp2px(if (isLocked) overlayLayout.lockedModeBaseline else overlayLayout.modeBaseline)
        val modeHalfWidth = min(dp2px(70f), w / 2f)
        modeBadgeRect.set(w / 2f - modeHalfWidth, modeBaseline - dp2px(VoiceOverlayLayout.MODE_HALF_HEIGHT_DP),
            w / 2f + modeHalfWidth, modeBaseline + dp2px(VoiceOverlayLayout.MODE_HALF_HEIGHT_DP))
    }

    private fun drawFittedText(canvas: Canvas, text: String, cx: Float, baseline: Float, paint: TextPaint,
        availableWidth: Float = width - dp2px(32f), truncateAt: TextUtils.TruncateAt = TextUtils.TruncateAt.END) {
        val fitted = TextUtils.ellipsize(text, paint, availableWidth.coerceAtLeast(0f), truncateAt)
        canvas.drawText(fitted.toString(), cx, baseline, paint)
    }

    private var captureMode = AudioRecordHelper.VoiceCaptureMode.STANDARD

    fun setCaptureMode(mode: AudioRecordHelper.VoiceCaptureMode) {
        if (captureMode != mode) {
            captureMode = mode
            invalidate()
        }
    }

    // Reusable RectF objects
    private val leftPillRect = RectF()
    private val rightPillRect = RectF()
    private val lockFinishRect = RectF()
    private val anchorRect = RectF()
    private val iconBodyRect = RectF()
    private val iconShackleRect = RectF()

    // Target coordinates and animation scales
    private var leftScale = 1.0f
    private var targetLeftScale = 1.0f
    private var rightScale = 1.0f
    private var targetRightScale = 1.0f

    // Initial touch origin
    private var originX = 0f
    private var originY = 0f
    private var hasOrigin = false

    // Localized string cache
    private var strHoldToSpeak: String = ""
    private var strReleaseToSend: String = ""
    private var strReleaseToCancel: String = ""
    private var strReleaseToLock: String = ""
    private var strCancel: String = ""
    private var strLock: String = ""
    private var strLiveTranscribing: String = ""
    private var strListeningHint: String = ""
    private var strFinish: String = ""
    private var strEnginePreparing: String = ""
    private var strModeStandard: String = ""
    private var strModeWhisper: String = ""

    init {
        setWillNotDraw(false)
        isClickable = true
        anchorBoxPaint.strokeWidth = dp2px(1f)

        refreshStrings()

        // Top dynamic waveform visualizer (Y: 28dp, Height: 24dp)
        val waveParams = LayoutParams(
            dp2px(180f).toInt(),
            dp2px(24f).toInt()
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp2px(28f).toInt()
        }
        addView(visualizerView, waveParams)
    }

    fun refreshStrings() {
        try {
            strHoldToSpeak = context.getString(R.string.voice_overlay_hold_to_speak)
            strReleaseToSend = context.getString(R.string.voice_overlay_release_to_send)
            strReleaseToCancel = context.getString(R.string.voice_overlay_release_to_cancel)
            strReleaseToLock = context.getString(R.string.voice_overlay_release_to_lock)
            strCancel = context.getString(R.string.voice_overlay_cancel)
            strLock = context.getString(R.string.voice_overlay_lock)
            strLiveTranscribing = context.getString(R.string.voice_overlay_live_transcribing)
            strListeningHint = context.getString(R.string.voice_overlay_listening_hint)
            strFinish = context.getString(R.string.voice_overlay_finish)
            strEnginePreparing = context.getString(R.string.voice_overlay_engine_preparing)
            strModeStandard = context.getString(R.string.voice_capture_standard)
            strModeWhisper = context.getString(R.string.voice_capture_whisper)
        } catch (_: Throwable) {
            strHoldToSpeak = "Hold to speak"
            strReleaseToSend = "Release to finish"
            strReleaseToCancel = "Release to cancel"
            strReleaseToLock = "Release to lock"
            strCancel = "Cancel"
            strLock = "Lock"
            strLiveTranscribing = "● Transcribing..."
            strListeningHint = "Speak now..."
            strFinish = "Done"
            strEnginePreparing = "Preparing engine; you can speak..."
            strModeStandard = "Standard capture"
            strModeWhisper = "Whisper · enhanced"
        }
    }

    fun setEnginePreparing(preparing: Boolean) {
        isEnginePreparing = preparing
        if (preparing) {
            isRecognizing = false
        }
        invalidate()
    }

    fun setRecognizing(recognizing: Boolean) {
        isRecognizing = recognizing
        if (recognizing) {
            isEnginePreparing = false
        }
        invalidate()
    }

    fun setTouchOriginFromScreen(screenX: Float, screenY: Float) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        originX = screenX - loc[0]
        originY = screenY - loc[1]
        hasOrigin = true
    }

    fun updateTouchPositionFromScreen(screenX: Float, screenY: Float): VoiceActionState {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val relX = screenX - loc[0]
        val relY = screenY - loc[1]
        return updateTouchPosition(relX, relY)
    }

    fun getCurrentActionState(): VoiceActionState = currentState

    fun enterLockMode() {
        isLocked = true
        currentState = VoiceActionState.LOCK
        updateControlBounds()
        invalidate()
    }

    fun updateLiveText(text: String) {
        liveTranscribeText = text
        if (text.isNotEmpty()) {
            isEnginePreparing = false
        }
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isLocked) return false
        updateControlBounds()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (lockFinishRect.contains(event.x, event.y)) {
                    DevicesUtils.tryVibrate(this)
                    onLockFinishListener?.invoke()
                    return true
                } else if (modeBadgeRect.contains(event.x, event.y)) {
                    DevicesUtils.tryVibrate(this)
                    onToggleModeListener?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val isNight = ThemeManager.activeTheme.isDark

        // 1. Draw translucent background scrim
        bgPath.reset()
        bgRect.set(0f, 0f, w, h)
        bgPath.addRect(bgRect, Path.Direction.CW)
        bgPaint.color = if (isNight) "#E6121316".toColorInt() else "#F2F5F7FA".toColorInt()
        canvas.drawPath(bgPath, bgPaint)

        if (isLocked) {
            drawLockModeView(canvas, w, h, isNight)
            return
        }

        // 2. Compute animation interpolations
        val animSpeed = 0.22f
        leftScale += (targetLeftScale - leftScale) * animSpeed
        rightScale += (targetRightScale - rightScale) * animSpeed
        if (Math.abs(targetLeftScale - leftScale) > 0.005f || Math.abs(targetRightScale - rightScale) > 0.005f) {
            postInvalidateOnAnimation()
        }

        val pillH = dp2px(VoiceOverlayLayout.TARGET_HEIGHT_DP)

        // 3. Left target: Cancel capsule
        val leftActive = currentState == VoiceActionState.CANCEL

        canvas.withScale(leftScale, leftScale, leftPillRect.centerX(), leftPillRect.centerY()) {
            pillBgPaint.color = when {
                leftActive -> "#FF453A".toColorInt()
                isNight -> "#26FFFFFF".toColorInt()
                else -> "#14000000".toColorInt()
            }
            canvas.drawRoundRect(leftPillRect, pillH / 2f, pillH / 2f, pillBgPaint)

            val iconSize = dp2px(14f)
            val iconCx = leftPillRect.left + dp2px(22f)
            val iconCy = leftPillRect.centerY()
            val iconColor = if (leftActive) Color.WHITE else if (isNight) "#B3FFFFFF".toColorInt() else "#80000000".toColorInt()
            drawVectorCloseIcon(canvas, iconCx, iconCy, iconSize, iconColor)

            textPaint.color = if (leftActive) Color.WHITE else if (isNight) "#E6FFFFFF".toColorInt() else "#B3000000".toColorInt()
            textPaint.textSize = dp2px(13f)
            val textX = leftPillRect.left + dp2px(48f)
            val textY = leftPillRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(strCancel, textX, textY, textPaint)
        }

        // 4. Right target: Lock capsule
        val rightActive = currentState == VoiceActionState.LOCK

        canvas.withScale(rightScale, rightScale, rightPillRect.centerX(), rightPillRect.centerY()) {
            pillBgPaint.color = when {
                rightActive -> "#0A84FF".toColorInt()
                isNight -> "#26FFFFFF".toColorInt()
                else -> "#14000000".toColorInt()
            }
            canvas.drawRoundRect(rightPillRect, pillH / 2f, pillH / 2f, pillBgPaint)

            val lockIconCx = rightPillRect.left + dp2px(22f)
            val lockIconCy = rightPillRect.centerY()
            val lockIconColor = if (rightActive) Color.WHITE else if (isNight) "#B3FFFFFF".toColorInt() else "#80000000".toColorInt()
            drawVectorLockIcon(canvas, lockIconCx, lockIconCy, dp2px(15f), lockIconColor)

            textPaint.color = if (rightActive) Color.WHITE else if (isNight) "#E6FFFFFF".toColorInt() else "#B3000000".toColorInt()
            textPaint.textSize = dp2px(13f)
            val lockTextX = rightPillRect.left + dp2px(48f)
            val lockTextY = rightPillRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(strLock, lockTextX, lockTextY, textPaint)
        }

        // 5. Center hint and live preview
        hintTextPaint.textSize = dp2px(14f)
        val hintY = dp2px(overlayLayout.hintBaseline)
        if (isRecognizing) {
            hintTextPaint.color = if (isNight) "#0A84FF".toColorInt() else "#007AFF".toColorInt()
            drawFittedText(canvas, strLiveTranscribing, w / 2f, hintY, hintTextPaint)
        } else if (isEnginePreparing) {
            hintTextPaint.color = if (isNight) "#FF9500".toColorInt() else "#FF8D00".toColorInt()
            drawFittedText(canvas, strEnginePreparing, w / 2f, hintY, hintTextPaint)
        } else if (liveTranscribeText.isNotEmpty() && currentState == VoiceActionState.NORMAL) {
            textPaint.textSize = dp2px(15f)
            textPaint.color = if (isNight) Color.WHITE else "#1C1C1E".toColorInt()
            drawFittedText(canvas, liveTranscribeText, w / 2f, dp2px(overlayLayout.previewBaseline), textPaint,
                truncateAt = TextUtils.TruncateAt.START)

            hintTextPaint.textSize = dp2px(12f)
            hintTextPaint.color = if (isNight) "#80FFFFFF".toColorInt() else "#80000000".toColorInt()
            drawFittedText(canvas, strReleaseToSend, w / 2f, hintY + dp2px(6f), hintTextPaint)

            drawModeBadge(canvas, w / 2f, dp2px(overlayLayout.modeBaseline), isNight)
        } else {
            when (currentState) {
                VoiceActionState.CANCEL -> {
                    hintTextPaint.color = "#FF453A".toColorInt()
                    drawFittedText(canvas, strReleaseToCancel, w / 2f, hintY, hintTextPaint)
                }
                VoiceActionState.LOCK -> {
                    hintTextPaint.color = if (isNight) "#0A84FF".toColorInt() else "#007AFF".toColorInt()
                    drawFittedText(canvas, strReleaseToLock, w / 2f, hintY, hintTextPaint)
                }
                VoiceActionState.NORMAL -> {
                    hintTextPaint.color = if (isNight) "#A6FFFFFF".toColorInt() else "#99000000".toColorInt()
                    drawFittedText(canvas, strReleaseToSend, w / 2f, hintY, hintTextPaint)

                    drawModeBadge(canvas, w / 2f, dp2px(overlayLayout.modeBaseline), isNight)
                }
            }
        }

        val anchorW = min(dp2px(200f), w - dp2px(16f))
        val anchorH = dp2px(overlayLayout.anchorHeight)
        val anchorY = dp2px(overlayLayout.anchorTop)
        anchorRect.set((w - anchorW) / 2f, anchorY, (w + anchorW) / 2f, anchorY + anchorH)
        anchorBoxPaint.color = if (isNight) "#1FFFFFFF".toColorInt() else "#14000000".toColorInt()
        canvas.drawRoundRect(anchorRect, dp2px(12f), dp2px(12f), anchorBoxPaint)

        hintTextPaint.textSize = dp2px(12f)
        hintTextPaint.color = if (isNight) "#4DFFFFFF".toColorInt() else "#4D000000".toColorInt()
        val anchorTextY = anchorRect.centerY() - ((hintTextPaint.descent() + hintTextPaint.ascent()) / 2)
        drawFittedText(canvas, strHoldToSpeak, anchorRect.centerX(), anchorTextY, hintTextPaint, anchorRect.width() - dp2px(8f))
    }

    private val modeBadgeRect = RectF()

    private fun drawModeBadge(canvas: Canvas, cx: Float, cy: Float, isNight: Boolean) {
        val modeText = when (captureMode) {
            AudioRecordHelper.VoiceCaptureMode.STANDARD -> strModeStandard
            AudioRecordHelper.VoiceCaptureMode.WHISPER -> strModeWhisper
        }
        val isWhisper = captureMode == AudioRecordHelper.VoiceCaptureMode.WHISPER

        modeBadgePaint.textSize = dp2px(11f)
        modeBadgePaint.color = when {
            isWhisper -> if (isNight) "#64D2FF".toColorInt() else "#0071A4".toColorInt()
            else -> if (isNight) "#66FFFFFF".toColorInt() else "#66000000".toColorInt()
        }
        drawFittedText(canvas, modeText, cx, cy, modeBadgePaint, modeBadgeRect.width())
    }

    /** Renders locked long-speech recording mode. */
    private fun drawLockModeView(canvas: Canvas, w: Float, h: Float, isNight: Boolean) {
        // 1. Top hint and live transcription preview
        hintTextPaint.textSize = dp2px(14f)
        hintTextPaint.color = if (isNight) "#0A84FF".toColorInt() else "#007AFF".toColorInt()
        drawFittedText(canvas, strLiveTranscribing, w / 2f, dp2px(overlayLayout.lockedStatusBaseline), hintTextPaint)

        // Real-time transcription display
        if (liveTranscribeText.isNotEmpty()) {
            textPaint.textSize = dp2px(15f)
            textPaint.color = if (isNight) Color.WHITE else "#1C1C1E".toColorInt()
            drawFittedText(canvas, liveTranscribeText, w / 2f, dp2px(overlayLayout.lockedPreviewBaseline), textPaint,
                truncateAt = TextUtils.TruncateAt.START)
        } else {
            hintTextPaint.textSize = dp2px(13f)
            hintTextPaint.color = if (isNight) "#66FFFFFF".toColorInt() else "#66000000".toColorInt()
            drawFittedText(canvas, strListeningHint, w / 2f, dp2px(overlayLayout.lockedPreviewBaseline), hintTextPaint)
        }

        drawModeBadge(canvas, w / 2f, dp2px(overlayLayout.lockedModeBaseline), isNight)


        lockBtnPaint.color = if (isNight) "#0A84FF".toColorInt() else "#007AFF".toColorInt()
        canvas.drawRoundRect(lockFinishRect, dp2px(22f), dp2px(22f), lockBtnPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = dp2px(15f)
        val textY = lockFinishRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(strFinish, lockFinishRect.centerX(), textY, textPaint)
    }

    /**
     * Gesture discrimination and hit testing:
     * 1. Vertical gate: actions active above bottomGateY;
     * 2. Tri-zone layout: left 36% CANCEL, right 36% LOCK, center 28% NORMAL;
     * 3. Hysteresis: smooth exit when sliding back to bottom.
     */
    fun updateTouchPosition(x: Float, y: Float): VoiceActionState {
        if (isLocked) return VoiceActionState.NORMAL
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return VoiceActionState.NORMAL

        updateControlBounds()
        val leftTargetCenterX = leftPillRect.centerX()
        val leftTargetCenterY = leftPillRect.centerY()
        val distToLeftTarget = sqrt(((x - leftTargetCenterX) * (x - leftTargetCenterX) + (y - leftTargetCenterY) * (y - leftTargetCenterY)).toDouble()).toFloat()
        val rightTargetCenterX = rightPillRect.centerX()
        val rightTargetCenterY = rightPillRect.centerY()
        val distToRightTarget = sqrt(((x - rightTargetCenterX) * (x - rightTargetCenterX) + (y - rightTargetCenterY) * (y - rightTargetCenterY)).toDouble()).toFloat()

        // Bottom touch gate: adapts to view height (default 140dp, max 65% height in landscape)
        val bottomGateY = min(dp2px(140f), h * 0.65f)

        when (currentState) {
            VoiceActionState.CANCEL -> {
                // Hysteresis exit: slid back to bottom gate or moved rightward
                if (y > bottomGateY + dp2px(16f) || x > w * 0.44f) {
                    setHoverState(VoiceActionState.NORMAL)
                }
            }
            VoiceActionState.LOCK -> {
                // Hysteresis exit: slid back to bottom gate or moved leftward
                if (y > bottomGateY + dp2px(16f) || x < w * 0.56f) {
                    setHoverState(VoiceActionState.NORMAL)
                }
            }
            VoiceActionState.NORMAL -> {
                if (y <= bottomGateY) {
                    if (x <= w * 0.36f || distToLeftTarget <= dp2px(60f)) {
                        setHoverState(VoiceActionState.CANCEL)
                    } else if (x >= w * 0.64f || distToRightTarget <= dp2px(60f)) {
                        setHoverState(VoiceActionState.LOCK)
                    }
                }
            }
        }

        return currentState
    }

    private fun setHoverState(newState: VoiceActionState) {
        if (currentState != newState) {
            currentState = newState
            when (newState) {
                VoiceActionState.CANCEL -> {
                    targetLeftScale = 1.15f
                    targetRightScale = 1.0f
                    DevicesUtils.tryVibrate(this, HapticEvent.STEP)
                }
                VoiceActionState.LOCK -> {
                    targetRightScale = 1.15f
                    targetLeftScale = 1.0f
                    DevicesUtils.tryVibrate(this, HapticEvent.STEP)
                }
                VoiceActionState.NORMAL -> {
                    targetLeftScale = 1.0f
                    targetRightScale = 1.0f
                }
            }
            invalidate()
        }
    }

    fun show() {
        isLocked = false
        hasOrigin = false
        isEnginePreparing = false
        isRecognizing = false
        currentState = VoiceActionState.NORMAL
        captureMode = AudioRecordHelper.VoiceCaptureMode.STANDARD
        liveTranscribeText = ""
        targetLeftScale = 1.0f
        targetRightScale = 1.0f
        leftScale = 1.0f
        rightScale = 1.0f
        visualizerView.reset()
        updateControlBounds()
        visibility = VISIBLE
        invalidate()
    }

    fun dismiss() {
        isLocked = false
        visibility = GONE
        liveTranscribeText = ""
        isEnginePreparing = false
        isRecognizing = false
        visualizerView.reset()
    }

    private fun drawVectorCloseIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        vectorIconPaint.reset()
        vectorIconPaint.isAntiAlias = true
        vectorIconPaint.color = color
        vectorIconPaint.style = Paint.Style.STROKE
        vectorIconPaint.strokeWidth = dp2px(1.6f)
        vectorIconPaint.strokeCap = Paint.Cap.ROUND

        val half = size * 0.36f
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, vectorIconPaint)
        canvas.drawLine(cx - half, cy + half, cx + half, cy - half, vectorIconPaint)
    }

    private fun drawVectorLockIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        vectorIconPaint.reset()
        vectorIconPaint.isAntiAlias = true
        vectorIconPaint.color = color

        val bodyW = size * 0.85f
        val bodyH = size * 0.58f
        val bodyRadius = dp2px(2.2f)
        val bodyTop = cy - bodyH / 2f + size * 0.16f
        iconBodyRect.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)

        // 1. Draw lock body
        vectorIconPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(iconBodyRect, bodyRadius, bodyRadius, vectorIconPaint)

        // 2. Draw lock shackle
        vectorIconPaint.style = Paint.Style.STROKE
        vectorIconPaint.strokeWidth = dp2px(1.5f)
        vectorIconPaint.strokeCap = Paint.Cap.ROUND
        val shackleW = size * 0.52f
        val shackleH = size * 0.50f
        val shackleTop = cy - size * 0.44f
        iconShackleRect.set(cx - shackleW / 2f, shackleTop, cx + shackleW / 2f, shackleTop + shackleH)
        canvas.drawArc(iconShackleRect, 180f, 180f, false, vectorIconPaint)
    }

    private fun dp2px(dp: Float): Float {
        return context.resources.displayMetrics.density * dp
    }
}
