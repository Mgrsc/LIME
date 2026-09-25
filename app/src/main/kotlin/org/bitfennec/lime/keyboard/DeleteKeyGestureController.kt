package org.bitfennec.lime.keyboard

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.view.popup.PopupComponent

/** Adapts a panel delete button to the keyboard's pointer state machine. */
class DeleteKeyGestureController(
    private val inputView: InputView,
    private val targetView: View,
) : View.OnTouchListener {
    private val pipeline = PointerPipeline()
    private val deleteKey = SoftKey(KeyEvent.KEYCODE_DEL)
    private val handler = Handler(Looper.getMainLooper())
    private var pointerId: Int? = null
    private var isTouchClick = false
    private val repeatDelete = object : Runnable {
        override fun run() {
            val id = pointerId ?: return
            if (!targetView.isShown) {
                cancel()
                return
            }
            if (pipeline.shouldRepeatDelete(id)) {
                inputView.responseKeyEvent(deleteKey)
                DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_DEL)
                DevicesUtils.tryVibrate(targetView, HapticEvent.STEP)
                handler.postDelayed(this, 50L)
            }
        }
    }

    init {
        targetView.setOnClickListener {
            if (!isTouchClick) {
                DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_DEL)
                DevicesUtils.tryVibrate(targetView)
            }
            inputView.responseKeyEvent(deleteKey)
        }
        targetView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = cancel()
        })
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                val id = event.getPointerId(event.actionIndex)
                pointerId = id
                targetView.isPressed = true
                DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_DEL)
                DevicesUtils.tryVibrate(targetView)
                execute(pipeline.onDown(deleteKey, event.x, event.y, id, PointerPipeline.DownContext(
                    selectionEnd = inputView.getSelectionEnd(),
                    textBeforeCursorLength = inputView.getTextBeforeCursor(1000).length,
                    longPressTimeoutMs = 400L,
                )))
            }
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = pointerId
                if (id != null && event.getPointerId(event.actionIndex) == id) {
                    move(event)
                    val commands = pipeline.onUp(id)
                    execute(commands)
                    pointerId = null
                    targetView.isPressed = false
                    if (commands.any { it is PointerCommand.Tap }) {
                        isTouchClick = true
                        try {
                            v.performClick()
                        } finally {
                            isTouchClick = false
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    fun cancel() {
        targetView.isPressed = false
        handler.removeCallbacks(repeatDelete)
        if (pointerId != null) {
            execute(pipeline.onCancel())
            PopupComponent.get().dismissPopup()
            pointerId = null
        }
    }

    private fun move(event: MotionEvent) {
        val id = pointerId ?: return
        val index = event.findPointerIndex(id)
        if (index < 0) return
        val settings = AppPrefs.getInstance().keyboardSetting
        execute(pipeline.onScroll(
            pointerId = id,
            currentX = event.getX(index),
            currentY = event.getY(index),
            context = PointerPipeline.MoveContext(
                keyboardSymbolEnabled = false,
                pxPerMm = 1f, // Unused for delete gestures, which use calibrated dp thresholds.
                deleteSwipeTriggerPx = DevicesUtils.dip2px(16).toFloat(),
                deleteVerticalTriggerPx = DevicesUtils.dip2px(24).toFloat(),
                deleteStepPx = DevicesUtils.dip2px(14).toFloat(),
                deleteSwipeUpAction = settings.deleteSwipeUpAction.getValue(),
                deleteSwipeLeftAction = settings.deleteSwipeLeftAction.getValue(),
                deleteSwipeDownAction = settings.deleteSwipeDownAction.getValue(),
                hasUndoContent = inputView.hasUndoContent(),
            ),
        ))
    }

    private fun execute(commands: List<PointerCommand>) {
        commands.forEach { command ->
            when (command) {
                is PointerCommand.DeleteSelect -> inputView.handleSwipeDeleteSelect(command.charCount, command.initialCursor)
                is PointerCommand.DeleteCancel -> inputView.handleSwipeDeleteCancel(command.initialCursor)
                is PointerCommand.DeleteCommit -> {
                    inputView.handleSwipeDeleteCommit(command.charCount, command.initialCursor)
                    DevicesUtils.tryVibrate(targetView)
                }
                PointerCommand.DeleteClearAll -> {
                    inputView.handleSwipeClearAll()
                    DevicesUtils.tryVibrate(targetView)
                }
                PointerCommand.DeleteUndoRevert -> {
                    inputView.handleSwipeUndoRevert()
                    DevicesUtils.tryVibrate(targetView)
                }
                PointerCommand.DeleteToPunctuation -> {
                    inputView.handleSwipeDeleteToPunctuation()
                    DevicesUtils.tryVibrate(targetView)
                }
                is PointerCommand.PopupShowText -> PopupComponent.get().showActionPopup(command.iconRes, command.text)
                PointerCommand.DismissPopup -> PopupComponent.get().dismissPopup()
                is PointerCommand.Haptic -> DevicesUtils.tryVibrate(targetView, command.event)
                is PointerCommand.CancelTimers -> handler.removeCallbacks(repeatDelete)
                is PointerCommand.ScheduleRepeat -> handler.postDelayed(repeatDelete, 400L)
                // The delete-only pipeline does not need long-press menus or cursor scrubbing.
                else -> Unit
            }
        }
    }
}
