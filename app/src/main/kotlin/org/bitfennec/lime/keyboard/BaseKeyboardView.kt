package org.bitfennec.lime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.keyboard.model.KeyCenterOffsetProvider
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.keyboard.model.SoftKeyboard
import org.bitfennec.lime.keyboard.spatial.AdaptiveKeyCenterLearner
import org.bitfennec.lime.inputmethod.InputPipelineTrace
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.PopupMenuMode
import org.bitfennec.lime.R
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.view.popup.PopupComponent
import org.bitfennec.lime.view.popup.PopupComponent.Companion.get

/**
 * Base keyboard view implementation.
 */
open class BaseKeyboardView(context: Context?) : View(context) {
    private val popupComponent: PopupComponent = get()
    private val feedbackRouter = KeyboardFeedbackRouter(this, popupComponent)
    private val pointerPipeline = PointerPipeline()
    private val adaptiveKeyCenterLearner = AdaptiveKeyCenterLearner.create(context)
    protected var mSoftKeyboard: SoftKeyboard? = null
    private val activePointerKeys = mutableMapOf<Int, SoftKey>()
    private var mHandler: Handler? = null
    protected var mDrawPending = false
    protected var mDirtyRect = Rect()
    protected var mService: InputView? = null
    private val mAccessibilityHelper: KeyboardAccessibilityHelper = KeyboardAccessibilityHelper(this)

    fun setResponseKeyEvent(service: InputView) {
        mService = service
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        androidx.core.view.ViewCompat.setAccessibilityDelegate(this, mAccessibilityHelper)
        if (mHandler == null) {
            mHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_REPEAT -> {
                            val pointerId = msg.obj as? Int ?: 0
                            if (repeatKey(pointerId)) {
                                val repeat = Message.obtain(this, MSG_REPEAT, pointerId)
                                sendMessageDelayed(repeat, REPEAT_INTERVAL)
                            }
                        }

                        MSG_LONGPRESS -> {
                            val pointerId = msg.obj as? Int ?: 0
                            openPopupIfRequired(pointerId)
                        }
                    }
                }
            }
        }
    }

    fun invalidateKey() {
        mDrawPending = true
        invalidate()
    }

    protected fun displayKeyLabel(softKey: SoftKey, chineseUppercase: Boolean): String = when {
        InputModeSwitcher.isEnglish -> if (InputModeSwitcher.isLower) softKey.keyLabel.lowercase() else softKey.keyLabel.uppercase()
        InputModeSwitcher.isChinese -> if (chineseUppercase) softKey.keyLabel.uppercase() else softKey.keyLabel.lowercase()
        else -> softKey.keyLabel
    }

    open fun onBufferDraw() {}
    private fun openPopupIfRequired(pointerId: Int) {
        val softKey = pointerPipeline.getKey(pointerId) ?: return
        val keyLabel = displayKeyLabel(softKey, ThemeManager.prefs.keyboardChineseUppercase.getValue())
        executePointerCommands(
            pointerPipeline.onLongPress(
                pointerId = pointerId,
                keyLabel = keyLabel,
                keySmallLabel = softKey.keyLabelSmall,
                keyboardSymbolEnabled = ThemeManager.prefs.keyboardSymbol.getValue(),
            )
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        tracePointerEvent(me)
        when (me.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val actionIndex = me.actionIndex
                val pointerId = me.getPointerId(actionIndex)
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                handlePointerDown(pointerId, x, y, isInitialDown = true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = me.actionIndex
                val pointerId = me.getPointerId(actionIndex)
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                handlePointerDown(pointerId, x, y, isInitialDown = false)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until me.pointerCount) {
                    val pointerId = me.getPointerId(i)
                    val x = me.getX(i)
                    val y = me.getY(i)
                    dispatchPointerMove(pointerId, x, y)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = me.actionIndex
                val pointerId = me.getPointerId(actionIndex)
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                handlePointerUp(pointerId, x, y, isFinalUp = false)
            }
            MotionEvent.ACTION_UP -> {
                val actionIndex = me.actionIndex.coerceIn(0, me.pointerCount - 1)
                val pointerId = me.getPointerId(actionIndex)
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                handlePointerUp(pointerId, x, y, isFinalUp = true)
            }
            MotionEvent.ACTION_CANCEL -> {
                executePointerCommands(pointerPipeline.onCancel(null))
                adaptiveKeyCenterLearner.cancelActiveTouch()
                dismissPreview(pointerId = null, commitLongPress = false)
            }
        }
        return true
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float, isInitialDown: Boolean) {
        if (isInitialDown) {
            adaptiveKeyCenterLearner.flushExpiredTaps()
        } else {
            adaptiveKeyCenterLearner.cancelActiveTouch()
        }
        val key = getKeyIndices(x.toInt(), y.toInt())
        val layoutId = mSoftKeyboard?.layoutId
        if (isInitialDown && layoutId != null) {
            adaptiveKeyCenterLearner.onTouchDown(layoutId, key, x, y)
        }
        key?.let { feedbackRouter.keyDown(it.code) }
        showPreview(pointerId, key)

        val selectionEnd = if (key?.code == KeyEvent.KEYCODE_DEL) mService?.getSelectionEnd() ?: 0 else 0
        val textBeforeCursorLength = if (key?.code == KeyEvent.KEYCODE_DEL) {
            mService?.getTextBeforeCursor(1000)?.length ?: 0
        } else {
            0
        }
        executePointerCommands(
            pointerPipeline.onDown(
                key = key,
                x = x,
                y = y,
                pointerId = pointerId,
                context = PointerPipeline.DownContext(
                    selectionEnd = selectionEnd,
                    textBeforeCursorLength = textBeforeCursorLength,
                    longPressTimeoutMs = AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong(),
                ),
            )
        )
    }

    private fun handlePointerUp(pointerId: Int, x: Float, y: Float, isFinalUp: Boolean) {
        dispatchPointerMove(pointerId, x, y)
        val commands = pointerPipeline.onUp(pointerId)
        executePointerCommands(commands)
        if (isFinalUp && commands.none { it is PointerCommand.Tap }) {
            adaptiveKeyCenterLearner.cancelActiveTouch()
        }
        dismissPreview(pointerId = pointerId, commitLongPress = true)
    }

    private fun sendVoiceMoveEvent(localX: Float, localY: Float) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        mService?.onVoiceMove(loc[0] + localX, loc[1] + localY)
    }

    private fun dispatchPointerMove(pointerId: Int, currentX: Float, currentY: Float) {
        if (!pointerPipeline.isVoiceActive() && pointerPipeline.isTextPopupActive(pointerId)) {
            val downX = pointerPipeline.getDownX(pointerId)
            val downY = pointerPipeline.getDownY(pointerId)
            feedbackRouter.changeFocus(currentX - downX, currentY - downY)
            return
        }
        val slideMod = ThemeManager.prefs.symbolSlideUpMod.getValue()
        val symbolSlideUpRatio = slideMod.triggerRatio
        val symbolMinSwipePx = DevicesUtils.dip2px(slideMod.minSwipeDp).toFloat()
        val pxPerMm = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1f, resources.displayMetrics)
        val keyboardSetting = AppPrefs.getInstance().keyboardSetting
        val commands = pointerPipeline.onScroll(
            pointerId = pointerId,
            currentX = currentX,
            currentY = currentY,
            context = PointerPipeline.MoveContext(
                symbolSlideUpRatio = symbolSlideUpRatio,
                symbolMinSwipePx = symbolMinSwipePx,
                keyboardSymbolEnabled = ThemeManager.prefs.keyboardSymbol.getValue(),
                pxPerMm = pxPerMm,
                deleteSwipeTriggerPx = DevicesUtils.dip2px(16).toFloat(),
                deleteVerticalTriggerPx = DevicesUtils.dip2px(24).toFloat(),
                deleteStepPx = DevicesUtils.dip2px(14).toFloat(),
                deleteSwipeUpAction = keyboardSetting.deleteSwipeUpAction.getValue(),
                deleteSwipeLeftAction = keyboardSetting.deleteSwipeLeftAction.getValue(),
                deleteSwipeDownAction = keyboardSetting.deleteSwipeDownAction.getValue(),
                hasUndoContent = mService?.hasUndoContent() == true,
                swipeDownCapsEnabled = keyboardSetting.swipeDownCaps.getValue(),
                englishLetterCapsEnabled = InputModeSwitcher.isEnglish,
            ),
        )
        executePointerCommands(commands)
    }

    private fun repeatKey(pointerId: Int = 0): Boolean {
        if (pointerPipeline.shouldRepeatDelete(pointerId)) {
            adaptiveKeyCenterLearner.onDeleteKey()
            mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
            feedbackRouter.repeatDelete()
            checkShowDeleteGestureHint()
            return true
        }
        val repeatKey = pointerPipeline.directionRepeatKey(pointerId)
        if (repeatKey != null) {
            mService?.responseKeyEvent(repeatKey)
            return true
        }
        return false
    }

    private fun checkShowDeleteGestureHint() {
        val internalPrefs = AppPrefs.getInstance().internal
        if (!internalPrefs.deleteGestureGuideShown.getValue()) {
            internalPrefs.deleteGestureGuideShown.setValue(true)
            Toast.makeText(context, R.string.delete_gesture_long_press_guide, Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeMessages(pointerId: Int? = null) {
        if (mHandler != null) {
            if (pointerId != null) {
                mHandler!!.removeMessages(MSG_REPEAT, pointerId)
                mHandler!!.removeMessages(MSG_LONGPRESS, pointerId)
            } else {
                mHandler!!.removeMessages(MSG_REPEAT)
                mHandler!!.removeMessages(MSG_LONGPRESS)
            }
        }
    }

    fun cancelActiveTouch() {
        executePointerCommands(pointerPipeline.onCancel(null))
        adaptiveKeyCenterLearner.cancelActiveTouch()
        dismissPreview(pointerId = null, commitLongPress = false)
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    /**
     * Updates pressed state of a single key and invalidates dirty region.
     */
    fun updateKeyState(key: SoftKey?, pressed: Boolean) {
        if (key == null) return
        if (key.pressed != pressed) {
            if (pressed) key.onPressed() else key.onReleased()
            val inset = DevicesUtils.dip2px(4)
            mDirtyRect.union(key.mLeft - inset, key.mTop - inset, key.mRight + inset, key.mBottom + inset)
            invalidateKey()
        }
    }

    /**
     * Shows preview popup and pressed visual feedback.
     */
    private fun showPreview(pointerId: Int, key: SoftKey?) {
        val oldKey = activePointerKeys[pointerId]
        if (oldKey != null && oldKey != key) {
            if (activePointerKeys.values.count { it == oldKey } <= 1) {
                updateKeyState(oldKey, false)
            }
        }
        if (key != null) {
            activePointerKeys[pointerId] = key
            updateKeyState(key, true)
            showBalloonText(key)
        } else {
            activePointerKeys.remove(pointerId)
            if (activePointerKeys.isEmpty()) {
                feedbackRouter.dismissPopup()
            }
        }
    }

    /**
     * Dismisses preview popup and restores default visual state.
     */
    private fun dismissPreview(pointerId: Int? = null, commitLongPress: Boolean = true) {
        if (commitLongPress && pointerPipeline.consumeLongPressCommit(pointerId)) {
            val result = feedbackRouter.triggerFocused()
            if (result.first != PopupMenuMode.None) {
                mService?.responseLongKeyEvent(result)
            } else {
                val key = if (pointerId != null) activePointerKeys[pointerId] else null
                if (key != null && pointerPipeline.isTypingKey(key)) {
                    mService?.responseKeyEvent(key)
                }
            }
        } else if (!commitLongPress) {
            pointerPipeline.consumeLongPressCommit(pointerId)
        }

        if (pointerId != null) {
            val key = activePointerKeys.remove(pointerId)
            if (key != null && activePointerKeys.values.none { it == key }) {
                updateKeyState(key, false)
            }
            if (activePointerKeys.isEmpty()) {
                feedbackRouter.dismissPopup()
            } else {
                showBalloonText(activePointerKeys.values.last())
            }
        } else {
            activePointerKeys.clear()
            var stateChanged = false
            mSoftKeyboard?.mKeyRows?.forEach { row ->
                row.forEach { key ->
                    if (key.pressed) {
                        key.onReleased()
                        val inset = DevicesUtils.dip2px(4)
                        mDirtyRect.union(key.mLeft - inset, key.mTop - inset, key.mRight + inset, key.mBottom + inset)
                        stateChanged = true
                    }
                }
            }
            if (stateChanged) {
                invalidateKey()
            }
            feedbackRouter.dismissPopup()
        }
    }

    open fun closing() {
        cancelActiveTouch()
        removeMessages(null)
    }

    private fun showBalloonText(key: SoftKey) {
        val keyboardBalloonShow = AppPrefs.getInstance().keyboardSetting.keyboardBalloonShow.getValue()
        if (keyboardBalloonShow && key.keyLabel.isNotEmpty()) {
            feedbackRouter.showPopup(key.keyLabel, key)
        }
    }

    fun getKeyIndices(
        x: Int,
        y: Int,
        hysteresisKey: SoftKey? = null,
        useAdaptiveOffset: Boolean = true,
    ): SoftKey? {
        return mSoftKeyboard?.mapToKey(x, y, hysteresisKey, useAdaptiveOffset)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return if (mAccessibilityHelper.dispatchHoverEvent(event)) {
            true
        } else {
            super.dispatchHoverEvent(event)
        }
    }

    private var mFlattenedKeys: List<SoftKey> = emptyList()

    open fun setSoftKeyboard(softSkb: SoftKeyboard) {
        mSoftKeyboard = softSkb
        softSkb.keyCenterOffsetProvider = KeyCenterOffsetProvider { layoutId, key ->
            adaptiveKeyCenterLearner.offsetFor(layoutId, key)
        }
        mFlattenedKeys = softSkb.mKeyRows.flatten()
        mAccessibilityHelper.invalidateRoot()
    }

    fun getFlattenedKeys(): List<SoftKey> {
        return mFlattenedKeys
    }

    fun getSoftKeyboard(): SoftKeyboard {
        return mSoftKeyboard!!
    }

    protected fun isPointerLongPressActive(): Boolean = pointerPipeline.isLongPressActive()

    fun startVoiceFromAccessibility(): Boolean = mService?.startVoiceFromAccessibility() == true

    fun onKeyClickedFromAccessibility(key: SoftKey) {
        feedbackRouter.keyDown(key.code)
        adaptiveKeyCenterLearner.onTap(key)
        mService?.responseKeyEvent(key)
    }

    private fun executePointerCommands(commands: List<PointerCommand>) {
        commands.forEach { command ->
            when (command) {
                is PointerCommand.Tap -> {
                    adaptiveKeyCenterLearner.onTap(command.key)
                    mService?.responseKeyEvent(command.key)
                }
                is PointerCommand.FlickSymbol -> mService?.responseLongKeyEvent(command.result)
                is PointerCommand.FlickCaps -> mService?.responseFlickCaps(command.key)
                is PointerCommand.DeleteSelect -> mService?.handleSwipeDeleteSelect(command.charCount, command.initialCursor)
                is PointerCommand.DeleteCommit -> {
                    mService?.handleSwipeDeleteCommit(command.charCount, command.initialCursor)
                    feedbackRouter.vibrate()
                }
                is PointerCommand.DeleteCancel -> mService?.handleSwipeDeleteCancel(command.initialCursor)
                PointerCommand.DeleteClearAll -> {
                    mService?.handleSwipeClearAll()
                    feedbackRouter.vibrate()
                }
                PointerCommand.DeleteUndoRevert -> {
                    mService?.handleSwipeUndoRevert()
                    feedbackRouter.vibrate()
                }
                PointerCommand.DeleteToPunctuation -> {
                    mService?.handleSwipeDeleteToPunctuation()
                    feedbackRouter.vibrate()
                }
                is PointerCommand.PopupShowKey -> feedbackRouter.showKeyboard(command.label, command.smallLabel, command.key)
                is PointerCommand.PopupShowMenu -> feedbackRouter.showKeyboardMenu(command.key, command.distanceY)
                is PointerCommand.PopupShowText -> {
                    if (command.iconRes != 0) {
                        feedbackRouter.showActionPopup(command.iconRes, command.text)
                    } else {
                        feedbackRouter.showPopup(command.text, command.key)
                    }
                }
                is PointerCommand.PopupFocus -> feedbackRouter.changeFocus(command.deltaX, command.deltaY)
                is PointerCommand.PopupGesture -> feedbackRouter.onGestureEvent(command.distanceY)
                is PointerCommand.Haptic -> feedbackRouter.vibrate(command.event)
                is PointerCommand.VoiceStart -> {
                    val loc = IntArray(2)
                    getLocationOnScreen(loc)
                    val originScreenX = loc[0] + (command.key.mLeft + command.key.mRight) / 2f
                    val originScreenY = loc[1] + (command.key.mTop + command.key.mBottom) / 2f
                    pointerPipeline.confirmVoiceStarted(mService?.onVoiceStart(originScreenX, originScreenY) == true)
                }
                is PointerCommand.VoiceMove -> sendVoiceMoveEvent(command.x, command.y)
                PointerCommand.VoiceEnd -> mService?.onVoiceEnd()
                PointerCommand.VoiceCancel -> mService?.onVoiceCancel()
                is PointerCommand.ScheduleLongPress -> {
                    val msg = mHandler!!.obtainMessage(MSG_LONGPRESS, command.pointerId)
                    mHandler!!.sendMessageDelayed(msg, command.timeoutMs)
                }
                is PointerCommand.ScheduleRepeat -> {
                    val msg = mHandler!!.obtainMessage(MSG_REPEAT, command.pointerId)
                    mHandler!!.sendMessageDelayed(msg, REPEAT_START_DELAY)
                }
                is PointerCommand.CancelTimers -> removeMessages(command.pointerId)
                is PointerCommand.DismissPreview -> dismissPreview(pointerId = command.pointerId, commitLongPress = false)
                PointerCommand.DismissPopup -> feedbackRouter.dismissPopup()
            }
        }
    }

    private fun tracePointerEvent(event: MotionEvent) {
        val index = event.actionIndex.coerceAtMost(event.pointerCount - 1)
        InputPipelineTrace.pointerEvent(
            action = MotionEvent.actionToString(event.actionMasked),
            pointerId = event.getPointerId(index),
            x = event.getX(index),
            y = event.getY(index),
            eventTimeMillis = event.eventTime,
            snapshotId = currentSnapshotId(),
        )
    }

    private fun currentSnapshotId(): Long = mService?.currentImeLayoutSnapshot()?.snapshotId ?: 0L

    companion object {
        private const val MSG_REPEAT = 3
        private const val MSG_LONGPRESS = 4
        private const val REPEAT_INTERVAL = 50L // ~20 keys per second
        private const val REPEAT_START_DELAY = 400L
    }
}
