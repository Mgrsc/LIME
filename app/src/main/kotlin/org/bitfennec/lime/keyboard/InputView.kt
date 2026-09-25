package org.bitfennec.lime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.postDelayed
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.R
import org.bitfennec.lime.candidate.CandidateViewListener
import org.bitfennec.lime.data.emojicon.EmojiconData.SymbolPreset
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.entity.StringQueue
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.keyboard.container.CandidatesContainer
import org.bitfennec.lime.keyboard.container.HandwritingContainer
import org.bitfennec.lime.keyboard.container.SymbolContainer
import org.bitfennec.lime.keyboard.container.T9TextContainer
import org.bitfennec.lime.keyboard.container.TextEditContainer
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs.Companion.getInstance
import org.bitfennec.lime.prefs.behavior.KeyboardOneHandedMod
import org.bitfennec.lime.prefs.behavior.PopupMenuMode
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import android.widget.Toast
import org.bitfennec.lime.service.DecodingInfo
import org.bitfennec.lime.service.ImeService
import org.bitfennec.lime.inputmethod.EngineAction
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.inputmethod.predict.CandidateStripMode
import org.bitfennec.lime.inputmethod.voice.AudioRecordHelper
import org.bitfennec.lime.inputmethod.voice.AsrWorker
import org.bitfennec.lime.inputmethod.voice.VoiceRecognitionEngine
import org.bitfennec.lime.inputmethod.voice.ui.VoiceInputOverlayView
import org.bitfennec.lime.keyboard.ui.HeightAdjustBar
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.InputMethodUtil
import org.bitfennec.lime.utils.KeyEventUtils
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.bitfennec.lime.view.CandidatesBar
import org.bitfennec.lime.view.popup.PopupComponent
import org.bitfennec.lime.view.preference.ManagedPreference
import org.bitfennec.lime.utils.collectWhenStarted
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.core.HandwritingEngine
import org.bitfennec.lime.core.runtime.AiModuleManager
import org.bitfennec.lime.inputmethod.calc.CalcResult
import org.bitfennec.lime.inputmethod.calc.NumberCalcEngine
import org.bitfennec.lime.utils.bottomPadding
import org.bitfennec.lime.utils.rightPadding
import kotlin.math.absoluteValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Main IME view containing composition display, candidate bar, and keyboard layouts.
 */
@SuppressLint("ViewConstructor")
class InputView(context: Context, val service: ImeService) : RelativeLayout(context), KeyEventListener {
    private val appPrefs = getInstance()
    private var currentCalcSuccess: CalcResult.Success? = null
    private val mChoiceNotifier = ChoiceNotifier()
    var mSkbRoot: RelativeLayout
    var mSkbCandidatesBarView: CandidatesBar
    private var mHoderLayoutLeft: LinearLayout
    private var mHoderLayoutRight: LinearLayout
    private lateinit var mOnehandHoderLayout: LinearLayout
    private var mLlKeyboardBottomHolder: LinearLayout
    private var mInputKeyboardContainer: RelativeLayout
    private lateinit var mRightPaddingKey: ManagedPreference.PInt
    private lateinit var mBottomPaddingKey: ManagedPreference.PInt
    // Record deleted content (delete gestures only, 5 layers safety buffer)
    private val textBeforeCursors = StringQueue(5)
    private var isUndoing = false
    private val audioRecordHelper = AudioRecordHelper()
    private var mVoiceOverlay: VoiceInputOverlayView? = null
    private var softHandwritingKeyboard: HandwritingKeyboard? = null
    private var stylusHandwritingKeyboard: HandwritingKeyboard? = null
    val handwritingKeyboard: HandwritingKeyboard?
        get() = stylusHandwritingKeyboard ?: softHandwritingKeyboard
    private var mHeightAdjustBar: HeightAdjustBar? = null
    private var imeLayoutSnapshot = ImeLayoutSnapshot.Empty
    private val geomGeneration = AtomicLong(0L)

    init {
        initNavbarBackground(service)
        InputModeSwitcher.reset()
        mSkbRoot = LayoutInflater.from(context).inflate(R.layout.skb_container, this, false) as RelativeLayout
        addView(mSkbRoot)
        mSkbRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateImeLayoutSnapshot()
        }
        mSkbCandidatesBarView = mSkbRoot.findViewById(R.id.candidates_bar)
        mHoderLayoutLeft = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_left)
        mHoderLayoutRight = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_right)
        mInputKeyboardContainer = mSkbRoot.findViewById(R.id.ll_input_keyboard_container)
        mLlKeyboardBottomHolder = mSkbRoot.findViewById(R.id.iv_keyboard_holder)
        KeyboardManager.instance.setData(mSkbRoot.findViewById(R.id.skb_input_keyboard_view), this)
        PopupComponent.get().root.let { root ->
            root.parent?.let { (it as ViewGroup).removeView(root) }
            addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                addRule(ALIGN_BOTTOM, mSkbRoot.id)
                addRule(ALIGN_LEFT, mSkbRoot.id)
                addRule(ALIGN_RIGHT, mSkbRoot.id)
            })
        }
        mVoiceOverlay = VoiceInputOverlayView(context).apply {
            visibility = GONE
            onLockFinishListener = {
                onVoiceEnd()
            }
            onToggleModeListener = {
                val next = audioRecordHelper.toggleMode()
                setCaptureMode(next)
            }
        }
        val overlayLp = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply {
            addRule(ALIGN_TOP, mSkbRoot.id)
            addRule(ALIGN_BOTTOM, mSkbRoot.id)
            addRule(ALIGN_LEFT, mSkbRoot.id)
            addRule(ALIGN_RIGHT, mSkbRoot.id)
        }
        addView(mVoiceOverlay, overlayLp)

        mHeightAdjustBar = HeightAdjustBar(context).apply {
            attachInputView(this@InputView)
            visibility = GONE
            onDismissListener = {
                updateImeLayoutSnapshot(forceNewId = true)
                updateCandidateBar()
            }
        }
        val heightBarLp = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(ALIGN_TOP, mSkbRoot.id)
            addRule(ALIGN_LEFT, mSkbRoot.id)
            addRule(ALIGN_RIGHT, mSkbRoot.id)
        }
        addView(mHeightAdjustBar, heightBarLp)

        service.collectWhenStarted(DecodingInfo.candidatesFlow) { cands ->
            mSkbCandidatesBarView.showCandidates(skipUnchanged = true)
            if (cands.isEmpty()) {
                if (KeyboardManager.instance.currentContainer is CandidatesContainer) {
                    KeyboardManager.instance.switchKeyboard()
                }
            } else {
                (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
            }
            (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
        }
        service.collectWhenStarted(EnginePipeline.stateFlow) {
            mSkbCandidatesBarView.showCandidates(skipUnchanged = true)
        }
        initView(context)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            updateImeLayoutSnapshot(forceNewId = oldw > 0 || oldh > 0)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView(context: Context) {
        mSkbCandidatesBarView.initialize(mChoiceNotifier)
        val env = ImeEnvironment
        val keyboardSetting = appPrefs.keyboardSetting
        val oneHandedModSwitch = keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = keyboardSetting.oneHandedMod.getValue()
        mHoderLayoutLeft.visibility = GONE
        mHoderLayoutRight.visibility = GONE
        if (::mOnehandHoderLayout.isInitialized) mOnehandHoderLayout.visibility = GONE
        if (oneHandedModSwitch) {
            mOnehandHoderLayout = if (oneHandedMod == KeyboardOneHandedMod.LEFT) mHoderLayoutRight else mHoderLayoutLeft
            mOnehandHoderLayout.apply {
                visibility = VISIBLE
                get(0).setOnClickListener { onClick(it) }
                get(1).setOnClickListener { onClick(it) }
                (get(1) as ImageButton).setImageResource(
                    if (oneHandedMod == KeyboardOneHandedMod.LEFT) R.drawable.ic_menu_one_hand_right else R.drawable.ic_menu_one_hand
                )
                layoutParams = layoutParams.apply {
                    width = env.holderWidth
                    height = env.skbHeight
                }
            }
        }
        mLlKeyboardBottomHolder.removeAllViews()
        mInputKeyboardContainer.layoutParams.width = env.inputAreaWidth
        if (env.keyboardModeFloat) {
            val isLand = env.isLandscape
            val internal = appPrefs.internal
            mBottomPaddingKey = if (isLand) internal.keyboardBottomPaddingLandscapeFloat else internal.keyboardBottomPaddingFloat
            mRightPaddingKey = if (isLand) internal.keyboardRightPaddingLandscapeFloat else internal.keyboardRightPaddingFloat

            val minPadding = DevicesUtils.dip2px(8)
            val parentW = if (width > 0) width else env.screenWidth
            val parentH = if (height > 0) height else env.screenHeight
            val maxRight = (parentW - env.skbWidth - minPadding).coerceAtLeast(minPadding)
            val maxBottom = (parentH - env.skbAreaHeight - minPadding).coerceAtLeast(minPadding)

            val savedRight = mRightPaddingKey.getValue()
            val initialRight = if (savedRight < 0) {
                ((parentW - env.skbWidth) / 2).coerceIn(minPadding, maxRight)
            } else {
                savedRight.coerceIn(minPadding, maxRight)
            }
            rightPadding = initialRight
            bottomPadding = mBottomPaddingKey.getValue().coerceIn(minPadding, maxBottom)
            mSkbRoot.bottomPadding = 0

            val activeTheme = ThemeManager.activeTheme
            val handleColor = Color.argb(200, Color.red(activeTheme.keyTextColor), Color.green(activeTheme.keyTextColor), Color.blue(activeTheme.keyTextColor))

            mLlKeyboardBottomHolder.visibility = VISIBLE
            mLlKeyboardBottomHolder.layoutParams = RelativeLayout.LayoutParams(env.skbWidth, env.heightForKeyboardMove).apply {
                addRule(RelativeLayout.BELOW, R.id.ll_input_keyboard_container)
                addRule(RelativeLayout.CENTER_HORIZONTAL)
            }
            mLlKeyboardBottomHolder.minimumHeight = env.heightForKeyboardMove
            mLlKeyboardBottomHolder.gravity = Gravity.CENTER
            mLlKeyboardBottomHolder.setOnTouchListener { _, event -> onMoveKeyboardEvent(event) }

            val pillDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = DevicesUtils.dip2px(3).toFloat()
                setColor(handleColor)
            }
            val mIvKeyboardMove = View(context).apply {
                background = pillDrawable
                isClickable = true
                isEnabled = true
                val pillWidth = DevicesUtils.dip2px(56)
                val pillHeight = DevicesUtils.dip2px(5)
                layoutParams = LinearLayout.LayoutParams(pillWidth, pillHeight).apply {
                    gravity = Gravity.CENTER
                }
            }
            mLlKeyboardBottomHolder.addView(mIvKeyboardMove)
            mLlKeyboardBottomHolder.requestLayout()
        } else {
            mLlKeyboardBottomHolder.setOnTouchListener(null)
            val initialBottom = if (env.systemNavbarWindowsBottom > 0) {
                env.systemNavbarWindowsBottom
            } else if (!env.isLandscape) {
                DevicesUtils.dip2px(8)
            } else {
                0
            }
            mLlKeyboardBottomHolder.visibility = if (initialBottom > 0) VISIBLE else GONE
            mLlKeyboardBottomHolder.layoutParams = RelativeLayout.LayoutParams(env.skbWidth, initialBottom).apply {
                addRule(RelativeLayout.BELOW, R.id.ll_input_keyboard_container)
                addRule(RelativeLayout.CENTER_HORIZONTAL)
            }
            bottomPadding = 0
            rightPadding = 0
            mBottomPaddingKey = appPrefs.internal.keyboardBottomPaddingFloat
            mRightPaddingKey = appPrefs.internal.keyboardRightPadding
            mSkbRoot.bottomPadding = 0
            mSkbRoot.rightPadding = mRightPaddingKey.getValue()
        }
        updateTheme()
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var rightPaddingValue = 0
    private var bottomPaddingValue = 0
    private var mSkbRootHeight = 0
    private var mSkbRootWidth = 0

    private fun onMoveKeyboardEvent(event: MotionEvent?): Boolean {
        if (!ImeEnvironment.keyboardModeFloat) return false
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                val env = ImeEnvironment
                val isLand = env.isLandscape
                val internal = appPrefs.internal
                mBottomPaddingKey = if (isLand) internal.keyboardBottomPaddingLandscapeFloat else internal.keyboardBottomPaddingFloat
                mRightPaddingKey = if (isLand) internal.keyboardRightPaddingLandscapeFloat else internal.keyboardRightPaddingFloat

                val parentW = if (width > 0) width else env.screenWidth
                val parentH = if (height > 0) height else env.screenHeight
                mSkbRootHeight = if (mSkbRoot.height > 0) mSkbRoot.height else env.skbAreaHeight
                mSkbRootWidth = if (mSkbRoot.width > 0) mSkbRoot.width else env.skbWidth
                val minPadding = DevicesUtils.dip2px(8)
                val maxRight = (parentW - mSkbRootWidth - minPadding).coerceAtLeast(minPadding)
                val maxBottom = (parentH - mSkbRootHeight - minPadding).coerceAtLeast(minPadding)

                val savedRight = mRightPaddingKey.getValue()
                rightPaddingValue = if (savedRight < 0) ((parentW - mSkbRootWidth) / 2).coerceIn(minPadding, maxRight) else savedRight.coerceIn(minPadding, maxRight)
                bottomPaddingValue = mBottomPaddingKey.getValue().coerceIn(minPadding, maxBottom)
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val env = ImeEnvironment
                val parentW = if (width > 0) width else env.screenWidth
                val parentH = if (height > 0) height else env.screenHeight
                val minPadding = DevicesUtils.dip2px(8)
                val maxRight = (parentW - mSkbRootWidth - minPadding).coerceAtLeast(minPadding)
                val maxBottom = (parentH - mSkbRootHeight - minPadding).coerceAtLeast(minPadding)

                if (dx.absoluteValue > 3) {
                    rightPaddingValue = (rightPaddingValue - dx.toInt()).coerceIn(minPadding, maxRight)
                    initialTouchX = event.rawX
                    rightPadding = rightPaddingValue
                }
                if (dy.absoluteValue > 3) {
                    bottomPaddingValue = (bottomPaddingValue - dy.toInt()).coerceIn(minPadding, maxBottom)
                    initialTouchY = event.rawY
                    bottomPadding = bottomPaddingValue
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mRightPaddingKey.setValue(rightPaddingValue)
                mBottomPaddingKey.setValue(bottomPaddingValue)
            }
        }
        return false
    }

    fun updateTheme() {
        setBackgroundResource(android.R.color.transparent)
        val activeTheme = ThemeManager.activeTheme
        val keyTextColor = activeTheme.keyTextColor
        val env = ImeEnvironment

        val background = activeTheme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
        if (env.keyboardModeFloat) {
            val cornerRadius = DevicesUtils.dip2px(12).toFloat()
            val strokeColor = Color.argb(45, Color.red(keyTextColor), Color.green(keyTextColor), Color.blue(keyTextColor))
            val cardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(activeTheme.keyboardColor)
                setStroke(DevicesUtils.dip2px(1f), strokeColor)
            }
            mSkbRoot.background = cardBg
            mSkbRoot.clipToOutline = true
            val handleColor = Color.argb(160, Color.red(keyTextColor), Color.green(keyTextColor), Color.blue(keyTextColor))
            for (i in 0 until mLlKeyboardBottomHolder.childCount) {
                val child = mLlKeyboardBottomHolder.getChildAt(i)
                (child.background as? GradientDrawable)?.setColor(handleColor)
                (child as? ImageView)?.drawable?.setTint(handleColor)
            }
        } else if (background is BitmapDrawable) {
            val scaledBitmap = background.bitmap.scale(env.skbWidth, env.inputAreaHeight)
            mSkbRoot.background = scaledBitmap.toDrawable(context.resources).apply {
                colorFilter = background.colorFilter
            }
            mSkbRoot.clipToOutline = false
        } else {
            mSkbRoot.background = background
            mSkbRoot.clipToOutline = false
        }
        mSkbCandidatesBarView.updateTheme(keyTextColor)
        if (::mOnehandHoderLayout.isInitialized) {
            (mOnehandHoderLayout[0] as ImageButton).drawable?.setTint(keyTextColor)
            (mOnehandHoderLayout[1] as ImageButton).drawable?.setTint(keyTextColor)
        }
        updateNavbarTheme()
    }

    private fun onClick(view: View) {
        val keyboardSetting = appPrefs.keyboardSetting
        if (view.id == R.id.ib_holder_one_hand_none) {
            keyboardSetting.oneHandedModSwitch.setValue(false)
        } else {
            val currentMod = keyboardSetting.oneHandedMod.getValue()
            keyboardSetting.oneHandedMod.setValue(if (currentMod == KeyboardOneHandedMod.LEFT) KeyboardOneHandedMod.RIGHT else KeyboardOneHandedMod.LEFT)
        }
        ImeEnvironment.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        post { requestApplyInsets() }
    }

    override fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>) {
        val (mode, value) = result
        when (mode) {
            PopupMenuMode.Text -> commitSymbolText(value)
            PopupMenuMode.SwitchIME -> {
                if (!service.switchToNextIme()) {
                    InputMethodUtil.showPicker()
                }
            }
            PopupMenuMode.EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            PopupMenuMode.EnglishCell -> {
                val pref = appPrefs.input.abcSearchEnglishCell
                pref.setValue(!pref.getValue())
                KeyboardManager.instance.switchKeyboard()
            }
            PopupMenuMode.Clear -> {
                service.getTextBeforeCursor(1000).takeIf { it.isNotEmpty() }?.let {
                    textBeforeCursors.push(it)
                    service.deleteSurroundingText(1000)
                }
            }
            PopupMenuMode.Revertl -> {
                textBeforeCursors.popInReverseOrder()?.takeIf { it.isNotEmpty() }?.let { restored ->
                    isUndoing = true
                    try {
                        commitText(restored)
                    } finally {
                        isUndoing = false
                    }
                }
            }
            PopupMenuMode.Enter -> commitText("\n")
            else -> {}
        }
        if (mode == PopupMenuMode.Clear) resetToIdleState()
    }

    override fun responseFlickCaps(sKey: SoftKey) {
        val upperCode = sKey.code
        if (upperCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return
        val upperChar = ('A'.code + (upperCode - KeyEvent.KEYCODE_A)).toChar().toString()
        if (DecodingInfo.hasExternalCandidateSource) {
            commitText(upperChar)
            return
        }
        val event = KeyEventUtils.createSoftKeyEvent(
            action = KeyEvent.ACTION_UP,
            keyCode = upperCode,
            metaState = KeyEvent.META_SHIFT_ON
        )
        processKeyUp(event)
    }

    override fun responseHandwritingResultEvent(modalSessionId: Long, words: Array<CandidateListItem>) {
        service.publishHandwritingCandidates(modalSessionId, words)
    }

    override fun currentHandwritingModalSessionId(): Long? =
        service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING)

    override fun discardHandwritingCandidates(): Boolean {
        val discardedServiceCandidates = service.discardHandwritingCandidates()
        val hadStrokes = handwritingKeyboard?.hasStrokes() == true
        if (discardedServiceCandidates || hadStrokes) {
            handwritingKeyboard?.cancelPendingRecognition()
            return true
        }
        return false
    }

    fun endHandwritingSession() {
        stylusHandwritingKeyboard?.cancelPendingRecognition()
        softHandwritingKeyboard?.cancelPendingRecognition()
        service.cancelModalSession(ImeService.ModalInputMode.HANDWRITING)
    }

    fun prepareHandwritingSession(): Boolean {
        if (!AiModuleManager.isHandwritingReady(context)) {
            Toast.makeText(context, context.getString(R.string.handwriting_module_download_required), Toast.LENGTH_SHORT).show()
            return false
        }
        HandwritingEngine.cancelIdleUnload()
        val accepted = service.requestModalSession(
            mode = ImeService.ModalInputMode.HANDWRITING,
            onReady = {
                HandwritingEngine.init(context)
                handwritingKeyboard?.onModalSessionReady()
            },
            onCancelled = {
                stylusHandwritingKeyboard?.cancelPendingRecognition()
                softHandwritingKeyboard?.cancelPendingRecognition()
            },
        )
        return accepted
    }

    fun registerHandwritingKeyboard(keyboard: HandwritingKeyboard) {
        if (keyboard.isSystemStylus) {
            stylusHandwritingKeyboard = keyboard
        } else {
            softHandwritingKeyboard = keyboard
        }
        keyboard.onModalSessionReady()
    }

    fun unregisterHandwritingKeyboard(keyboard: HandwritingKeyboard) {
        if (stylusHandwritingKeyboard === keyboard) stylusHandwritingKeyboard = null
        if (softHandwritingKeyboard === keyboard) softHandwritingKeyboard = null
        softHandwritingKeyboard?.onModalSessionReady()
    }

    fun restoreHandwritingKeyboard() {
        if (softHandwritingKeyboard == null) {
            val container = KeyboardManager.instance.currentContainer as? HandwritingContainer
            container?.handwritingKeyboard?.let { registerHandwritingKeyboard(it) }
        }
        softHandwritingKeyboard?.onModalSessionReady()
    }

    fun startStylusHandwriting(): Boolean {
        InputModeSwitcher.enterTransientHandwritingMode()
        if (prepareHandwritingSession()) return true
        InputModeSwitcher.leaveTransientHandwritingMode()
        return false
    }

    @Volatile
    private var currentVoiceSessionId = 0L
    @Volatile
    private var isVoiceSessionActive = false
    @Volatile
    private var isVoiceStartPending = false
    private val voiceResultGeneration = AtomicLong(0)
    private val voiceLeaseHeld = AtomicBoolean(false)
    private val voiceStopRequested = AtomicBoolean(false)

    private fun invalidateVoiceSession() {
        voiceResultGeneration.incrementAndGet()
        isVoiceSessionActive = false
        asrWorker?.reset()
    }

    private fun releaseVoiceLeaseIfHeld() {
        if (voiceLeaseHeld.compareAndSet(true, false)) {
            VoiceRecognitionEngine.releaseLease()
        }
    }

    private val maxRecordingTimeoutRunnable = Runnable {
        if (isVoiceSessionActive) {
            Toast.makeText(context, context.getString(R.string.voice_overlay_max_duration_reached), Toast.LENGTH_SHORT).show()
            onVoiceEnd()
        }
    }

    private var streamingThread: Thread? = null
    @Volatile
    private var isStreamingActive = false
    private var asrWorker: AsrWorker? = null

    private fun startLiveStreaming(sessionId: Long) {
        isStreamingActive = true
        streamingThread = Thread({
            while (isStreamingActive && audioRecordHelper.isRecording() && sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
                if (!isStreamingActive || !audioRecordHelper.isRecording() || sessionId != currentVoiceSessionId || !isVoiceSessionActive) break

                if (VoiceRecognitionEngine.isReady()) {
                    try {
                        val snapshot = audioRecordHelper.getCurrentSentenceSnapshot()
                        if (snapshot.size >= 400 * (AudioRecordHelper.SAMPLE_RATE / 1000) &&
                            isStreamingActive && sessionId == currentVoiceSessionId && isVoiceSessionActive
                        ) {
                            asrWorker?.submitPreview(snapshot)
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("InputView", """{"event":"voice.preview_snapshot_error","error":"${e.message}"}""")
                    }
                }
            }
        }, "Voice-LiveStreaming-Thread").apply {
            start()
        }
    }

    private fun stopLiveStreaming() {
        isStreamingActive = false
        try {
            streamingThread?.interrupt()
        } catch (_: Throwable) {}
        streamingThread = null
    }

    private var accessibleVoiceStart = false

    fun startVoiceFromAccessibility(): Boolean {
        if (isVoiceSessionActive || isVoiceStartPending || InputModeSwitcher.isPrivateOrSensitive) return false
        accessibleVoiceStart = true
        val accepted = onVoiceStart(0f, 0f)
        if (!accepted) accessibleVoiceStart = false
        return accepted
    }

    override fun onVoiceStart(originScreenX: Float, originScreenY: Float): Boolean {
        if (!AiModuleManager.isVoiceReady(context)) {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.VOICE_DOWNLOAD)
            return false
        }

        if (!org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.hasPermission(context)) {
            org.bitfennec.lime.inputmethod.voice.ui.VoicePermissionActivity.requestPermission(context)
            return false
        }

        isVoiceStartPending = true
        val accepted = service.requestModalSession(
            mode = ImeService.ModalInputMode.VOICE,
            onReady = { sessionId ->
                if (isVoiceStartPending) startVoiceRecording(sessionId, originScreenX, originScreenY)
                else service.cancelModalSession(sessionId)
            },
            onCancelled = {
                post {
                    isVoiceStartPending = false
                    accessibleVoiceStart = false
                }
            },
        )
        if (!accepted) {
            isVoiceStartPending = false
        }
        return accepted
    }

    private fun startVoiceRecording(sessionId: Long, originScreenX: Float = 0f, originScreenY: Float = 0f) {
        val overlay = mVoiceOverlay ?: run {
            service.cancelModalSession(sessionId)
            return
        }
        if (voiceLeaseHeld.compareAndSet(false, true)) {
            VoiceRecognitionEngine.acquireLease()
        }
        currentVoiceSessionId = sessionId
        isVoiceStartPending = false
        voiceStopRequested.set(false)
        voiceResultGeneration.incrementAndGet()
        isVoiceSessionActive = true
        val resultGen = voiceResultGeneration.get()

        asrWorker = AsrWorker(
            onInterimResult = { interimText ->
                post {
                    if (resultGen != voiceResultGeneration.get()) return@post
                    if (sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                        mVoiceOverlay?.updateLiveText(interimText)
                    }
                }
            },
            onFinalResult = { finalText ->
                post {
                    // Do not gate on voiceResultGeneration: active_reused keeps the same
                    // sessionId, and bumping generation on restart would drop the previous
                    // utterance still decoding after overlay dismiss.
                    if (sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                        if (finalText.isNotEmpty()) {
                            val committed = service.commitModalText(sessionId, finalText)
                            android.util.Log.i(
                                "InputView",
                                """{"event":"voice.commit_final","session_id":$sessionId,"success":$committed,"text_length":${finalText.length}}"""
                            )
                            // May clear a newer session's interim preview when sessionId is reused;
                            // the next preview (~500ms) restores it.
                            mVoiceOverlay?.updateLiveText("")
                        } else {
                            Toast.makeText(context, R.string.voice_retry_hint, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )

        overlay.show()
        if (accessibleVoiceStart) overlay.enterLockMode()
        accessibleVoiceStart = false
        overlay.bringToFront()
        if (originScreenX > 0f && originScreenY > 0f) {
            overlay.setTouchOriginFromScreen(originScreenX, originScreenY)
        }
        if (!VoiceRecognitionEngine.isReady()) {
            overlay.setEnginePreparing(true)
            VoiceRecognitionEngine.init(context) { ready ->
                post {
                    if (ready && sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                        overlay.setEnginePreparing(false)
                    } else if (!ready && sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                        removeCallbacks(maxRecordingTimeoutRunnable)
                        stopLiveStreaming()
                        overlay.setEnginePreparing(false)
                        invalidateVoiceSession()
                        audioRecordHelper.abortRecording()
                        overlay.dismiss()
                        service.cancelModalSession(sessionId)
                        releaseVoiceLeaseIfHeld()
                        Toast.makeText(context, R.string.voice_engine_init_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val success = audioRecordHelper.startRecording(object : AudioRecordHelper.AudioCaptureListener {
            override fun onAmplitudeUpdate(amplitude: Float) {
                if (sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                    overlay.visualizerView.updateAmplitude(amplitude)
                }
            }

            override fun onSpeechStart() {
                post {
                    if (resultGen == voiceResultGeneration.get() && sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                        mVoiceOverlay?.updateLiveText("")
                    }
                }
            }

            override fun onSentenceFinal(samples: FloatArray) {
                if (resultGen == voiceResultGeneration.get() &&
                    sessionId == currentVoiceSessionId &&
                    isVoiceSessionActive
                ) {
                    asrWorker?.submitFinal(samples)
                }
            }

            override fun onSilenceTimeout() {
                if (sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                    onVoiceEnd()
                }
            }

            override fun onCaptureModeChanged(mode: AudioRecordHelper.VoiceCaptureMode) {
                post {
                    if (resultGen == voiceResultGeneration.get() && sessionId == currentVoiceSessionId && isVoiceSessionActive) {
                        mVoiceOverlay?.setCaptureMode(mode)
                    }
                }
            }
        })

        if (!success) {
            invalidateVoiceSession()
            overlay.dismiss()
            service.cancelModalSession(sessionId)
            releaseVoiceLeaseIfHeld()
            Toast.makeText(context, context.getString(R.string.voice_error_mic_permission), Toast.LENGTH_SHORT).show()
            return
        }

        removeCallbacks(maxRecordingTimeoutRunnable)
        postDelayed(maxRecordingTimeoutRunnable, 60000L) // 60s max recording guard

        DevicesUtils.tryVibrate(this, HapticEvent.LONG_PRESS)
        startLiveStreaming(sessionId)
    }

    override fun onVoiceMove(screenX: Float, screenY: Float) {
        if (isVoiceSessionActive) {
            mVoiceOverlay?.updateTouchPositionFromScreen(screenX, screenY)
        }
    }

    override fun onVoiceEnd() {
        if (!isVoiceSessionActive) {
            if (isVoiceStartPending) {
                isVoiceStartPending = false
                service.cancelModalSession(ImeService.ModalInputMode.VOICE)
            }
            return
        }
        removeCallbacks(maxRecordingTimeoutRunnable)
        val overlay = mVoiceOverlay ?: return
        val state = overlay.getCurrentActionState()
        val sessionId = currentVoiceSessionId

        if (state == VoiceInputOverlayView.VoiceActionState.LOCK && !overlay.isLocked) {
            DevicesUtils.tryVibrate(this)
            overlay.enterLockMode()
            overlay.bringToFront()
            return
        }

        stopLiveStreaming()

        when (state) {
            VoiceInputOverlayView.VoiceActionState.CANCEL -> {
                invalidateVoiceSession()
                audioRecordHelper.abortRecording()
                DevicesUtils.tryVibrate(this)
                overlay.dismiss()
                service.cancelModalSession(sessionId)
                releaseVoiceLeaseIfHeld()
            }
            VoiceInputOverlayView.VoiceActionState.NORMAL,
            VoiceInputOverlayView.VoiceActionState.LOCK -> {
                val isColdPreparing = !VoiceRecognitionEngine.isReady()
                if (isColdPreparing) {
                    overlay.setRecognizing(true)
                } else {
                    overlay.dismiss()
                }

                if (!voiceStopRequested.compareAndSet(false, true)) {
                    return
                }
                val resultGen = voiceResultGeneration.get()
                val worker = asrWorker
                audioRecordHelper.stopRecording { remainingSamples ->
                    post {
                        if (resultGen != voiceResultGeneration.get() || worker !== asrWorker) return@post
                        if (sessionId != currentVoiceSessionId || !isVoiceSessionActive) return@post
                        if (worker == null) {
                            overlay.dismiss()
                            service.cancelModalSession(sessionId)
                            invalidateVoiceSession()
                            releaseVoiceLeaseIfHeld()
                            return@post
                        }
                        worker.finishSession(remainingSamples) {
                            post {
                                if (resultGen != voiceResultGeneration.get()) return@post
                                if (sessionId == currentVoiceSessionId) {
                                    if (isColdPreparing) {
                                        overlay.dismiss()
                                    }
                                    service.cancelModalSession(sessionId)
                                    isVoiceSessionActive = false
                                    releaseVoiceLeaseIfHeld()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onVoiceCancel() {
        accessibleVoiceStart = false
        val sessionId = currentVoiceSessionId
        isVoiceStartPending = false
        invalidateVoiceSession()
        removeCallbacks(maxRecordingTimeoutRunnable)
        stopLiveStreaming()
        mVoiceOverlay?.dismiss()
        audioRecordHelper.abortRecording()
        service.cancelModalSession(sessionId)
        service.cancelModalSession(ImeService.ModalInputMode.VOICE)
        releaseVoiceLeaseIfHeld()
    }

    val isHandwritingActive: Boolean
        get() = InputModeSwitcher.isChineseHandWriting ||
            KeyboardManager.instance.currentContainer is HandwritingContainer

    private val isHandwritingMode: Boolean
        get() = isHandwritingActive || service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING)

    override fun responseKeyEvent(sKey: SoftKey) {
        asrWorker?.onUserKeyActivity()
        if (service.isModalModeActive(ImeService.ModalInputMode.VOICE)) return

        if (isHandwritingMode) {
            if (sKey.code == KeyEvent.KEYCODE_DEL) {
                if (!discardHandwritingCandidates()) {
                    sendKeyEvent(KeyEvent.KEYCODE_DEL)
                }
                return
            }
            if (sKey.isUserDefKey) {
                processUserDefKey(sKey.code, sKey.keyLabel)
                return
            }
            if (sKey.isUniStrKey) {
                sKey.label.takeIf(String::isNotEmpty)?.let {
                    commitSymbolText(it)
                }
                return
            }
            if (sKey.code == KeyEvent.KEYCODE_SPACE) {
                if (DecodingInfo.hasExternalCandidateSource) {
                    chooseAndUpdate()
                } else {
                    sendKeyEvent(KeyEvent.KEYCODE_SPACE)
                }
                return
            }
            if (sKey.code == KeyEvent.KEYCODE_ENTER) {
                if (DecodingInfo.hasExternalCandidateSource) {
                    chooseAndUpdate()
                } else {
                    sendKeyEvent(KeyEvent.KEYCODE_ENTER)
                }
                return
            }
        }

        val keyCode = sKey.code
        if(sKey.isUserDefKey)processUserDefKey(keyCode, sKey.keyLabel)
        else if(sKey.isUniStrKey){
            sKey.label.takeIf(String::isNotEmpty)?.let {
                commitSymbolText(it)
            }
        } else {
            val metaState = InputModeSwitcher.resolveSoftKeyMetaState()
            processKeyUp(KeyEventUtils.createSoftKeyEvent(action = KeyEvent.ACTION_UP, keyCode = keyCode, metaState = metaState))
        }
    }

    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (service.isModalModeActive(ImeService.ModalInputMode.VOICE)) {
            return keyCode != KeyEvent.KEYCODE_BACK
        }
        if (isHandwritingMode) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER -> true
                else -> false
            }
        }
        if (!service.isInputViewShown) return false
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> return true
        }
        return false
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        if (service.isModalModeActive(ImeService.ModalInputMode.VOICE)) {
            return event.keyCode != KeyEvent.KEYCODE_BACK
        }
        if (isHandwritingMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> return false
                KeyEvent.KEYCODE_DEL -> {
                    if (!discardHandwritingCandidates()) {
                        sendKeyEvent(KeyEvent.KEYCODE_DEL)
                    }
                    return true
                }
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (DecodingInfo.hasExternalCandidateSource) {
                        chooseAndUpdate()
                    } else {
                        sendKeyEvent(event.keyCode)
                    }
                    return true
                }
                else -> return false
            }
        }
        if (event.isSystem) return processSystemKeys(event)
        else if (isFunctionKey(event.keyCode)) {
            processFunctionKey(event)
            return true
        }
        if (InputModeSwitcher.isNumberSkb) {
            val keyCode = event.keyCode
            val keyChar = KeyEventUtils.getResolvedKeyChar(event)
            val label = if (keyChar > 0) keyChar.toChar().toString() else ""
            val result = when {
                keyCode == KeyEvent.KEYCODE_DEL -> {
                    sendKeyEvent(KeyEvent.KEYCODE_DEL)
                    true
                }
                label.isNotEmpty() -> {
                    commitText(label)
                    true
                }
                keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    commitText(('0'.code + (keyCode - KeyEvent.KEYCODE_0)).toChar().toString())
                    true
                }
                keyCode != 0 -> {
                    sendKeyEvent(keyCode)
                    true
                }
                else -> false
            }
            return result
        }
        val bypassRimeKeys = InputModeSwitcher.isPassword ||
            (InputModeSwitcher.isEnglish && !appPrefs.input.abcSearchEnglishCell.getValue())
        val result = when {
            bypassRimeKeys -> processEnglishKey(event)
            InputModeSwitcher.isEnglish || InputModeSwitcher.isChinese -> processInput(event)
            else -> processEnglishKey(event)
        }
        InputModeSwitcher.resetCharCase()
        return result
    }

    private fun processEnglishKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = KeyEventUtils.getResolvedKeyChar(event)
        val label = if (keyChar > 0) keyChar.toChar().toString() else ""
        var result = true
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                sendKeyEvent(keyCode)
            }
            keyCode in (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) -> {
                commitText(label.ifEmpty { ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString() })
            }
            keyCode != 0 -> sendKeyEvent(keyCode)
            label.isNotEmpty() -> if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            else -> result = false
        }
        return result
    }

    // Handle back key: dismiss keyboard and consume event if soft keyboard is showing
    private fun processSystemKeys(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (service.isInputViewShown) { requestHideSelf(); true } else false
            else -> false
        }
    }

    private fun isFunctionKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> return true
        }
        return false
    }

    private fun processFunctionKey(event: KeyEvent) {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
                    InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
                    resetToIdleState(resetEngine = false)
                    Toast.makeText(context, if (InputModeSwitcher.isEnglish) context.getString(R.string.ime_mode_english) else context.getString(R.string.ime_mode_pinyin), Toast.LENGTH_LONG).show()
                } else if (InputModeSwitcher.isNumberSkb) {
                    sendKeyEvent(KeyEvent.KEYCODE_SPACE)
                } else if (DecodingInfo.hasExternalCandidateSource) {
                    chooseAndUpdate()
                } else {
                    EnginePipeline.send(EngineAction.SpaceKey(gen = DecodingInfo.nextActionGeneration()))
                }
            }
            KeyEvent.KEYCODE_CLEAR -> resetToIdleState()
            KeyEvent.KEYCODE_ENTER -> {
                if (InputModeSwitcher.isNumberSkb) {
                    service.sendEnterKeyEvent()
                } else if (DecodingInfo.hasExternalCandidateSource) {
                    chooseAndUpdate()
                } else {
                    EnginePipeline.send(EngineAction.EnterKey(gen = DecodingInfo.nextActionGeneration()))
                }
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (InputModeSwitcher.isChinese) {
                    if (!DecodingInfo.isEngineFinish) processInput(KeyEventUtils.createSoftKeyEvent(keyCode = KeyEvent.KEYCODE_APOSTROPHE))
                } else {
                    InputModeSwitcher.processShiftKey(keyCode)
                }
            }
        }
    }


    private fun processUserDefKey(keyCode: Int, label: String) {
        when {
            keyCode == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> {
                resetToIdleState()
                return
            }
            label.isEmpty() && !DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty -> {
                if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
            }
        }

        when (keyCode) {
            InputModeSwitcher.USER_KEYCODE_SYMBOL -> {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setSymbolsView()
            }
            InputModeSwitcher.USER_KEYCODE_EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            in InputModeSwitcher.USER_KEYCODE_RETURN..InputModeSwitcher.USER_KEYCODE_LANG -> InputModeSwitcher.switchModeForUserKey(keyCode)
            else -> {
                if(label.isNotEmpty()){
                    commitSymbolText(label)
                }
            }
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = KeyEventUtils.getResolvedKeyChar(event)
        val label = if (keyChar > 0) keyChar.toChar().toString() else ""

        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                DecodingInfo.deleteAction()
                updateCandidateBar()
                true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!DecodingInfo.isEngineFinish) {
                    EnginePipeline.send(EngineAction.StepCompositionCursor(-1))
                } else {
                    sendKeyEvent(keyCode)
                }
                true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!DecodingInfo.isEngineFinish) {
                    EnginePipeline.send(EngineAction.StepCompositionCursor(1))
                } else {
                    sendKeyEvent(keyCode)
                }
                true
            }
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 ||
            keyCode == KeyEvent.KEYCODE_APOSTROPHE ||
            keyCode == KeyEvent.KEYCODE_SEMICOLON ||
            (keyChar > 0 && Character.isLetterOrDigit(keyChar) && keyCode != KeyEvent.KEYCODE_0) -> {
                textBeforeCursors.clear()
                DecodingInfo.inputAction(event)
                updateCandidateBar()
                true
            }
            keyCode != 0 -> {
                if (DecodingInfo.hasExternalCandidateSource) {
                    chooseAndUpdate()
                    sendKeyEvent(keyCode)
                } else {
                    EnginePipeline.send(EngineAction.CommitThenKey(keyCode))
                }
                true
            }
            label.isNotEmpty() -> {
                if (DecodingInfo.hasExternalCandidateSource) chooseAndUpdate()
                else {
                    val pair = SymbolPreset[label]?.takeIf { appPrefs.input.symbolPairInput.getValue() }
                    EnginePipeline.send(EngineAction.CommitThenText(
                        text = label + pair.orEmpty(),
                        moveCursorLeft = pair != null
                    ))
                }
                true
            }
            else -> false
        }
    }

    fun resetToIdleState(resetEngine: Boolean = true) {
        currentCalcSuccess = null
        resetCandidateWindow(resetEngine = resetEngine)
        mSkbCandidatesBarView.clearClipboardSuggestion()
    }

    fun clearCalcInterim() {
        currentCalcSuccess = null
        if (DecodingInfo.hasExternalCandidateSource) {
            resetCandidateWindow()
        }
    }

    fun chooseAndUpdate(candId: Int = mSkbCandidatesBarView.getActiveCandNo()) {
        if (InputModeSwitcher.isNumberSkb) {
            handleNumberCandidateChoice(candId)
            return
        }
        val selection = DecodingInfo.chooseDecodingCandidate(candId)
        if (selection.text.isNotEmpty()) {
            if (!isUndoing) {
                textBeforeCursors.clear()
            }
            val modalSessionId = selection.modalSessionId
            if (modalSessionId != null) {
                service.commitModalText(modalSessionId, selection.text)
                handwritingKeyboard?.clear()
            } else {
                commitDecInfoText(selection.text)
            }
        }
    }

    private fun handleNumberCandidateChoice(candId: Int) {
        val candidate = DecodingInfo.getCandidate(candId) ?: return
        if (candidate.comment == CalcResult.COMMENT_ERROR) {
            return
        }
        val success = currentCalcSuccess
        if (success != null) {
            when (candId) {
                0 -> {
                    // Slot 0: Click result -> delete preceding formula slice and retain numeric result
                    val deleteLen = success.deleteLength
                    service.deleteSurroundingText(deleteLen)
                    commitText(success.result)
                }
                1 -> {
                    // Slot 1: Click expression -> append "=result" after formula
                    val appendText = if (success.expression.endsWith("=")) success.result else "=${success.result}"
                    commitText(appendText)
                }
                else -> {
                    commitText(candidate.text)
                }
            }
            textBeforeCursors.clear()
            currentCalcSuccess = null
            resetCandidateWindow()
        } else {
            commitText(candidate.text)
            textBeforeCursors.clear()
            resetCandidateWindow()
        }
    }

    fun updateCandidateBar() = mSkbCandidatesBarView.scheduleShowCandidates()


    private fun resetCandidateWindow(resetEngine: Boolean = true) {
        DecodingInfo.reset(invalidateSession = false, resetEngine = resetEngine)
        (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
    }

    inner class ChoiceNotifier internal constructor() : CandidateViewListener {
        override fun onClickChoice(choiceId: Int) {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(KeyboardManager.instance.currentContainer)
            chooseAndUpdate(choiceId)
        }

        override fun onLongClickChoice(choiceId: Int) {
            if (DecodingInfo.canRemoveUserPrediction(choiceId)) {
                DecodingInfo.removeUserPrediction(choiceId)
            } else {
                DecodingInfo.deleteCandidate(choiceId)
            }
        }

        override fun onClickMore(level: Int) {
            if (level == 0) {
                onSettingsMenuClick(SkbMenuMode.CandidatesMore)
            } else {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
            }
        }

        override fun onClickMenu(skbMenuMode: SkbMenuMode) = onSettingsMenuClick(skbMenuMode)

        override fun onClickClearCandidate() {
            resetToIdleState()
            KeyboardManager.instance.switchKeyboard()
        }
    }

    fun onSettingsMenuClick(skbMenuMode: SkbMenuMode) {
        onSettingsMenuClick(this, skbMenuMode)
        mSkbCandidatesBarView.initMenuView()
    }

    fun selectPrefix(position: Int) {
        DevicesUtils.tryPlayKeyDown()
        DevicesUtils.tryVibrate(this)
        DecodingInfo.selectPrefix(position)
    }


    fun showClipboardSuggestion(content: String) {
        if (content.isBlank()) return
        mSkbCandidatesBarView.showClipboardSuggestion(content) {
            commitText(content)
        }
    }

    fun clearClipboardSuggestion() {
        mSkbCandidatesBarView.clearClipboardSuggestion()
    }

    fun requestHideSelf() = service.requestHideSelf(0)

    private fun sendKeyEvent(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
            else -> service.sendCombinationKeyEvents(keyCode)
        }
    }

    private fun commitText(text: String) {
        if (!isUndoing) {
            textBeforeCursors.clear()
        }
        service.commitText(text)
    }

    private fun commitPairSymbol(text: String) {
        if (!isUndoing) {
            textBeforeCursors.clear()
        }
        if (appPrefs.input.symbolPairInput.getValue() && !InputModeSwitcher.isEmailOrUri) {
            service.commitText(text + SymbolPreset[text]!!)
            postDelayed(300) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
        } else {
            service.commitText(InputModeSwitcher.normalizeEditorLiteral(text))
        }
    }

    private fun commitSymbolText(text: String) {
        if (InputModeSwitcher.isNumberSkb) {
            if (!isUndoing) {
                textBeforeCursors.clear()
            }
            service.commitText(InputModeSwitcher.normalizeEditorLiteral(text))
            return
        }
        if (DecodingInfo.hasExternalCandidateSource) {
            chooseAndUpdate()
            return
        }
        if (!isUndoing) {
            textBeforeCursors.clear()
        }
        val literal = InputModeSwitcher.normalizeEditorLiteral(text)
        val pair = SymbolPreset[literal]?.takeIf {
            appPrefs.input.symbolPairInput.getValue() && !InputModeSwitcher.isEmailOrUri
        }
        val commit = literal + pair.orEmpty()
        if (!DecodingInfo.isEngineFinish) {
            EnginePipeline.send(EngineAction.CommitThenText(
                text = commit,
                moveCursorLeft = pair != null,
                gen = DecodingInfo.nextActionGeneration()
            ))
        } else if (pair != null) {
            service.commitText(commit)
            postDelayed(300) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
        } else {
            service.commitText(literal)
        }
    }

    fun performEditorAction(editorAction: Int) = service.performEditorAction(editorAction)

    private fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        if (!isUndoing) {
            textBeforeCursors.clear()
        }
        service.commitText(resultText)
        if (InputModeSwitcher.isEnglish){
            service.finishComposingText()
            if(!InputModeSwitcher.isEmailOrUri && appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
            resetToIdleState()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initNavbarBackground(service)
        updateNavbarTheme()
        ViewCompat.requestApplyInsets(this)
    }

    private fun initNavbarBackground(service: ImeService) {
        service.window.window?.also { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val env = ImeEnvironment
            val navBarsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val mandatoryGesturesBottom = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            val tappableBottom = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom

            // Adaptive navigation bar insets across ROMs:
            // Computes max of gesture/navigation insets; falls back to 8dp comfort padding if zero.
            val systemBottom = maxOf(navBarsBottom, mandatoryGesturesBottom, tappableBottom)
            val effectiveBottom = if (!env.keyboardModeFloat && !env.isLandscape) {
                if (systemBottom > 0) {
                    systemBottom
                } else {
                    DevicesUtils.dip2px(8)
                }
            } else {
                0
            }

            env.systemNavbarWindowsBottom = effectiveBottom
            updateImeLayoutSnapshot()

            if (!env.keyboardModeFloat) {
                mLlKeyboardBottomHolder.visibility = if (effectiveBottom > 0) VISIBLE else GONE
                mLlKeyboardBottomHolder.layoutParams = RelativeLayout.LayoutParams(env.skbWidth, effectiveBottom).apply {
                    addRule(RelativeLayout.BELOW, R.id.ll_input_keyboard_container)
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                }
                mSkbRoot.bottomPadding = 0
            } else {
                mLlKeyboardBottomHolder.visibility = VISIBLE
                mLlKeyboardBottomHolder.layoutParams = RelativeLayout.LayoutParams(env.skbWidth, env.heightForKeyboardMove).apply {
                    addRule(RelativeLayout.BELOW, R.id.ll_input_keyboard_container)
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                }
                mSkbRoot.bottomPadding = 0
            }
            updateNavbarTheme()
            insets
        }
    }

    fun currentImeLayoutSnapshot(): ImeLayoutSnapshot {
        val snapshot = imeLayoutSnapshot
        if (snapshot.inputWidth > 0 && snapshot.inputHeight > 0) return snapshot
        val env = ImeEnvironment
        return ImeLayoutSnapshot(
            windowWidth = width.takeIf { it > 0 } ?: env.skbWidth,
            windowHeight = rootView.height.takeIf { it > 0 } ?: env.screenHeight,
            inputLeft = 0,
            inputTop = (rootView.height - env.inputAreaHeight).coerceAtLeast(0),
            inputWidth = env.skbWidth,
            inputHeight = env.inputAreaHeight,
            isFloating = env.keyboardModeFloat,
            orientation = resources.configuration.orientation,
            density = resources.displayMetrics.density,
            snapshotId = geomGeneration.get(),
        )
    }

    fun currentKeyboardSurfaceWidth(): Int {
        // During measurement, View.width still reflects the previous layout (e.g. one-handed).
        // Use the same target width as the key coordinates and candidate bar.
        return ImeEnvironment.skbWidth
    }

    private fun updateImeLayoutSnapshot(forceNewId: Boolean = false) {
        val env = ImeEnvironment
        val location = IntArray(2)
        mSkbRoot.getLocationInWindow(location)
        val inputWidth = mSkbRoot.width.takeIf { it > 0 } ?: env.skbWidth
        val inputHeight = mSkbRoot.height.takeIf { it > 0 } ?: env.inputAreaHeight
        val next = ImeLayoutSnapshot(
            windowWidth = rootView.width.takeIf { it > 0 } ?: inputWidth,
            windowHeight = rootView.height.takeIf { it > 0 } ?: env.screenHeight,
            inputLeft = location[0],
            inputTop = location[1].coerceAtLeast(0),
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            isFloating = env.keyboardModeFloat,
            orientation = resources.configuration.orientation,
            density = resources.displayMetrics.density,
            snapshotId = imeLayoutSnapshot.snapshotId,
        )
        val old = imeLayoutSnapshot
        val changed = forceNewId || !old.hasSameGeometry(next)
        if (!changed) return

        imeLayoutSnapshot = next.copy(snapshotId = geomGeneration.incrementAndGet())
        if (forceNewId && isAttachedToWindow && !env.keyboardModeFloat) {
            service.window?.window?.decorView?.requestApplyInsets()
        }
    }

    private fun ImeLayoutSnapshot.hasSameGeometry(other: ImeLayoutSnapshot): Boolean =
        windowWidth == other.windowWidth &&
            windowHeight == other.windowHeight &&
            inputLeft == other.inputLeft &&
            inputTop == other.inputTop &&
            inputWidth == other.inputWidth &&
            inputHeight == other.inputHeight &&
            isFloating == other.isFloating &&
            orientation == other.orientation &&
            density == other.density

    private fun updateNavbarTheme() {
        val env = ImeEnvironment
        val activeTheme = ThemeManager.activeTheme
        if (!env.keyboardModeFloat) {
            mLlKeyboardBottomHolder.setBackgroundColor(activeTheme.keyboardColor)
        } else {
            mLlKeyboardBottomHolder.setBackgroundColor(Color.TRANSPARENT)
        }
        service.window.window?.also { win ->
            val insetsController = WindowCompat.getInsetsController(win, win.decorView)
            insetsController.isAppearanceLightNavigationBars = !activeTheme.isDark
            if (!env.keyboardModeFloat) {
                win.navigationBarColor = activeTheme.keyboardColor
            } else {
                win.navigationBarColor = Color.TRANSPARENT
            }
        }
    }

    private var mInlineSuggestionGeneration: Long = 0L
    private var mInlineSuggestionSessionId: Long = 0L

    fun beginInlineSuggestions(editorSessionId: Long) {
        mInlineSuggestionSessionId = editorSessionId
        mInlineSuggestionGeneration++
        mSkbCandidatesBarView.clearInlineSuggestions()
    }

    fun clearInlineSuggestions(editorSessionId: Long? = null) {
        if (editorSessionId != null && editorSessionId != mInlineSuggestionSessionId) return
        mInlineSuggestionGeneration++
        mInlineSuggestionSessionId = 0L
        mSkbCandidatesBarView.clearInlineSuggestions()
    }

    fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        InputModeSwitcher.requestInputWithSkb(editorInfo, restarting)
        if (restarting) {
            KeyboardManager.instance.currentContainer?.updateSkbLayout()
        }
        if (KeyboardManager.instance.currentContainer == null) {
            KeyboardManager.instance.switchKeyboard()
        }
        if (InputModeSwitcher.isChineseHandWriting) {
            KeyboardManager.instance.switchKeyboard()
        }
        if (!restarting) {
            // P2: Pre-warm voice engine in background if model is ready and not yet initialized
            if (org.bitfennec.lime.core.runtime.AiModuleManager.isVoiceReady(context) && !VoiceRecognitionEngine.isReady()) {
                VoiceRecognitionEngine.init(context)
            }
            if (!InputModeSwitcher.isChineseHandWriting) resetToIdleState(resetEngine = false)
            clearInlineSuggestions()
            mSkbCandidatesBarView.clearClipboardSuggestion()
            val clipboard = appPrefs.clipboard
            if (clipboard.clipboardSuggestion.getValue() && !InputModeSwitcher.isPrivateOrSensitive) {
                val internal = appPrefs.internal
                val lastTime = internal.clipboardUpdateTime.getValue()
                if (System.currentTimeMillis() - lastTime <= clipboard.clipboardItemTimeout.getValue() * 1000L) {
                    val content = internal.clipboardUpdateContent.getValue()
                    if (content.isNotBlank()) {
                        showClipboardSuggestion(content)
                        internal.clipboardUpdateTime.setValue(0L)
                    }
                }
            }
        } else {
            if (!InputModeSwitcher.isNumberSkb && DecodingInfo.hasExternalCandidateSource) {
                DecodingInfo.clearModalCandidates()
            }
        }
    }

    fun inlineSuggestionSizeConstraints(): Pair<android.util.Size, android.util.Size> {
        val candidateHeight = ImeEnvironment.candidateRowHeight.coerceAtLeast(1)
        val availableWidth = mSkbCandidatesBarView.width.takeIf { it > 0 }
            ?: ImeEnvironment.skbWidth
        val maxWidth = (availableWidth - DevicesUtils.dip2px(20)).coerceAtLeast(1)
        return android.util.Size(minOf(maxWidth, DevicesUtils.dip2px(96)), candidateHeight) to
            android.util.Size(maxWidth, candidateHeight)
    }

    fun handleInlineSuggestions(
        editorSessionId: Long,
        suggestions: List<android.view.inputmethod.InlineSuggestion>,
    ) {
        if (editorSessionId != mInlineSuggestionSessionId) return
        val generation = ++mInlineSuggestionGeneration
        post {
            if (editorSessionId != mInlineSuggestionSessionId || generation != mInlineSuggestionGeneration ||
                !isAttachedToWindow || !mSkbCandidatesBarView.isAttachedToWindow
            ) return@post
            if (suggestions.isEmpty()) {
                mSkbCandidatesBarView.clearInlineSuggestions()
                return@post
            }
            val maxCount = minOf(suggestions.size, 3)
            val views = arrayOfNulls<View>(maxCount)
            mSkbCandidatesBarView.clearInlineSuggestions()
            val (_, maximumSize) = inlineSuggestionSizeConstraints()
            val preferredWidth = (maximumSize.width / maxCount).coerceAtLeast(1)

            for (i in 0 until maxCount) {
                val index = i
                val suggestion = suggestions[i]
                try {
                    val spec = suggestion.info.inlinePresentationSpec
                    val size = android.util.Size(
                        preferredWidth.coerceIn(spec.minSize.width, spec.maxSize.width),
                        maximumSize.height.coerceIn(spec.minSize.height, spec.maxSize.height),
                    )
                    suggestion.inflate(context, size, context.mainExecutor) { inlineView ->
                        if (editorSessionId != mInlineSuggestionSessionId || generation != mInlineSuggestionGeneration ||
                            !isAttachedToWindow || !mSkbCandidatesBarView.isAttachedToWindow
                        ) return@inflate
                        views[index] = inlineView
                        mSkbCandidatesBarView.setInlineSuggestionViews(views.filterNotNull())
                    }
                } catch (_: IllegalArgumentException) {
                    // A provider may reject a stale presentation after a layout change.
                } catch (_: IllegalStateException) {
                    // A reused suggestion may already be inflated; keep ordinary typing available.
                }
            }
        }
    }

    fun showKeyboardHeightAdjustBar() {
        if (!KeyboardManager.instance.isInputKeyboard) {
            KeyboardManager.instance.switchKeyboard()
        }
        mHeightAdjustBar?.show()
    }

    fun hideKeyboardHeightAdjustBar() {
        mHeightAdjustBar?.dismiss()
    }

    fun onWindowHidden() {
        hideKeyboardHeightAdjustBar()
        onVoiceCancel()
        HandwritingEngine.scheduleIdleUnload(60_000L)
        KeyboardManager.instance.switchKeyboard()
        resetToIdleState()
    }

    override fun onDetachedFromWindow() {
        hideKeyboardHeightAdjustBar()
        onVoiceCancel()
        super.onDetachedFromWindow()
    }

    private var selStart = 0
    private var selEnd = 0
    private var oldCandidatesEnd = 0
    private var expectedSelStart = -1
    private var expectedSelEnd = -1

    fun onImeCommittedText(length: Int) {
        if (selEnd >= 0) {
            expectedSelStart = selEnd + length
            expectedSelEnd = expectedSelStart
        }
    }

    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesEnd: Int) {
        val wasExpected = expectedSelStart >= 0 && newSelStart == expectedSelStart && newSelEnd == expectedSelEnd
        expectedSelStart = -1
        expectedSelEnd = -1

        selStart = newSelStart
        selEnd = newSelEnd
        (KeyboardManager.instance.currentContainer as? TextEditContainer)?.onSelectionUpdated(newSelStart, newSelEnd)
        if (KeyboardManager.instance.currentContainer is TextEditContainer) return
        if (InputModeSwitcher.isEnglish) {
            if (candidatesEnd >= 0 && oldCandidatesEnd == candidatesEnd) {
                service.finishComposingText()
                resetToIdleState()
            }
            oldCandidatesEnd = candidatesEnd
            return
        }

        if (InputModeSwitcher.isNumberSkb) {
            if (oldSelStart == newSelStart && oldSelEnd == newSelEnd && wasExpected) {
                return
            }
            if (InputModeSwitcher.isModalInputBlocked || InputModeSwitcher.isPrivateOrSensitive) {
                currentCalcSuccess = null
                resetCandidateWindow()
                if (wasExpected) return
                return
            }
            val isCalcEnabled = appPrefs.keyboardSetting.keyboardCalculator.getValue()
            val textBeforeCursor = service.getTextBeforeCursor(64)
            if (!isCalcEnabled || textBeforeCursor.isBlank()) {
                currentCalcSuccess = null
                resetCandidateWindow()
            } else {
                when (val calcRes = NumberCalcEngine.calculate(textBeforeCursor)) {
                    is CalcResult.Success -> {
                        currentCalcSuccess = calcRes
                        val items = arrayOf(
                            CandidateListItem("", calcRes.result),
                            CandidateListItem("", calcRes.fullEquation)
                        )
                        DecodingInfo.cacheCandidates(items, associate = true)
                    }
                    is CalcResult.Error -> {
                        currentCalcSuccess = null
                        val items = arrayOf(
                            CandidateListItem(CalcResult.COMMENT_ERROR, calcRes.message)
                        )
                        DecodingInfo.cacheCandidates(items, associate = true)
                    }
                    is CalcResult.Idle -> {
                        currentCalcSuccess = null
                        resetCandidateWindow()
                    }
                }
            }
            if (wasExpected) return
        }

        // Expected echo from our own commitText: ignore and return immediately
        if (wasExpected) {
            return
        }

        if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return

        val state = EnginePipeline.stateFlow.value
        val isManualJump = newSelStart != newSelEnd || newSelStart < oldSelStart || (newSelStart - oldSelStart) > 10
        if (state.stripMode == CandidateStripMode.PREDICT) {
            if (isManualJump) {
                resetCandidateWindow()
            }
        }
    }

    fun getSelectionEnd(): Int = selEnd

    fun getTextBeforeCursor(length: Int = 1000): String {
        return service.getTextBeforeCursor(length)
    }

    private fun hasUncommittedComposing(): Boolean {
        return (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) ||
                !DecodingInfo.isEngineFinish ||
                DecodingInfo.composingStrForDisplay.isNotEmpty()
    }

    private fun clearComposingState() {
        if (DecodingInfo.hasRimeComposition) {
            DecodingInfo.clearComposition()
        } else {
            resetCandidateWindow(resetEngine = false)
        }
        mSkbCandidatesBarView.clearClipboardSuggestion()
        mSkbCandidatesBarView.clearComposingSynchronous()
    }

    /**
     * Swipe-left selection: updates reverse selection region (fcitx5 style).
     */
    fun handleSwipeDeleteSelect(charCount: Int, initialCursor: Int) {
        if (hasUncommittedComposing()) {
            return
        }
        val targetCursor = when {
            initialCursor > 0 -> initialCursor
            selEnd > 0 -> selEnd
            else -> service.getTextBeforeCursor(1000).length
        }
        val start = (targetCursor - charCount).coerceAtLeast(0)
        service.setSelection(start, targetCursor)
    }

    /**
     * Swipe-left commit: deletes selected content.
     */
    fun handleSwipeDeleteCommit(charCount: Int, initialCursor: Int) {
        if (hasUncommittedComposing()) {
            val composingLen = DecodingInfo.composingStrForDisplay.length
            if (charCount >= composingLen) {
                clearComposingState()
            } else {
                repeat(charCount) { DecodingInfo.deleteAction() }
                updateCandidateBar()
            }
            return
        }
        if (charCount <= 0) return

        val selectedText = service.getSelectedText()
        val text = if (selectedText.isNotEmpty()) selectedText else service.getTextBeforeCursor(charCount)
        if (text.isNotEmpty()) {
            textBeforeCursors.push(text)
        }
        if (selectedText.isNotEmpty()) {
            service.commitText("")
        } else {
            service.deleteSurroundingText(charCount)
        }
        resetToIdleState()
    }

    /**
     * Swipe-left cancel: restores cursor position on reverse swipe.
     */
    fun handleSwipeDeleteCancel(initialCursor: Int) {
        if (hasUncommittedComposing()) {
            return
        }
        val targetCursor = when {
            initialCursor > 0 -> initialCursor
            selEnd > 0 -> selEnd
            else -> service.getTextBeforeCursor(1000).length
        }
        service.setSelection(targetCursor, targetCursor)
    }

    /**
     * Swipe clear: clears composition or text before cursor.
     */
    fun handleSwipeClearAll() {
        if (hasUncommittedComposing()) {
            clearComposingState()
            return
        }
        val selectedText = service.getSelectedText()
        val textBefore = service.getTextBeforeCursor(10000)
        val textToSave = if (selectedText.isNotEmpty()) "$textBefore$selectedText" else textBefore
        if (textToSave.isNotEmpty()) {
            textBeforeCursors.push(textToSave)
            if (selectedText.isNotEmpty()) {
                service.commitText("")
            }
            service.deleteSurroundingText(10000)
            val charCount = textToSave.codePointCount(0, textToSave.length)
            if (charCount > 50) {
                Toast.makeText(
                    context,
                    context.resources.getQuantityString(R.plurals.delete_tip_cleared_large_text, charCount, charCount),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        resetToIdleState()
    }

    /**
     * Swipe undo: restores previously deleted text from history stack.
     */
    fun handleSwipeUndoRevert() {
        if (hasUncommittedComposing()) {
            return
        }
        val restored = textBeforeCursors.popInReverseOrder()
        if (!restored.isNullOrEmpty()) {
            isUndoing = true
            try {
                commitText(restored)
            } finally {
                isUndoing = false
            }
            resetToIdleState()
        } else {
            PopupComponent.get().showActionPopup(
                R.drawable.ic_gesture_none,
                context.getString(R.string.delete_tip_no_undo_content)
            )
            mSkbRoot.postDelayed(1000) {
                PopupComponent.get().dismissPopup()
            }
        }
    }

    fun hasUndoContent(): Boolean = !textBeforeCursors.isEmpty()

    fun clearUndoStack() {
        textBeforeCursors.clear()
    }

    /**
     * Swipe-down delete to punctuation: deletes text back to nearest punctuation.
     */
    fun handleSwipeDeleteToPunctuation() {
        if (hasUncommittedComposing()) {
            clearComposingState()
            return
        }
        val text = service.getTextBeforeCursor(1000)
        if (text.isEmpty()) return

        val delLen = findDeleteToPunctuationLength(text)
        if (delLen > 0) {
            val deletedText = text.takeLast(delLen)
            textBeforeCursors.push(deletedText)
            service.deleteSurroundingText(delLen)
            resetToIdleState()
        }
    }

    companion object {
        private val PUNCTUATION_SET = setOf(
            // Chinese punctuation
            '，', '。', '！', '？', '；', '：', '、', '“', '”', '‘', '’',
            '（', '）', '《', '》', '【', '】', '…', '—', '～', '·',
            // English punctuation
            ',', '.', '!', '?', ';', ':', '\'', '"', '`',
            '(', ')', '<', '>', '[', ']', '{', '}',
            '/', '\\', '|', '@', '#', '$', '%', '^', '&', '*', '_', '-', '+', '=',
            // Whitespace and line breaks
            '\n', '\r'
        )

        fun findDeleteToPunctuationLength(text: String): Int {
            if (text.isEmpty()) return 0
            var end = text.length - 1
            while (end >= 0 && (text[end] in PUNCTUATION_SET || text[end].isWhitespace())) {
                end--
            }
            if (end < 0) {
                return text.length
            }
            var start = end
            while (start >= 0 && text[start] !in PUNCTUATION_SET && text[start] != '\n' && text[start] != '\r') {
                start--
            }
            return text.length - (start + 1)
        }
    }
}
