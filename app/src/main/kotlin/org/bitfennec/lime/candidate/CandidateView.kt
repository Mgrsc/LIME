package org.bitfennec.lime.candidate

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.core.text.isDigitsOnly
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs.Companion.getInstance
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.service.DecodingInfo
import org.bitfennec.lime.inputmethod.EngineAction
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.service.ImeService
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.KeyEventUtils
import org.bitfennec.lime.utils.collectWhenStarted
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.utils.bottomPadding
import org.bitfennec.lime.utils.leftPadding
import kotlin.math.max

/**
 * Candidate view handling hardware keyboard input events.
 */
@SuppressLint("ViewConstructor")
class CandidateView(context: Context, private val service: ImeService) : RelativeLayout(context) {

    private var mHorizontalCutoutWidth: Int = 0
    private var mFloatCandidateBarWidth: Int = 0
    private val appPrefs = getInstance()
    private val mChoiceNotifier = ChoiceNotifier()
    var mSkbRoot: RelativeLayout
    var mSkbCandidatesBarView: FloatCandidateBar

    init {
        InputModeSwitcher.reset()
        initDisplayCutout(service)
        mFloatCandidateBarWidth = (if(ImeEnvironment.isLandscape)ImeEnvironment.screenHeight else ImeEnvironment.screenWidth) - dp(40)
        mSkbRoot = LayoutInflater.from(context).inflate(R.layout.candidate_container, this, false) as RelativeLayout
        addView(mSkbRoot)
        mSkbCandidatesBarView = mSkbRoot.findViewById(R.id.candidates_bar)
        initView()
        service.collectWhenStarted(DecodingInfo.candidatesFlow) {
            mSkbCandidatesBarView.showCandidates(skipUnchanged = true)
        }
        service.collectWhenStarted(EnginePipeline.stateFlow) {
            mSkbCandidatesBarView.showCandidates(skipUnchanged = true)
        }
    }

    private fun initDisplayCutout(service: ImeService) {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safeLeft = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left
            val safeRight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right
            val cutout = insets.displayCutout
            val displayCutoutWidth = if(cutout!=null) max(cutout.safeInsetLeft, cutout.safeInsetRight) else 0
            mHorizontalCutoutWidth = resources.displayMetrics.widthPixels - safeLeft - safeRight - displayCutoutWidth
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView() {
        mSkbCandidatesBarView.initialize(mChoiceNotifier)
        mSkbRoot.layoutParams.width = mFloatCandidateBarWidth
        updateTheme()
    }

    fun updateTheme() {
        setBackgroundResource(android.R.color.transparent)
        val activeTheme = ThemeManager.activeTheme
        val keyTextColor = activeTheme.keyTextColor
        mSkbCandidatesBarView.updateTheme(keyTextColor)
    }

    fun updatePosition(anchor: FloatArray) {
        val bottom = ImeEnvironment.screenHeight - anchor[1].toInt()
        val diffHight = (ImeEnvironment.hardwareCandidatesAreaHeight * 1.5).toInt()
        leftPadding = if(!ImeEnvironment.isLandscape) 0
         else if(mHorizontalCutoutWidth - anchor[0] > mFloatCandidateBarWidth)anchor[0].toInt() - dp(20)
        else mHorizontalCutoutWidth - mFloatCandidateBarWidth
        bottomPadding = if(bottom > diffHight) bottom - diffHight else bottom + ImeEnvironment.hardwareCandidatesAreaHeight
    }

    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(InputModeSwitcher.isEnglish) return false
        // Alphanumeric, symbols, space
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return true
        if (keyCode == KeyEvent.KEYCODE_SPACE) return true
        if (keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON) return true   // KEYCODE_SEMICOLON used in Double Pinyin
        // Editing keys
        if (keyCode == KeyEvent.KEYCODE_DEL) return true
        if (keyCode == KeyEvent.KEYCODE_ENTER) return true
        if (keyCode == KeyEvent.KEYCODE_TAB) return true
        // Direction keys
        if (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) return true
        return false
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        InputModeSwitcher.resetCharCase()
        return if (processFunctionKeys(event)) true
        else if (InputModeSwitcher.isChinese) processInput(event)
        else  false
    }

    private fun processFunctionKeys(event: KeyEvent): Boolean {
        return when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
                    InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
                    resetToIdleState(resetEngine = false)
                    Toast.makeText(context, if(InputModeSwitcher.isEnglish) context.getString(R.string.ime_mode_english) else context.getString(R.string.ime_mode_pinyin), Toast.LENGTH_LONG).show()
                } else if (DecodingInfo.hasExternalCandidateSource) {
                    chooseAndUpdate()
                } else {
                    EnginePipeline.send(EngineAction.SpaceKey(gen = DecodingInfo.nextActionGeneration()))
                }
                true
            }
            KeyEvent.KEYCODE_CLEAR -> {
                resetToIdleState()
                true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (DecodingInfo.hasExternalCandidateSource) chooseAndUpdate()
                else EnginePipeline.send(EngineAction.EnterKey(gen = DecodingInfo.nextActionGeneration()))
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!DecodingInfo.isCandidatesEmpty) {
                    mSkbCandidatesBarView.updateActiveCandidateNo(keyCode)
                } else {
                    sendKeyEvent(keyCode)
                }
                true
            }
            else -> false
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
            keyCode != KeyEvent.KEYCODE_0 && label.isDigitsOnly() && label.isNotEmpty() -> {
                chooseAndUpdate(label.toInt() - 1)
                true
            }
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            keyCode == KeyEvent.KEYCODE_APOSTROPHE ||
            keyCode == KeyEvent.KEYCODE_SEMICOLON ||
            (keyChar > 0 && Character.isLetter(keyChar)) -> {
                DecodingInfo.inputAction(event)
                updateCandidateBar()
                true
            }
            else -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                false
            }
        }
    }

    fun updateCandidateBar() = mSkbCandidatesBarView.showCandidates()

    fun resetToIdleState(resetEngine: Boolean = true) {
        DecodingInfo.reset(invalidateSession = false, resetEngine = resetEngine)
    }

    fun chooseAndUpdate(candId: Int = mSkbCandidatesBarView.getActiveCandNo()) {
        val selection = DecodingInfo.chooseDecodingCandidate(candId)
        if (selection.text.isNotEmpty()) {
            val modalSessionId = selection.modalSessionId
            if (modalSessionId != null) {
                service.commitModalText(modalSessionId, selection.text)
            } else {
                commitDecInfoText(selection.text)
            }
        }
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

        override fun onClickMore(level: Int) {}

        override fun onClickMenu(skbMenuMode: SkbMenuMode){}

        override fun onClickClearCandidate() {}
    }

    fun requestHideSelf() = service.requestHideSelf(0)

    private fun sendKeyEvent(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
            in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                service.sendCombinationKeyEvents(keyCode)
            }
            else -> service.sendCombinationKeyEvents(keyCode)
        }
    }

    private fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        service.commitText(resultText)
        if (InputModeSwitcher.isEnglish){
            service.finishComposingText()
            if(!InputModeSwitcher.isEmailOrUri && appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
            resetToIdleState()
        }
    }

    fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        if(editorInfo != null)InputModeSwitcher.requestInputWithSkb(editorInfo, restarting)
        if (!restarting) resetToIdleState(resetEngine = false)
    }

}
