package org.bitfennec.lime.service

import org.bitfennec.lime.R
import android.content.res.Configuration
import android.content.ComponentCallbacks2
import android.graphics.Rect
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.bitfennec.lime.candidate.CandidateView
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager.OnThemeChangeListener
import org.bitfennec.lime.data.theme.ThemeManager.addOnChangedListener
import org.bitfennec.lime.data.theme.ThemeManager.onSystemDarkModeChange
import org.bitfennec.lime.data.theme.ThemeManager.removeOnChangedListener
import org.bitfennec.lime.core.runtime.AiModuleManager
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.HandwritingKeyboard
import org.bitfennec.lime.keyboard.container.ClipBoardContainer
import org.bitfennec.lime.keyboard.container.TextEditContainer
import org.bitfennec.lime.prefs.AppPrefs.Companion.getInstance
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.bitfennec.lime.utils.isDarkMode
import org.bitfennec.lime.view.preference.ManagedPreference
import org.bitfennec.lime.inputmethod.EngineEvent
import org.bitfennec.lime.inputmethod.EngineAction
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.inputmethod.EditorSessionKey
import org.bitfennec.lime.inputmethod.InputPipelineTrace
import org.bitfennec.lime.inputmethod.SessionUpdate
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.core.HandwritingEngine
import org.bitfennec.lime.inputmethod.voice.VoiceRecognitionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.time.Duration
import org.bitfennec.lime.utils.hasFlag

/**
 * Main Input Method Service implementation.
 */
class ImeService : InputMethodService(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private fun ensureWindowLifecycleOwner() {
        val decor = window?.window?.decorView
        if (decor != null && decor.findViewTreeLifecycleOwner() == null) {
            decor.setViewTreeLifecycleOwner(this)
        }
    }

    private fun moveToResumed() {
        when (lifecycleRegistry.currentState) {
            Lifecycle.State.INITIALIZED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.CREATED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.STARTED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.RESUMED,
            Lifecycle.State.DESTROYED -> Unit
        }
    }

    private fun moveToCreated() {
        when (lifecycleRegistry.currentState) {
            Lifecycle.State.RESUMED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            Lifecycle.State.STARTED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            Lifecycle.State.CREATED,
            Lifecycle.State.INITIALIZED,
            Lifecycle.State.DESTROYED -> Unit
        }
    }

    private fun moveToDestroyed() {
        when (lifecycleRegistry.currentState) {
            Lifecycle.State.RESUMED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            Lifecycle.State.STARTED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            Lifecycle.State.CREATED,
            Lifecycle.State.INITIALIZED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            Lifecycle.State.DESTROYED -> Unit
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        win.decorView.setViewTreeLifecycleOwner(this)
    }

    enum class ModalInputMode {
        VOICE,
        HANDWRITING,
    }

    private data class ModalSession(
        val id: Long,
        val editorSessionId: Long,
        val mode: ModalInputMode,
    )

    private data class PendingModalSession(
        val requestId: Long,
        val editorSessionId: Long,
        val mode: ModalInputMode,
        val onReady: (Long) -> Unit,
        val onCancelled: () -> Unit,
    )

    private var isHardwareKeyboard = false
    private var isSoftKeyboard = true
    var isCandidatesViewShown: Boolean = false
        private set
    private var isStylusHandwritingRequested = false
    private var rimeComposingSessionId = 0L
    private var rimeComposingText: String? = null
    private var rimeComposingGeneration = 0L
    private val modalSessionCounter = AtomicLong(0L)
    private var modalSession: ModalSession? = null
    private var pendingModalSession: PendingModalSession? = null
    private var inlineSuggestionSessionId = 0L
    private val stylusHandwritingRegion = Region()
    private var stylusHandwritingKeyboard: HandwritingKeyboard? = null
    private var stylusHandwritingReusedSession = false
    private lateinit var mInputView: InputView
    private lateinit var mCandidateView: CandidateView
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleTrimJob: Job? = null
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? ->
        val update = {
            if (isHardwareKeyboard) {
                if (::mCandidateView.isInitialized) mCandidateView.updateTheme()
            } else {
                if (::mInputView.isInitialized) mInputView.updateTheme()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            Handler(Looper.getMainLooper()).post(update)
        }
    }
    private val clipboardUpdateContent = getInstance().internal.clipboardUpdateContent
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if (isInputViewShown && isSoftKeyboard && getInstance().clipboard.clipboardSuggestion.getValue() && !InputModeSwitcher.isPrivateOrSensitive) {
            if (::mInputView.isInitialized) {
                if (value.isNotBlank()) {
                    if (KeyboardManager.instance.currentContainer is ClipBoardContainer
                        && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard
                    ) {
                        (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView()
                    } else {
                        mInputView.showClipboardSuggestion(value)
                    }
                } else {
                    mInputView.clearClipboardSuggestion()
                }
            }
        }
    }
    private val qwerty9GeometryPref = getInstance().keyboardSetting.qwerty9Geometry
    private val qwerty9GeometryListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            KeyboardManager.instance.switchKeyboard()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        ensureWindowLifecycleOwner()
        addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
        qwerty9GeometryPref.registerOnChangeListener(qwerty9GeometryListener)
        setBackDisposition(BACK_DISPOSITION_DEFAULT)

        // Collect engine pipeline events (commits, composing updates, etc.)
        serviceScope.launch(Dispatchers.Main.immediate) {
            EnginePipeline.updates.collect { update ->
                val event = (update as? SessionUpdate.Event)?.event ?: return@collect
                if (event.sessionId != EnginePipeline.currentSessionId) return@collect
                when (event) {
                    is EngineEvent.CommitText -> {
                        if (modalSession?.editorSessionId == event.sessionId) return@collect
                        val writeStartedAtNanos = InputPipelineTrace.mark()
                        commitText(event.text)
                        InputPipelineTrace.inputConnectionWrite(
                            sessionId = event.sessionId,
                            traceSequence = event.traceSequence,
                            operation = "commit_text",
                            textLength = event.text.length,
                            startedAtNanos = writeStartedAtNanos,
                        )
                        rimeComposingSessionId = event.sessionId
                        rimeComposingText = ""
                        if (InputModeSwitcher.isEnglish && !InputModeSwitcher.isEmailOrUri && getInstance().input.abcSpaceAuto.getValue()) {
                            commitText(" ")
                        }
                    }
                    is EngineEvent.ApplyRimeSnapshot -> applyRimeSnapshot(event)
                    is EngineEvent.CompositionCleared -> activateModalSession(event)
                    is EngineEvent.SendKeyEvent -> {
                        val sendKey = {
                            if (event.sessionId == EnginePipeline.currentSessionId) {
                                if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                                    sendEnterKeyEvent()
                                } else {
                                    sendCombinationKeyEvents(event.keyCode)
                                }
                            }
                        }
                        if (event.delayMs > 0L) {
                            launch {
                                delay(event.delayMs)
                                sendKey()
                            }
                        } else {
                            sendKey()
                        }
                    }
                }
            }
        }

    }

    override fun onCreateInputView(): View {
        ensureWindowLifecycleOwner()
        mInputView = InputView(baseContext, this)
        mInputView.setViewTreeLifecycleOwner(this)
        mInputView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                ensureWindowLifecycleOwner()
                (v.rootView ?: v).setViewTreeLifecycleOwner(this@ImeService)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        return mInputView
    }

    override fun onCreateCandidatesView(): View {
        ensureWindowLifecycleOwner()
        mCandidateView = CandidateView(baseContext, this)
        mCandidateView.setViewTreeLifecycleOwner(this)
        mCandidateView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                ensureWindowLifecycleOwner()
                (v.rootView ?: v).setViewTreeLifecycleOwner(this@ImeService)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        return mCandidateView
    }

    override fun onBindInput() {
        super.onBindInput()
        DecodingInfo.reset(invalidateSession = false, resetEngine = false)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return if(getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()) true else super.onEvaluateInputViewShown()
    }

    // yagni: ownership is process-lifetime, not per-Window; upgrade when IME window recreation is observed stripping flags
    private var imeOwnSecureFlag = false

    private fun applyImeWindowSecurity(password: Boolean) {
        val w = window?.window ?: return
        if (password) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            imeOwnSecureFlag = true
        } else if (imeOwnSecureFlag) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            imeOwnSecureFlag = false
        }
        Log.i(
            "ImeService",
            "{\"event\":\"ime.window_secure\",\"requested\":$password,\"own_secure\":$imeOwnSecureFlag,\"editor_session_id\":${EnginePipeline.currentSessionId}}"
        )
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        val flags = InputModeSwitcher.parseEditorFlags(editorInfo)
        InputModeSwitcher.setEditorFieldFlags(flags)
        val currentKey = EditorSessionKey.from(editorInfo)
        EnginePipeline.bindEditor(currentKey, flags, restarting)
        val mirrorComposition = shouldMirrorCompositionToEditor(editorInfo, InputModeSwitcher.isModalInputBlocked)
        if (rimeComposingSessionId != EnginePipeline.currentSessionId) {
            clearRimeComposingTracking()
        }
        mirrorRimeComposition = mirrorComposition
        applyImeWindowSecurity(flags.isPassword)
        EnginePipeline.send(EngineAction.ApplyEditorPolicy(
            schema = InputModeSwitcher.currentRimeSchema(),
            charCase = InputModeSwitcher.resolveSoftKeyMetaState(),
        ))
        clearInlineSuggestions()
        DecodingInfo.reset(invalidateSession = false, resetEngine = false)
        super.onStartInput(editorInfo, restarting)
        handleHardwareKeyboard()
        if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.onStartInput(editorInfo, restarting)
    }

    override fun onFinishInput() {
        applyImeWindowSecurity(false)
        clearInlineSuggestions()
        cancelModalSession()
        clearStylusHandwritingView()
        InputModeSwitcher.leaveTransientHandwritingMode()
        EnginePipeline.invalidateSession()
        clearRimeComposingTracking()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            mInputView.onVoiceCancel()
        }
        DecodingInfo.reset(invalidateSession = false, resetEngine = true)
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        applyImeWindowSecurity(false)
        clearInlineSuggestions()
        cancelModalSession()
        clearStylusHandwritingView()
        InputModeSwitcher.leaveTransientHandwritingMode()
        EnginePipeline.invalidateSession()
        clearRimeComposingTracking()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            mInputView.onVoiceCancel()
        }
        DecodingInfo.reset(invalidateSession = false, resetEngine = true)
        super.onUnbindInput()
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        ensureWindowLifecycleOwner()
        moveToResumed()
        ImeEnvironment.initData(this@ImeService)
        if (ImeEnvironment.keyboardModeFloat) {
            window?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        applyImeWindowSecurity(InputModeSwitcher.parseEditorFlags(editorInfo).isPassword)
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            mInputView.initView(this@ImeService)
            mInputView.onStartInputView(editorInfo, restarting)
        }
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clearInlineSuggestions()
        cancelModalSession()
        clearStylusHandwritingView()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            mInputView.onVoiceCancel()
            if (finishingInput) {
                mInputView.clearUndoStack()
            }
        }
        super.onFinishInputView(finishingInput)
        if (!isCandidatesViewShown) {
            moveToCreated()
        }
    }

    override fun onStartCandidatesView(info: EditorInfo, restarting: Boolean) {
        isCandidatesViewShown = true
        ensureWindowLifecycleOwner()
        moveToResumed()
        super.onStartCandidatesView(info, restarting)
    }

    override fun onFinishCandidatesView(finishingInput: Boolean) {
        isCandidatesViewShown = false
        super.onFinishCandidatesView(finishingInput)
        if (!isInputViewShown) {
            moveToCreated()
        }
    }

    override fun onDestroy() {
        moveToDestroyed()
        clearInlineSuggestions()
        cancelModalSession()
        clearStylusHandwritingView()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            mInputView.onVoiceCancel()
        }
        VoiceRecognitionEngine.release()
        HandwritingEngine.release()
        EnginePipeline.invalidateSession()
        clearRimeComposingTracking()
        serviceScope.cancel()
        removeOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
        qwerty9GeometryPref.unregisterOnChangeListener(qwerty9GeometryListener)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            clearInlineSuggestions()
            cancelModalSession()
            clearStylusHandwritingView()
            if (isSoftKeyboard && ::mInputView.isInitialized) {
                mInputView.onVoiceCancel()
            }
            KeyboardLoaderUtil.instance.clearKeyboardMap()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            VoiceRecognitionEngine.release()
            HandwritingEngine.release()
        }
    }

    /**
     * Handles configuration / orientation change.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stylusHandwritingRegion.setEmpty()
        handleHardwareKeyboard(newConfig)
        ImeEnvironment.initData(this@ImeService)
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            mInputView.initView(this@ImeService)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        } else if (isHardwareKeyboard && ::mCandidateView.isInitialized) {
            mCandidateView.initView()
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyboardManager.instance.currentContainer is TextEditContainer) return super.onKeyDown(keyCode, event)
        // Pass physical repeat keys or modifier shortcuts (Ctrl/Meta/Shift) to system; Ctrl+Space toggles language
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if (event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE) super.onKeyDown(keyCode, event)
        else if (isSoftKeyboard && isInputViewShown) mInputView.processKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized && mCandidateView.isShown) mCandidateView.processKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyboardManager.instance.currentContainer is TextEditContainer) return super.onKeyUp(keyCode, event)
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyUp(keyCode, event)
        else if (event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE) super.onKeyUp(keyCode, event)
        else if (isSoftKeyboard && isInputViewShown) mInputView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized && mCandidateView.isShown) mCandidateView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else super.onKeyUp(keyCode, event)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.setLayoutParams(layoutParams)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false // Prevent input view occlusion in landscape


    override fun onComputeInsets(outInsets: Insets) {
        val isShown = if (isSoftKeyboard) isInputViewShown else if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.isShown else false
        val layout = if (isSoftKeyboard && ::mInputView.isInitialized) {
            mInputView.currentImeLayoutSnapshot()
        } else {
            null
        }
        if (!isShown) {
            val hiddenTop = layout?.windowHeight?.takeIf { it > 0 } ?: ImeEnvironment.screenHeight
            outInsets.contentTopInsets = hiddenTop
            outInsets.visibleTopInsets = hiddenTop
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            outInsets.touchableRegion.setEmpty()
            return
        }
        val (x, y) = if (layout != null) intArrayOf(layout.inputLeft, layout.inputTop)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) intArrayOf(0, 0).also { mCandidateView.mSkbRoot.getLocationInWindow(it) }
        else intArrayOf(0, ImeEnvironment.screenHeight)

        val validY = if (y > 0) y else {
            val h = if (layout != null && layout.inputHeight > 0) {
                layout.inputHeight
            } else if (isHardwareKeyboard && ::mCandidateView.isInitialized && mCandidateView.mSkbRoot.height > 0) {
                mCandidateView.mSkbRoot.height
            } else {
                ImeEnvironment.inputAreaHeight
            }
            val windowHeight = layout?.windowHeight?.takeIf { it > h } ?: ImeEnvironment.screenHeight
            windowHeight - h
        }

        outInsets.apply {
            if (isSoftKeyboard || !isHardwareKeyboard) {
                if (layout?.isFloating == true || ImeEnvironment.keyboardModeFloat) {
                    val windowHeight = layout?.windowHeight?.takeIf { it > 0 } ?: ImeEnvironment.screenHeight
                    contentTopInsets = windowHeight
                    visibleTopInsets = windowHeight
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    val width = layout?.inputWidth?.takeIf { it > 0 } ?: ImeEnvironment.skbWidth
                    val height = layout?.inputHeight?.takeIf { it > 0 } ?: ImeEnvironment.inputAreaHeight
                    touchableRegion.set(x, validY, x + width, validY + height)
                } else {
                    contentTopInsets = validY
                    touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                    touchableRegion.setEmpty()
                    visibleTopInsets = validY
                }
            } else {
                contentTopInsets = ImeEnvironment.screenHeight
                visibleTopInsets = ImeEnvironment.screenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, validY, x + mCandidateView.mSkbRoot.width, validY + mCandidateView.mSkbRoot.height)
            }
        }
    }

    private var lastComposingLength = 0
    private var mirrorRimeComposition = false

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (lastComposingLength > 0 && candidatesStart >= 0 && (candidatesEnd - candidatesStart) == lastComposingLength) {
            return
        }
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
    }

    private val cursorAnchorPosition = FloatArray(2)
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null || !::mCandidateView.isInitialized) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        mCandidateView.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        idleTrimJob?.cancel()
        idleTrimJob = null
        if (!EnginePipeline.stateFlow.value.engineReady) {
            EnginePipeline.send(EngineAction.ApplyEditorPolicy(
                schema = InputModeSwitcher.currentRimeSchema(),
                charCase = InputModeSwitcher.resolveSoftKeyMetaState(),
                clearComposition = false,
            ))
        }
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        setBackDisposition(BACK_DISPOSITION_DEFAULT)
        clearInlineSuggestions()
        cancelModalSession()
        stylusHandwritingReusedSession = false
        clearStylusHandwritingView()
        InputModeSwitcher.leaveTransientHandwritingMode()
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onWindowHidden()
        super.onWindowHidden()
        idleTrimJob?.cancel()
        idleTrimJob = serviceScope.launch {
            delay(60_000L)
            if (!isInputViewShown) {
                withContext(Dispatchers.Main.immediate) {
                    onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
                }
            }
        }
    }

    override fun onStartStylusHandwriting(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isSoftKeyboard || !::mInputView.isInitialized) return false
        if (InputModeSwitcher.isModalInputBlocked) {
            logModalSession("stylus_handwriting", ModalInputMode.HANDWRITING, "rejected", "private_or_sensitive")
            return false
        }
        if (hasModalInputSession && currentModalSessionId(ModalInputMode.HANDWRITING) == null &&
            !hasPendingModalSession(ModalInputMode.HANDWRITING)
        ) {
            logModalSession("stylus_handwriting", ModalInputMode.HANDWRITING, "rejected", "modal_session_conflict")
            return false
        }
        val reusesHandwritingSession = currentModalSessionId(ModalInputMode.HANDWRITING) != null ||
            hasPendingModalSession(ModalInputMode.HANDWRITING)
        stylusHandwritingReusedSession = reusesHandwritingSession
        if (!mInputView.startStylusHandwriting()) {
            stylusHandwritingReusedSession = false
            return false
        }
        if (!attachStylusHandwritingView()) {
            if (!reusesHandwritingSession) cancelModalSession(ModalInputMode.HANDWRITING)
            InputModeSwitcher.leaveTransientHandwritingMode()
            stylusHandwritingReusedSession = false
            return false
        }
        isStylusHandwritingRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setStylusHandwritingSessionTimeout(Duration.ofMillis(2_000L))
        }
        stylusHandwritingRegion.setEmpty()
        return true
    }

    override fun onStylusHandwritingMotionEvent(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isStylusHandwritingRequested) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) updateStylusHandwritingRegion(event)
        stylusHandwritingKeyboard?.onStylusHandwritingMotionEvent(event)
    }

    override fun onFinishStylusHandwriting() {
        val wasReused = stylusHandwritingReusedSession
        stylusHandwritingReusedSession = false
        clearStylusHandwritingView()
        if (!wasReused) {
            cancelModalSession(ModalInputMode.HANDWRITING)
        }
        InputModeSwitcher.leaveTransientHandwritingMode()
        if (wasReused && InputModeSwitcher.isChineseHandWriting && ::mInputView.isInitialized) {
            mInputView.restoreHandwritingKeyboard()
            if (currentModalSessionId(ModalInputMode.HANDWRITING) == null &&
                !hasPendingModalSession(ModalInputMode.HANDWRITING)
            ) {
                mInputView.prepareHandwritingSession()
            }
        }
        super.onFinishStylusHandwriting()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun attachStylusHandwritingView(): Boolean = try {
        val keyboard = HandwritingKeyboard(baseContext).apply {
            useSystemStylusWindow()
            setResponseKeyEvent(mInputView)
        }
        mInputView.registerHandwritingKeyboard(keyboard)
        requireNotNull(getStylusHandwritingWindow()).setContentView(
            keyboard,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        stylusHandwritingKeyboard = keyboard
        true
    } catch (error: Throwable) {
        Log.e(
            "ImeService",
            "{\"event\":\"stylus_handwriting_window_attach\",\"result\":\"failed\",\"exception\":\"${error.javaClass.simpleName}\"}",
            error,
        )
        false
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun updateStylusHandwritingRegion(event: MotionEvent) {
        val radius = (resources.displayMetrics.density * 64f).toInt()
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        stylusHandwritingRegion.op(
            Rect(x - radius, y - radius, x + radius, y + radius),
            Region.Op.UNION,
        )
        setStylusHandwritingRegion(stylusHandwritingRegion)
    }

    private fun clearStylusHandwritingView() {
        isStylusHandwritingRequested = false
        stylusHandwritingRegion.setEmpty()
        stylusHandwritingKeyboard?.cancelPendingRecognition()
        stylusHandwritingKeyboard?.let { if (::mInputView.isInitialized) mInputView.unregisterHandwritingKeyboard(it) }
        stylusHandwritingKeyboard = null
    }

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (InputModeSwitcher.isPrivateOrSensitive || InputModeSwitcher.isNoSuggestions) {
            clearInlineSuggestions()
            return null
        }
        val editorSessionId = EnginePipeline.currentSessionId
        if (editorSessionId == 0L || !isSoftKeyboard || !::mInputView.isInitialized) return null
        inlineSuggestionSessionId = editorSessionId
        mInputView.beginInlineSuggestions(editorSessionId)
        val (minimumSize, maximumSize) = mInputView.inlineSuggestionSizeConstraints()
        val presentationSpec = InlinePresentationSpec.Builder(
            minimumSize,
            maximumSize,
        ).build()
        return InlineSuggestionsRequest.Builder(listOf(presentationSpec))
            .setMaxSuggestionCount(3)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val inlineSuggestions = response.inlineSuggestions
        if (isSoftKeyboard && ::mInputView.isInitialized &&
            inlineSuggestionSessionId == EnginePipeline.currentSessionId &&
            !InputModeSwitcher.isPrivateOrSensitive && !InputModeSwitcher.isNoSuggestions
        ) {
            mInputView.handleInlineSuggestions(inlineSuggestionSessionId, inlineSuggestions)
            return true
        }
        return false
    }

    fun switchToNextIme(): Boolean {
        return if (shouldOfferSwitchingToNextInputMethod()) {
            switchToNextInputMethod(false)
        } else {
            false
        }
    }

    /**
     * Simulates Enter key action.
     */
    fun sendEnterKeyEvent() {
        val inputConnection = currentInputConnection ?: return
        currentInputEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * Finishes an inline composition previously written by this session.
     */
    fun finishComposingText() {
        if (!mirrorRimeComposition || lastComposingLength == 0 ||
            rimeComposingSessionId != EnginePipeline.currentSessionId
        ) return
        lastComposingLength = 0
        currentInputConnection?.run {
            beginBatchEdit()
            finishComposingText()
            endBatchEdit()
        }
    }

    fun requestModalSession(
        mode: ModalInputMode,
        onReady: (Long) -> Unit,
        onCancelled: () -> Unit = {},
    ): Boolean {
        // Modal runtime installation gate
        if (mode == ModalInputMode.HANDWRITING) {
            if (!AiModuleManager.isHandwritingReady(this)) {
                android.widget.Toast.makeText(this, getString(R.string.handwriting_module_download_required), android.widget.Toast.LENGTH_SHORT)?.show()
                onCancelled()
                return false
            }
        } else if (mode == ModalInputMode.VOICE) {
            if (!AiModuleManager.isVoiceReady(this)) {
                android.widget.Toast.makeText(this, getString(R.string.voice_module_download_required), android.widget.Toast.LENGTH_SHORT)?.show()
                onCancelled()
                return false
            }
        }

        if (InputModeSwitcher.isModalInputBlocked) {
            logModalSession("modal_session_request", mode, "rejected", "private_or_sensitive")
            android.widget.Toast.makeText(this, getString(R.string.modal_input_unavailable), android.widget.Toast.LENGTH_SHORT).show()
            onCancelled()
            return false
        }
        clearInlineSuggestions()

        val editorSessionId = EnginePipeline.currentSessionId
        modalSession?.takeIf { it.editorSessionId == editorSessionId }?.let { active ->
            if (active.mode != mode) {
                logModalSession("modal_session_request", mode, "rejected", "active_mode_conflict")
                return false
            }
            logModalSession("modal_session_request", mode, "accepted", "active_reused")
            onReady(active.id)
            return true
        }
        pendingModalSession?.takeIf { it.editorSessionId == editorSessionId }?.let { pending ->
            if (pending.mode != mode) {
                logModalSession("modal_session_request", mode, "rejected", "pending_mode_conflict")
                return false
            }
            logModalSession("modal_session_request", mode, "accepted", "pending_reused")
            val prevOnReady = pending.onReady
            val prevOnCancelled = pending.onCancelled
            pendingModalSession = pending.copy(
                onReady = { id ->
                    prevOnReady(id)
                    onReady(id)
                },
                onCancelled = {
                    prevOnCancelled()
                    onCancelled()
                },
            )
            return true
        }

        val requestId = modalSessionCounter.incrementAndGet()
        modalSession = null

        pendingModalSession = PendingModalSession(requestId, editorSessionId, mode, onReady, onCancelled)
        if (!EnginePipeline.send(EngineAction.ClearComposition(modalRequestId = requestId))) {
            pendingModalSession = null
            logModalSession("modal_session_request", mode, "failed", "actor_queue_rejected")
            return false
        }
        logModalSession("modal_session_request", mode, "accepted", "clear_composition_queued")
        return true
    }

    fun currentModalSessionId(mode: ModalInputMode): Long? {
        val currentEditorSessionId = EnginePipeline.currentSessionId
        return modalSession?.takeIf {
            it.mode == mode && it.editorSessionId == currentEditorSessionId
        }?.id
    }

    private fun hasPendingModalSession(mode: ModalInputMode): Boolean =
        pendingModalSession?.let { it.mode == mode && it.editorSessionId == EnginePipeline.currentSessionId } == true

    fun isModalModeActive(mode: ModalInputMode): Boolean {
        val currentEditorSessionId = EnginePipeline.currentSessionId
        return (modalSession?.mode == mode && modalSession?.editorSessionId == currentEditorSessionId) ||
            (pendingModalSession?.mode == mode && pendingModalSession?.editorSessionId == currentEditorSessionId)
    }

    val hasModalInputSession: Boolean
        get() = modalSession?.editorSessionId == EnginePipeline.currentSessionId ||
            pendingModalSession?.editorSessionId == EnginePipeline.currentSessionId

    fun publishHandwritingCandidates(modalSessionId: Long, candidates: Array<org.bitfennec.lime.core.CandidateListItem>) {
        val currentEditorSessionId = EnginePipeline.currentSessionId
        val active = modalSession
        if (active == null ||
            active.mode != ModalInputMode.HANDWRITING ||
            active.id != modalSessionId ||
            active.editorSessionId != currentEditorSessionId
        ) {
            return
        }
        DecodingInfo.cacheCandidates(candidates, modalSessionId = active.id)
    }

    fun discardHandwritingCandidates(): Boolean {
        val active = modalSession
        if (active != null && active.mode == ModalInputMode.HANDWRITING) {
            return DecodingInfo.clearModalCandidates(active.id)
        }
        return false
    }

    fun commitModalText(modalSessionId: Long, text: String): Boolean {
        if (text.isEmpty() || InputModeSwitcher.isModalInputBlocked) return false
        val active = modalSession
        if (active == null || active.id != modalSessionId || active.editorSessionId != EnginePipeline.currentSessionId) {
            val reason = if (active == null) "no_active_session" else if (active.id != modalSessionId) "session_id_mismatch" else "editor_session_mismatch"
            logModalSession("modal_session_commit", active?.mode, "rejected", reason)
            return false
        }
        DecodingInfo.clearModalCandidates(active.id)
        return commitText(text)
    }

    fun cancelModalSession(modalSessionId: Long? = null) {
        val active = modalSession
        if (modalSessionId == null || active?.id == modalSessionId) {
            active?.let { DecodingInfo.clearModalCandidates(it.id) }
            modalSession = null
        }
        val pending = pendingModalSession
        if (pending != null && (modalSessionId == null || pending.requestId == modalSessionId)) {
            pendingModalSession = null
            pending.onCancelled()
        }
    }

    fun cancelModalSession(mode: ModalInputMode) {
        modalSession?.takeIf { it.mode == mode }?.let { DecodingInfo.clearModalCandidates(it.id) }
        if (modalSession?.mode == mode) modalSession = null
        val pending = pendingModalSession?.takeIf { it.mode == mode }
        if (pending != null) {
            pendingModalSession = null
            pending.onCancelled()
        }
        if (mode == ModalInputMode.HANDWRITING) {
            HandwritingEngine.scheduleIdleUnload(60_000L)
        }
    }

    private fun clearInlineSuggestions() {
        inlineSuggestionSessionId = 0L
        if (::mInputView.isInitialized) mInputView.clearInlineSuggestions()
    }

    private fun activateModalSession(event: EngineEvent.CompositionCleared) {
        val pending = pendingModalSession ?: return
        if (pending.requestId != event.modalRequestId) {
            logModalSession("modal_session_activation", pending.mode, "ignored", "request_id_mismatch")
            return
        }
        if (pending.editorSessionId != event.sessionId || event.sessionId != EnginePipeline.currentSessionId ||
            InputModeSwitcher.isModalInputBlocked
        ) {
            pendingModalSession = null
            logModalSession("modal_session_activation", pending.mode, "rejected", "session_or_privacy_changed")
            pending.onCancelled()
            return
        }
        pendingModalSession = null
        val session = ModalSession(
            id = modalSessionCounter.incrementAndGet(),
            editorSessionId = event.sessionId,
            mode = pending.mode,
        )
        modalSession = session
        logModalSession("modal_session_activation", session.mode, "accepted", "")
        pending.onReady(session.id)
    }

    private fun logModalSession(event: String, mode: ModalInputMode?, result: String, reason: String) {
        val flags = InputModeSwitcher.currentEditorFieldFlags
        val modeStr = mode?.name?.lowercase() ?: "none"
        Log.i(
            "ImeService",
            "{\"event\":\"$event\",\"result\":\"$result\",\"reason\":\"$reason\",\"editor_session_id\":${EnginePipeline.currentSessionId},\"mode\":\"$modeStr\",\"is_password\":${flags.isPassword},\"is_no_personalized_learning\":${flags.isNoPersonalizedLearning},\"is_no_suggestions\":${flags.isNoSuggestions}}",
        )
    }

    @androidx.annotation.VisibleForTesting
    internal var inputConnectionOverride: InputConnection? = null

    override fun getCurrentInputConnection(): InputConnection? {
        return inputConnectionOverride ?: super.getCurrentInputConnection()
    }

    /**
     * Sends text to target editor.
     */
    fun commitText(text: String, newCursorPosition: Int = 1): Boolean {
        val ic = currentInputConnection ?: return false
        if (::mInputView.isInitialized) {
            mInputView.onImeCommittedText(text.length)
        }
        ic.beginBatchEdit()
        val success = ic.commitText(text, newCursorPosition)
        ic.endBatchEdit()
        if (success) lastComposingLength = 0
        return success
    }


    private fun applyRimeSnapshot(event: EngineEvent.ApplyRimeSnapshot) {
        if (modalSession?.editorSessionId == event.sessionId) return
        val inputConnection = currentInputConnection ?: return
        if (rimeComposingSessionId == event.sessionId && event.sequence < rimeComposingGeneration) return
        val previousComposing = if (rimeComposingSessionId == event.sessionId) rimeComposingText else null
        val committedText = event.committedText?.takeIf { it.isNotEmpty() }
        val composingText = event.composingText
        val shouldWriteComposition = committedText != null || composingText != previousComposing
        if (committedText == null && !shouldWriteComposition) return

        inputConnection.beginBatchEdit()
        if (committedText != null) {
            if (::mInputView.isInitialized) {
                mInputView.onImeCommittedText(committedText.length)
            }
            val writeStartedAtNanos = InputPipelineTrace.mark()
            if (inputConnection.commitText(committedText, 1)) lastComposingLength = 0
            InputPipelineTrace.inputConnectionWrite(
                sessionId = event.sessionId,
                traceSequence = event.traceSequence,
                operation = "commit_text",
                textLength = committedText.length,
                startedAtNanos = writeStartedAtNanos,
            )
        }
        if (shouldWriteComposition && mirrorRimeComposition) {
            if (composingText.isNotEmpty()) {
                val writeStartedAtNanos = InputPipelineTrace.mark()
                if (inputConnection.setComposingText(composingText, 1)) {
                    lastComposingLength = composingText.length
                }
                InputPipelineTrace.inputConnectionWrite(
                    sessionId = event.sessionId,
                    traceSequence = event.traceSequence,
                    operation = "set_composing_text",
                    textLength = composingText.length,
                    startedAtNanos = writeStartedAtNanos,
                )
            } else if (committedText == null && lastComposingLength > 0 && rimeComposingSessionId == event.sessionId) {
                val writeStartedAtNanos = InputPipelineTrace.mark()
                inputConnection.setComposingText("", 0)
                InputPipelineTrace.inputConnectionWrite(
                    sessionId = event.sessionId,
                    traceSequence = event.traceSequence,
                    operation = "clear_composing_text",
                    textLength = 0,
                    startedAtNanos = writeStartedAtNanos,
                )
                lastComposingLength = 0
            }
        }
        inputConnection.endBatchEdit()
        rimeComposingSessionId = event.sessionId
        rimeComposingText = composingText
        rimeComposingGeneration = event.sequence

        if (committedText != null && InputModeSwitcher.isEnglish && !InputModeSwitcher.isEmailOrUri && getInstance().input.abcSpaceAuto.getValue()) {
            commitText(" ")
        }
    }

    private fun clearRimeComposingTracking() {
        lastComposingLength = 0
        mirrorRimeComposition = false
        rimeComposingSessionId = 0L
        rimeComposingText = null
        rimeComposingGeneration = 0L
    }

    fun getTextBeforeCursor(length:Int) : String {
        return currentInputConnection?.getTextBeforeCursor(length, 0)?.toString() ?: ""
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection?.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection?.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
        currentInputConnection?.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection?.setSelection(start, end)
    }

    fun getSelectedText(): String {
        return currentInputConnection?.getSelectedText(0)?.toString() ?: ""
    }

    fun handleHardwareKeyboard(newConfig: Configuration? = null) {
        val config = newConfig ?: resources.configuration
        val hasPhysicalKeyboard = config.keyboard == Configuration.KEYBOARD_QWERTY
                && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        val forceVirtualKeyboard = getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()
        val hardwareKeyboard = !forceVirtualKeyboard && hasPhysicalKeyboard
        isSoftKeyboard = !hardwareKeyboard
        isHardwareKeyboard = hardwareKeyboard
        setCandidatesViewShown(isHardwareKeyboard)
        currentInputConnection?.requestCursorUpdates(if (isHardwareKeyboard) InputConnection.CURSOR_UPDATE_MONITOR else 0)
    }

    companion object {
        internal fun shouldMirrorCompositionToEditor(editorInfo: EditorInfo?, isModalInputBlocked: Boolean): Boolean {
            if (editorInfo == null || isModalInputBlocked) return false
            val inputType = editorInfo.inputType
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
            // SEARCH and GO are compatibility heuristics for query/filter fields.
            return variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
                action == EditorInfo.IME_ACTION_SEARCH ||
                action == EditorInfo.IME_ACTION_GO
        }
    }
}
