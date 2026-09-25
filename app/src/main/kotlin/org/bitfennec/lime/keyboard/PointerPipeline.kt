package org.bitfennec.lime.keyboard

import android.view.KeyEvent
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.prefs.behavior.DeleteGestureAction
import org.bitfennec.lime.prefs.behavior.PopupMenuMode
import org.bitfennec.lime.view.popup.keyboardPopupLabels
import kotlin.math.abs
import kotlin.math.absoluteValue

class PointerPipeline {
    enum class DeleteGestureMode {
        NONE,
        SWIPE_LEFT,
        SWIPE_UP,
        SWIPE_DOWN,
    }

    data class DownContext(
        val selectionEnd: Int,
        val textBeforeCursorLength: Int,
        val longPressTimeoutMs: Long,
    )

    data class MoveContext(
        val symbolSlideUpRatio: Float = 0.40f,
        /** Minimum swipe displacement floor in pixels; defaults to 0f (no floor constraint). */
        val symbolMinSwipePx: Float = 0f,
        val keyboardSymbolEnabled: Boolean,
        val pxPerMm: Float,
        val deleteSwipeTriggerPx: Float,
        val deleteVerticalTriggerPx: Float,
        val deleteStepPx: Float,
        val deleteSwipeUpAction: DeleteGestureAction = DeleteGestureAction.CLEAR_ALL,
        val deleteSwipeLeftAction: DeleteGestureAction = DeleteGestureAction.SWIPE_SELECT,
        val deleteSwipeDownAction: DeleteGestureAction = DeleteGestureAction.UNDO_REVERT,
        val hasUndoContent: Boolean = true,
        val swipeDownCapsEnabled: Boolean = true,
        val englishLetterCapsEnabled: Boolean = false,
    )

    private class PointerState(
        val pointerId: Int,
        var currentKey: SoftKey?,
        var downX: Float,
        var downY: Float,
        var lastEventX: Float = downX,
        var lastEventY: Float = downY,
        var hasLastEvent: Boolean = true,
        var currentDistanceX: Float = 0f,
        var currentDistanceY: Float = 0f,
        var longPressKey: Boolean = false,
        var preserveLongPressOnOverlap: Boolean = false,
        var abortKey: Boolean = false,
        var repeatCount: Int = 0,
        var tapCommitted: Boolean = false,
        var isFlickArmed: Boolean = false,
        var flickSymbol: String = "",
        var isDownFlickArmed: Boolean = false,
        var deleteGestureMode: DeleteGestureMode = DeleteGestureMode.NONE,
        var activeDeleteAction: DeleteGestureAction = DeleteGestureAction.NONE,
        var deleteSwipeStep: Int = 0,
        var deleteInitialCursor: Int = 0,
        var deleteInitialTextLength: Int = 0,
    ) {
        val effectiveDeleteCursor: Int
            get() = if (deleteInitialCursor > 0) deleteInitialCursor else deleteInitialTextLength
    }

    private val pointers = mutableMapOf<Int, PointerState>()
    private val pendingLongPressCommits = mutableSetOf<Int>()
    private var voiceOwnerPointerId: Int? = null
    private var voiceActive = false

    fun isTypingKey(key: SoftKey?): Boolean {
        if (key == null) return false
        val code = key.code
        if (code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_DEL) return false
        if (code == InputModeSwitcher.USER_KEYCODE_LANG ||
            code == InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ||
            code == KeyEvent.KEYCODE_SHIFT_LEFT ||
            code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == InputModeSwitcher.USER_KEYCODE_RETURN
        ) return false
        return true
    }

    fun getKey(pointerId: Int): SoftKey? = pointers[pointerId]?.currentKey

    fun getDownX(pointerId: Int): Float = pointers[pointerId]?.downX ?: 0f

    fun getDownY(pointerId: Int): Float = pointers[pointerId]?.downY ?: 0f

    fun onDown(
        key: SoftKey?,
        x: Float,
        y: Float,
        pointerId: Int = 0,
        context: DownContext,
    ): List<PointerCommand> {
        pendingLongPressCommits.remove(pointerId)
        val state = PointerState(
            pointerId = pointerId,
            currentKey = key,
            downX = x,
            downY = y,
            lastEventX = x,
            lastEventY = y,
            hasLastEvent = true,
        )
        if (key?.code == KeyEvent.KEYCODE_DEL) {
            state.deleteInitialCursor = context.selectionEnd
            state.deleteInitialTextLength = context.textBeforeCursorLength
        }
        pointers[pointerId] = state
        if (key == null) return emptyList()
        if (voiceActive) {
            state.abortKey = true
            return emptyList()
        }

        val multiTouchCommands = mutableListOf<PointerCommand>()
        if (isTypingKey(key)) {
            for ((otherId, otherState) in pointers) {
                if (otherId != pointerId && isTypingKey(otherState.currentKey)) {
                    multiTouchCommands += PointerCommand.CancelTimers(otherId)
                    if (otherState.longPressKey && !otherState.preserveLongPressOnOverlap) {
                        otherState.longPressKey = false
                    }
                    if (!otherState.longPressKey && !otherState.isFlickArmed && !otherState.abortKey && !otherState.tapCommitted) {
                        val otherKey = otherState.currentKey
                        if (otherKey != null) {
                            otherState.tapCommitted = true
                            multiTouchCommands += PointerCommand.Tap(otherKey)
                            multiTouchCommands += PointerCommand.DismissPreview(otherId)
                        }
                    }
                }
            }
        }

        return multiTouchCommands + buildList {
            if (key.repeatable()) add(PointerCommand.ScheduleRepeat(pointerId))
            val timeout = if (key.code == KeyEvent.KEYCODE_SPACE) VOICE_ARM_TIMEOUT_MS else context.longPressTimeoutMs
            add(PointerCommand.ScheduleLongPress(timeout, pointerId))
        }
    }

    fun onUp(pointerId: Int = 0): List<PointerCommand> {
        val state = pointers.remove(pointerId) ?: return listOf(PointerCommand.CancelTimers(pointerId))
        val commands = mutableListOf<PointerCommand>(PointerCommand.CancelTimers(pointerId))
        val tapKey = state.currentKey

        if (voiceActive && voiceOwnerPointerId == pointerId) {
            voiceActive = false
            voiceOwnerPointerId = null
            state.longPressKey = false
            state.abortKey = true
            commands += PointerCommand.VoiceEnd
            return commands
        }
        if (voiceActive) {
            return commands
        }

        if (state.currentKey?.code == KeyEvent.KEYCODE_DEL) {
            when (state.activeDeleteAction) {
                DeleteGestureAction.SWIPE_SELECT -> {
                    commands += if (state.deleteSwipeStep > 0) {
                        PointerCommand.DeleteCommit(state.deleteSwipeStep, state.effectiveDeleteCursor)
                    } else {
                        PointerCommand.DeleteCancel(state.effectiveDeleteCursor)
                    }
                    state.abortKey = true
                }
                DeleteGestureAction.CLEAR_ALL -> {
                    commands += PointerCommand.DeleteClearAll
                    state.abortKey = true
                }
                DeleteGestureAction.UNDO_REVERT -> {
                    commands += PointerCommand.DeleteUndoRevert
                    state.abortKey = true
                }
                DeleteGestureAction.TO_PUNCTUATION -> {
                    commands += PointerCommand.DeleteToPunctuation
                    state.abortKey = true
                }
                DeleteGestureAction.NONE -> Unit
            }
            state.deleteGestureMode = DeleteGestureMode.NONE
            state.activeDeleteAction = DeleteGestureAction.NONE
            state.deleteSwipeStep = 0
            commands += PointerCommand.DismissPopup
        }

        if (state.isFlickArmed) {
            val symbol = state.flickSymbol
            state.isFlickArmed = false
            state.flickSymbol = ""
            state.abortKey = true
            state.longPressKey = false
            commands += PointerCommand.FlickSymbol(PopupMenuMode.Text to symbol)
            commands += PointerCommand.DismissPopup
        }

        if (state.isDownFlickArmed) {
            val capsKey = state.currentKey
            state.isDownFlickArmed = false
            state.abortKey = true
            state.longPressKey = false
            if (capsKey != null) {
                commands += PointerCommand.FlickCaps(capsKey)
            }
            commands += PointerCommand.DismissPopup
        }

        if (!state.abortKey && !state.tapCommitted && !state.longPressKey && state.repeatCount == 0 && tapKey != null) {
            commands += PointerCommand.Tap(tapKey)
        }
        if (state.longPressKey) {
            pendingLongPressCommits.add(pointerId)
        }
        return commands
    }

    fun onCancel(pointerId: Int? = null): List<PointerCommand> {
        if (pointerId == null) {
            pendingLongPressCommits.clear()
            val commands = mutableListOf<PointerCommand>(PointerCommand.CancelTimers(null))
            if (voiceActive) {
                commands += PointerCommand.VoiceCancel
                voiceActive = false
                voiceOwnerPointerId = null
            }
            pointers.values.forEach { state ->
                if (state.currentKey?.code == KeyEvent.KEYCODE_DEL &&
                    state.activeDeleteAction == DeleteGestureAction.SWIPE_SELECT &&
                    state.deleteSwipeStep > 0
                ) {
                    commands += PointerCommand.DeleteCancel(state.effectiveDeleteCursor)
                }
            }
            commands += PointerCommand.DismissPopup
            pointers.clear()
            return commands
        }

        pendingLongPressCommits.remove(pointerId)
        val state = pointers.remove(pointerId) ?: return listOf(PointerCommand.CancelTimers(pointerId))
        val commands = mutableListOf<PointerCommand>(PointerCommand.CancelTimers(pointerId))
        if (voiceActive && voiceOwnerPointerId == pointerId) {
            commands += PointerCommand.VoiceCancel
            voiceActive = false
            voiceOwnerPointerId = null
        }
        if (state.currentKey?.code == KeyEvent.KEYCODE_DEL &&
            state.activeDeleteAction == DeleteGestureAction.SWIPE_SELECT &&
            state.deleteSwipeStep > 0
        ) {
            commands += PointerCommand.DeleteCancel(state.effectiveDeleteCursor)
        }
        commands += PointerCommand.DismissPopup
        return commands
    }

    fun onScroll(
        pointerId: Int = 0,
        currentX: Float,
        currentY: Float,
        distanceX: Float = 0f,
        distanceY: Float = 0f,
        context: MoveContext,
    ): List<PointerCommand> {
        val state = pointers[pointerId] ?: return emptyList()
        if (state.tapCommitted) return emptyList()

        if (voiceActive) {
            return if (pointerId == voiceOwnerPointerId) {
                listOf(PointerCommand.VoiceMove(currentX, currentY))
            } else {
                emptyList()
            }
        }

        val effDistanceX = if (distanceX != 0f) distanceX else if (state.hasLastEvent) state.lastEventX - currentX else 0f
        val effDistanceY = if (distanceY != 0f) distanceY else if (state.hasLastEvent) state.lastEventY - currentY else 0f
        state.currentDistanceX = effDistanceX
        state.currentDistanceY = effDistanceY
        if (!state.hasLastEvent) {
            state.lastEventX = currentX
            state.lastEventY = currentY
            state.hasLastEvent = true
            return emptyList()
        }

        val key = state.currentKey ?: return emptyList()
        val totalDeltaX = currentX - state.downX
        val totalDeltaY = currentY - state.downY
        val absTotalX = abs(totalDeltaX)
        val absTotalY = abs(totalDeltaY)

        buildDeleteGestureCommands(state, key, totalDeltaX, totalDeltaY, absTotalX, absTotalY, context)?.let {
            state.lastEventX = currentX
            state.lastEventY = currentY
            return it
        }

        val relDiffX = abs(currentX - state.lastEventX)
        val relDiffY = abs(currentY - state.lastEventY)
        val isVertical = relDiffX * 1.5f < relDiffY
        val smallLabel = key.keyLabelSmall

        val capsEligible = context.swipeDownCapsEnabled &&
            context.englishLetterCapsEnabled &&
            key.code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z

        val keyHeight = if (key.height() > 0) key.height().toFloat() else DEFAULT_KEY_DIMENSION
        val isBottomRow = key.isBottomRowKey()
        val capsEffectiveRatio = if (isBottomRow) {
            if (isSpaceAdjacentBottomKey(key)) {
                BOTTOM_ROW_SPACE_ADJACENT_TRIGGER_RATIO
            } else {
                BOTTOM_ROW_DEFAULT_TRIGGER_RATIO
            }
        } else {
            CAPS_DEFAULT_TRIGGER_RATIO
        }
        val triggerDistance = keyHeight * capsEffectiveRatio

        val commands = if (key.code == KeyEvent.KEYCODE_SPACE) {
            val dragThreshold = context.pxPerMm * 2f
            if (!state.abortKey && (absTotalX > dragThreshold || absTotalY > dragThreshold)) {
                state.abortKey = true
                listOf(PointerCommand.CancelTimers(pointerId))
            } else {
                emptyList()
            }
        } else if (capsEligible && (state.isDownFlickArmed || (!state.isFlickArmed && !state.longPressKey && totalDeltaY > 0))) {
            val isDownwardCone = totalDeltaY > 0 && absTotalX * FLICK_CONE_FACTOR < totalDeltaY
            val isDownFlickCandidate = isDownwardCone && totalDeltaY >= triggerDistance

            if (isDownFlickCandidate) {
                if (!state.isDownFlickArmed) {
                    state.isDownFlickArmed = true
                    val upperText = key.label.uppercase().ifEmpty {
                        ('A'.code + (key.code - KeyEvent.KEYCODE_A)).toChar().toString()
                    }
                    listOf(
                        PointerCommand.CancelTimers(pointerId),
                        PointerCommand.PopupShowText(upperText, key),
                    )
                } else {
                    emptyList()
                }
            } else if (state.isDownFlickArmed) {
                val isDownPulledBack = totalDeltaY < triggerDistance * FLICK_PULLBACK_RATIO || totalDeltaY <= 0
                if (isDownPulledBack) {
                    state.isDownFlickArmed = false
                    listOf(
                        PointerCommand.PopupShowText(key.keyLabel, key),
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } else if (smallLabel.isNotBlank() && context.keyboardSymbolEnabled && !state.isDownFlickArmed) {
            if (state.longPressKey) {
                emptyList()
            } else {
                // Tighten symbol flicks without changing downward caps or popup gestures.
                val upRatio = context.symbolSlideUpRatio + if (isBottomRow) {
                    if (isSpaceAdjacentBottomKey(key)) {
                        BOTTOM_ROW_SPACE_ADJACENT_RATIO_BONUS
                    } else {
                        BOTTOM_ROW_RATIO_BONUS
                    }
                } else {
                    NON_BOTTOM_ROW_UP_RATIO_BONUS
                }
                val upTriggerDistance = (keyHeight * upRatio).coerceAtLeast(context.symbolMinSwipePx)
                val isUpwardCone = totalDeltaY < 0 && absTotalX * FLICK_CONE_FACTOR < -totalDeltaY
                val isCrossedTop = !isBottomRow || key.height() <= 0 || (key.mTop - currentY >= keyHeight * BOTTOM_ROW_TOP_EXIT_RATIO)
                val isFlickCandidate = isUpwardCone && -totalDeltaY >= upTriggerDistance && isCrossedTop

                if (isFlickCandidate) {
                    if (!state.isFlickArmed) {
                        state.isFlickArmed = true
                        state.flickSymbol = smallLabel
                        listOf(
                            PointerCommand.CancelTimers(pointerId),
                            PointerCommand.PopupShowText(smallLabel, key),
                        )
                    } else {
                        emptyList()
                    }
                } else if (state.isFlickArmed) {
                    val isPulledBack = -totalDeltaY < upTriggerDistance * FLICK_PULLBACK_RATIO ||
                        totalDeltaY >= 0
                    // A wider exit cone keeps small direction changes from flickering.
                    val isSideways = absTotalX > -totalDeltaY
                    if (isPulledBack || isSideways) {
                        state.isFlickArmed = false
                        state.flickSymbol = ""
                        listOf(
                            PointerCommand.PopupShowText(key.keyLabel, key),
                        )
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        } else if (smallLabel.isNotBlank()) {
            emptyList()
        } else {
            val popupTriggerDistance = keyHeight * context.symbolSlideUpRatio
            if (isVertical && relDiffY > popupTriggerDistance * 2f) {
                state.longPressKey = true
                listOf(PointerCommand.PopupGesture(effDistanceY))
            } else {
                listOf(PointerCommand.PopupFocus(currentX - state.downX, currentY - state.downY))
            }
        }
        state.lastEventX = currentX
        state.lastEventY = currentY
        return commands
    }

    fun onLongPress(
        pointerId: Int = 0,
        keyLabel: String,
        keySmallLabel: String,
        keyboardSymbolEnabled: Boolean,
    ): List<PointerCommand> {
        val state = pointers[pointerId] ?: return emptyList()
        val key = state.currentKey ?: return emptyList()
        if (key.code == KeyEvent.KEYCODE_DEL) return emptyList()

        if (isTypingKey(key) && pointers.values.any { it.pointerId != pointerId && isTypingKey(it.currentKey) }) {
            return emptyList()
        }

        if (key.code == KeyEvent.KEYCODE_SPACE) {
            state.longPressKey = true
            state.abortKey = true
            voiceOwnerPointerId = pointerId
            return listOf(PointerCommand.VoiceStart(key), PointerCommand.DismissPopup)
        }

        if (key.keyLabel.isNotBlank() && key.code != InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI) {
            val designPreset = setOf("，", "。", ",", ".")
            val smallLabel = if (designPreset.any { it == keyLabel } || !keyboardSymbolEnabled) "" else keySmallLabel
            state.longPressKey = keyboardPopupLabels(keyLabel, smallLabel).isNotEmpty()
            state.preserveLongPressOnOverlap = smallLabel.isNotEmpty()
            if (!state.longPressKey) return emptyList()
            return listOf(PointerCommand.PopupShowKey(keyLabel, smallLabel, key), PointerCommand.Haptic(HapticEvent.LONG_PRESS))
        }

        if (key.code == InputModeSwitcher.USER_KEYCODE_LANG ||
            key.code == InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ||
            (key.code == KeyEvent.KEYCODE_SHIFT_LEFT && !InputModeSwitcher.isChinese) ||
            key.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION ||
            key.code == KeyEvent.KEYCODE_ENTER
        ) {
            state.longPressKey = true
            state.preserveLongPressOnOverlap = true
            return listOf(PointerCommand.PopupShowMenu(key, state.currentDistanceY), PointerCommand.Haptic(HapticEvent.LONG_PRESS))
        }

        state.longPressKey = true
        state.abortKey = true
        state.preserveLongPressOnOverlap = true
        return listOf(PointerCommand.DismissPreview(pointerId))
    }

    fun confirmVoiceStarted(started: Boolean) {
        voiceActive = started
        if (!started) {
            voiceOwnerPointerId = null
        }
    }

    fun isVoiceActive(): Boolean = voiceActive

    fun consumeLongPressCommit(pointerId: Int? = null): Boolean {
        if (pointerId != null) {
            val state = pointers[pointerId]
            if (state?.longPressKey == true) {
                state.longPressKey = false
                pendingLongPressCommits.remove(pointerId)
                return true
            }
            return pendingLongPressCommits.remove(pointerId)
        }
        val target = pointers.values.firstOrNull { it.longPressKey }
        if (target != null) {
            target.longPressKey = false
            pendingLongPressCommits.remove(target.pointerId)
            return true
        }
        val pendingId = pendingLongPressCommits.firstOrNull() ?: return false
        pendingLongPressCommits.remove(pendingId)
        return true
    }

    fun isTextPopupActive(pointerId: Int? = null): Boolean {
        if (voiceActive) return false
        val state = if (pointerId != null) pointers[pointerId] else pointers.values.firstOrNull { it.longPressKey }
        return state?.longPressKey == true && state.currentKey?.code != KeyEvent.KEYCODE_SPACE && state.currentKey?.keyLabel?.isNotBlank() == true
    }

    fun isLongPressActive(pointerId: Int? = null): Boolean =
        if (pointerId != null) pointers[pointerId]?.longPressKey == true else pointers.values.any { it.longPressKey }

    internal fun isFlickArmed(pointerId: Int? = null): Boolean =
        if (pointerId != null) pointers[pointerId]?.isFlickArmed == true else pointers.values.any { it.isFlickArmed }

    internal fun isDownFlickArmed(pointerId: Int? = null): Boolean =
        if (pointerId != null) pointers[pointerId]?.isDownFlickArmed == true else pointers.values.any { it.isDownFlickArmed }

    fun directionRepeatKey(pointerId: Int? = null): SoftKey? {
        val state = if (pointerId != null) pointers[pointerId] else pointers.values.firstOrNull()
        val key = state?.currentKey ?: return null
        if (!key.repeatable()) return null
        if (key.code == KeyEvent.KEYCODE_DEL) return null
        state.repeatCount++
        return if (key.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION) {
            SoftKey(
                if (state.currentDistanceX.absoluteValue >= state.currentDistanceY.absoluteValue) {
                    if (state.currentDistanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                } else {
                    if (state.currentDistanceY < 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                }
            )
        } else {
            key
        }
    }

    fun shouldRepeatDelete(pointerId: Int? = null): Boolean {
        val state = if (pointerId != null) pointers[pointerId] else pointers.values.firstOrNull { it.currentKey?.code == KeyEvent.KEYCODE_DEL }
        val ok = state?.currentKey?.code == KeyEvent.KEYCODE_DEL && state.deleteGestureMode == DeleteGestureMode.NONE
        if (ok) {
            state.repeatCount++
        }
        return ok
    }

    fun activePointerCount(): Int = pointers.size

    private fun buildDeleteGestureCommands(
        state: PointerState,
        key: SoftKey,
        totalDeltaX: Float,
        totalDeltaY: Float,
        absTotalX: Float,
        absTotalY: Float,
        moveContext: MoveContext,
    ): List<PointerCommand>? {
        if (key.code != KeyEvent.KEYCODE_DEL) return null

        if (state.deleteGestureMode == DeleteGestureMode.NONE) {
            when {
                totalDeltaX < -moveContext.deleteSwipeTriggerPx && absTotalX > absTotalY -> {
                    if (moveContext.deleteSwipeLeftAction != DeleteGestureAction.NONE) {
                        state.deleteGestureMode = DeleteGestureMode.SWIPE_LEFT
                        state.activeDeleteAction = moveContext.deleteSwipeLeftAction
                        state.abortKey = true
                    }
                }
                totalDeltaY < -moveContext.deleteVerticalTriggerPx && absTotalY > absTotalX -> {
                    if (moveContext.deleteSwipeUpAction != DeleteGestureAction.NONE) {
                        state.deleteGestureMode = DeleteGestureMode.SWIPE_UP
                        state.activeDeleteAction = moveContext.deleteSwipeUpAction
                        state.abortKey = true
                    }
                }
                totalDeltaY > moveContext.deleteVerticalTriggerPx && absTotalY > absTotalX -> {
                    if (moveContext.deleteSwipeDownAction != DeleteGestureAction.NONE) {
                        state.deleteGestureMode = DeleteGestureMode.SWIPE_DOWN
                        state.activeDeleteAction = moveContext.deleteSwipeDownAction
                        state.abortKey = true
                    }
                }
            }
        }

        if (state.activeDeleteAction == DeleteGestureAction.NONE) return emptyList()

        return when (state.activeDeleteAction) {
            DeleteGestureAction.SWIPE_SELECT -> buildDeleteSwipeSelectCommands(state, totalDeltaX, totalDeltaY, absTotalX, absTotalY, moveContext)
            DeleteGestureAction.CLEAR_ALL -> buildDeletePopupActionCommands(
                state = state,
                iconRes = R.drawable.ic_gesture_clear,
                text = runCatching { Launcher.instance.context.getString(R.string.delete_tip_clear_all) }.getOrDefault("Clear All"),
                key = key,
                shouldReset = isDeleteGestureReturning(state.deleteGestureMode, totalDeltaX, totalDeltaY, moveContext),
            )
            DeleteGestureAction.UNDO_REVERT -> {
                val hasUndo = moveContext.hasUndoContent
                val text = if (hasUndo) {
                    runCatching { Launcher.instance.context.getString(R.string.delete_tip_undo_revert) }.getOrDefault("Undo Delete")
                } else {
                    runCatching { Launcher.instance.context.getString(R.string.delete_tip_no_undo_content) }.getOrDefault("Nothing to undo")
                }
                val icon = if (hasUndo) R.drawable.ic_gesture_undo else R.drawable.ic_gesture_none
                buildDeletePopupActionCommands(
                    state = state,
                    iconRes = icon,
                    text = text,
                    key = key,
                    shouldReset = isDeleteGestureReturning(state.deleteGestureMode, totalDeltaX, totalDeltaY, moveContext),
                )
            }
            DeleteGestureAction.TO_PUNCTUATION -> buildDeletePopupActionCommands(
                state = state,
                iconRes = R.drawable.ic_gesture_punctuation,
                text = runCatching { Launcher.instance.context.getString(R.string.delete_tip_to_punctuation) }.getOrDefault("Delete to Punctuation"),
                key = key,
                shouldReset = isDeleteGestureReturning(state.deleteGestureMode, totalDeltaX, totalDeltaY, moveContext),
            )
            DeleteGestureAction.NONE -> emptyList()
        }
    }

    private fun isDeleteGestureReturning(
        mode: DeleteGestureMode,
        totalDeltaX: Float,
        totalDeltaY: Float,
        moveContext: MoveContext,
    ): Boolean = when (mode) {
        DeleteGestureMode.SWIPE_UP -> totalDeltaY > -moveContext.deleteVerticalTriggerPx / 2
        DeleteGestureMode.SWIPE_DOWN -> totalDeltaY < moveContext.deleteVerticalTriggerPx / 2
        DeleteGestureMode.SWIPE_LEFT -> totalDeltaX > -moveContext.deleteSwipeTriggerPx / 2
        DeleteGestureMode.NONE -> false
    }

    private fun buildDeleteSwipeSelectCommands(
        state: PointerState,
        totalDeltaX: Float,
        totalDeltaY: Float,
        absTotalX: Float,
        absTotalY: Float,
        moveContext: MoveContext,
    ): List<PointerCommand> {
        val effectiveCursor = state.effectiveDeleteCursor
        val commands = mutableListOf<PointerCommand>(PointerCommand.CancelTimers(state.pointerId))
        val slideDist = when (state.deleteGestureMode) {
            DeleteGestureMode.SWIPE_LEFT -> -totalDeltaX - moveContext.deleteSwipeTriggerPx
            DeleteGestureMode.SWIPE_UP -> -totalDeltaY - moveContext.deleteVerticalTriggerPx
            DeleteGestureMode.SWIPE_DOWN -> totalDeltaY - moveContext.deleteVerticalTriggerPx
            DeleteGestureMode.NONE -> 0f
        }
        if (slideDist < 0) {
            if (state.deleteSwipeStep != 0) {
                state.deleteSwipeStep = 0
                commands += PointerCommand.DeleteCancel(effectiveCursor)
            }
            commands += PointerCommand.DismissPopup
        } else {
            val maxChars = effectiveCursor
            val targetStep = if (maxChars > 0) {
                ((slideDist / moveContext.deleteStepPx).toInt() + 1).coerceAtMost(maxChars)
            } else {
                ((slideDist / moveContext.deleteStepPx).toInt() + 1)
            }
            if (targetStep != state.deleteSwipeStep) {
                val shouldVibrate = targetStep > state.deleteSwipeStep
                state.deleteSwipeStep = targetStep
                commands += PointerCommand.DeleteSelect(state.deleteSwipeStep, effectiveCursor)
                if (shouldVibrate) commands += PointerCommand.Haptic(HapticEvent.STEP)

                state.currentKey?.let { key ->
                    val tipText = if (state.deleteSwipeStep > 0) {
                        runCatching { Launcher.instance.context.resources.getQuantityString(R.plurals.delete_tip_selected_chars, state.deleteSwipeStep, state.deleteSwipeStep) }
                            .getOrDefault("Delete ${state.deleteSwipeStep} chars")
                    } else {
                        runCatching { Launcher.instance.context.getString(R.string.delete_action_swipe_select) }
                            .getOrDefault("Select & Delete")
                    }
                    commands += PointerCommand.PopupShowText(tipText, key, R.drawable.ic_gesture_select)
                }
            }
        }
        if (absTotalX < moveContext.deleteSwipeTriggerPx / 2 && absTotalY < moveContext.deleteVerticalTriggerPx / 2) {
            if (state.deleteSwipeStep != 0) {
                state.deleteSwipeStep = 0
                commands += PointerCommand.DeleteCancel(effectiveCursor)
            }
            commands += PointerCommand.DismissPopup
            state.deleteGestureMode = DeleteGestureMode.NONE
            state.activeDeleteAction = DeleteGestureAction.NONE
        }
        return commands
    }

    private fun buildDeletePopupActionCommands(
        state: PointerState,
        iconRes: Int,
        text: String,
        key: SoftKey,
        shouldReset: Boolean,
    ): List<PointerCommand> {
        val commands = mutableListOf<PointerCommand>(PointerCommand.CancelTimers(state.pointerId))
        if (state.deleteSwipeStep != 0) {
            state.deleteSwipeStep = 0
            commands += PointerCommand.DeleteCancel(state.effectiveDeleteCursor)
        }
        commands += PointerCommand.PopupShowText(text, key, iconRes)
        if (shouldReset) {
            state.deleteGestureMode = DeleteGestureMode.NONE
            state.activeDeleteAction = DeleteGestureAction.NONE
            commands += PointerCommand.DismissPopup
        }
        return commands
    }

    // yagni: space-adjacent heuristic tailored for QWERTY bottom row letters; upgrade when supporting non-QWERTY layouts like AZERTY/QWERTZ
    private fun isSpaceAdjacentBottomKey(key: SoftKey): Boolean =
        key.code == KeyEvent.KEYCODE_N || key.code == KeyEvent.KEYCODE_M

    companion object {
        const val VOICE_ARM_TIMEOUT_MS = 180L
        const val DEFAULT_SYMBOL_MIN_SWIPE_DP = 20
        private const val DEFAULT_KEY_DIMENSION = 100f
        private const val FLICK_CONE_FACTOR = 1.19f // cot(40°) ≈ 1.19 => |Δx| * 1.19 < -Δy (±40° cone, balances thumb arc with mis-touch protection)
        private const val FLICK_PULLBACK_RATIO = 0.50f
        // yagni: caps flick threshold is intentionally decoupled from symbol slide-up sensitivity tiers; upgrade when caps flick sensitivity needs user tuning
        private const val CAPS_DEFAULT_TRIGGER_RATIO = 0.40f
        private const val BOTTOM_ROW_DEFAULT_TRIGGER_RATIO = 0.55f
        private const val BOTTOM_ROW_SPACE_ADJACENT_TRIGGER_RATIO = 0.70f
        private const val NON_BOTTOM_ROW_UP_RATIO_BONUS = 0.15f
        private const val BOTTOM_ROW_RATIO_BONUS = 0.15f
        private const val BOTTOM_ROW_SPACE_ADJACENT_RATIO_BONUS = 0.30f
        private const val BOTTOM_ROW_TOP_EXIT_RATIO = 0.10f
    }
}
