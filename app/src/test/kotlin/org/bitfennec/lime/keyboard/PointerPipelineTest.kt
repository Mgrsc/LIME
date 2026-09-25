package org.bitfennec.lime.keyboard

import android.view.KeyEvent
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.prefs.behavior.DeleteGestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import org.bitfennec.lime.prefs.behavior.PopupMenuMode

class PointerPipelineTest {
    @Test
    fun spaceDragCancelsVoiceTimer() {
        val pipeline = PointerPipeline()
        val space = SoftKey(KeyEvent.KEYCODE_SPACE, " ")

        pipeline.onDown(space, 0f, 0f, 0, downContext())
        val commands = pipeline.onScroll(0, 5f, 0f, -5f, 0f, moveContext(pxPerMm = 1f))

        assertTrue(commands.any { it is PointerCommand.CancelTimers })
        assertFalse(commands.any { it is PointerCommand.VoiceStart })
        val up = pipeline.onUp()
        assertFalse(up.any { it is PointerCommand.Tap })
    }

    @Test
    fun voiceActiveConsumesMoveUntilUp() {
        val pipeline = PointerPipeline()
        val space = SoftKey(KeyEvent.KEYCODE_SPACE, " ")

        pipeline.onDown(space, 0f, 0f, 0, downContext())
        val longPress = pipeline.onLongPress(pointerId = 0, keyLabel = " ", keySmallLabel = "", keyboardSymbolEnabled = true)
        pipeline.confirmVoiceStarted(started = true)
        val move = pipeline.onScroll(0, 0f, 40f, 0f, -40f, moveContext())
        val up = pipeline.onUp()

        assertTrue(longPress.any { it is PointerCommand.VoiceStart })
        assertEquals(listOf(PointerCommand.VoiceMove(0f, 40f)), move)
        assertTrue(up.contains(PointerCommand.VoiceEnd))
    }

    @Test
    fun spaceLongPressDoesNotActivateTextPopup() {
        val pipeline = PointerPipeline()
        val space = SoftKey(KeyEvent.KEYCODE_SPACE, "空格")

        pipeline.onDown(space, 0f, 0f, 0, downContext())
        pipeline.onLongPress(pointerId = 0, keyLabel = "空格", keySmallLabel = "", keyboardSymbolEnabled = true)
        pipeline.confirmVoiceStarted(started = true)

        assertFalse(pipeline.isTextPopupActive())
        assertTrue(pipeline.isVoiceActive())

        val move = pipeline.onScroll(0, 10f, 20f, -10f, -20f, moveContext())
        assertEquals(listOf(PointerCommand.VoiceMove(10f, 20f)), move)
    }

    @Test
    fun voiceModalOnlyRespondsToOwnerPointer() {
        val pipeline = PointerPipeline()
        val space = SoftKey(KeyEvent.KEYCODE_SPACE, " ")

        pipeline.onDown(space, 0f, 0f, 0, downContext())
        pipeline.onLongPress(pointerId = 0, " ", "", keyboardSymbolEnabled = true)
        pipeline.confirmVoiceStarted(started = true)

        // Active non-owner pointer down, moves and cancels -> should not emit VoiceMove or cancel voice
        pipeline.onDown(key = testKey(KeyEvent.KEYCODE_A, "a"), x = 50f, y = 50f, pointerId = 1, context = downContext())
        val commandsPointer1 = pipeline.onScroll(pointerId = 1, currentX = 5f, currentY = 5f, distanceX = 0f, distanceY = 0f, moveContext())
        assertTrue(commandsPointer1.none { it is PointerCommand.VoiceMove })
        assertTrue(commandsPointer1.none { it is PointerCommand.VoiceCancel })
        assertTrue(pipeline.isVoiceActive())

        // Owner pointer moves -> emits VoiceMove
        val commandsPointer0 = pipeline.onScroll(pointerId = 0, currentX = 10f, currentY = 20f, distanceX = 0f, distanceY = 0f, moveContext())
        assertEquals(listOf(PointerCommand.VoiceMove(10f, 20f)), commandsPointer0)

        // Non-owner pointer cancels -> voice remains active
        val cancelPointer1 = pipeline.onCancel(pointerId = 1)
        assertTrue(cancelPointer1.any { it is PointerCommand.CancelTimers })
        assertTrue(pipeline.isVoiceActive())

        // Owner pointer ends -> VoiceEnd
        val upCommands = pipeline.onUp(pointerId = 0)
        assertTrue(upCommands.contains(PointerCommand.VoiceEnd))
        assertFalse(pipeline.isVoiceActive())
    }

    @Test
    fun nonOwnerTapDuringVoiceDoesNotEmitTap() {
        val pipeline = PointerPipeline()
        val space = SoftKey(KeyEvent.KEYCODE_SPACE, " ")
        val keyA = testKey(KeyEvent.KEYCODE_A, "a")

        pipeline.onDown(space, 0f, 0f, 0, downContext())
        pipeline.onLongPress(pointerId = 0, " ", "", keyboardSymbolEnabled = true)
        pipeline.confirmVoiceStarted(started = true)

        // Non-owner pointer taps 'A' while voice is active
        val down1 = pipeline.onDown(key = keyA, x = 50f, y = 50f, pointerId = 1, context = downContext())
        assertEquals(emptyList<PointerCommand>(), down1)

        val up1 = pipeline.onUp(pointerId = 1)
        assertFalse(up1.any { it is PointerCommand.Tap })
        assertTrue(pipeline.isVoiceActive())

        // Owner pointer lifts and finishes voice
        val up0 = pipeline.onUp(pointerId = 0)
        assertTrue(up0.contains(PointerCommand.VoiceEnd))
        assertFalse(pipeline.isVoiceActive())
    }

    @Test
    fun deleteSwipeLeftSelectsThenCommits() {
        val pipeline = PointerPipeline()
        val delete = SoftKey(KeyEvent.KEYCODE_DEL, "")

        pipeline.onDown(delete, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, -1f, 0f, 1f, 0f, moveContext())
        val move = pipeline.onScroll(0, -31f, 0f, 30f, 0f, moveContext())
        val up = pipeline.onUp()

        assertTrue(move.any { it == PointerCommand.DeleteSelect(charCount = 2, initialCursor = 5) })
        assertTrue(move.contains(PointerCommand.Haptic(org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent.STEP)))
        assertTrue(up.any { it == PointerCommand.DeleteCommit(charCount = 2, initialCursor = 5) })
    }

    @Test
    fun deleteSwipeStopsRepeatFallback() {
        val pipeline = PointerPipeline()
        val delete = SoftKey(KeyEvent.KEYCODE_DEL, "")

        pipeline.onDown(delete, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, -1f, 0f, 1f, 0f, moveContext())
        pipeline.onScroll(0, -31f, 0f, 30f, 0f, moveContext())

        assertFalse(pipeline.shouldRepeatDelete())
        assertEquals(null, pipeline.directionRepeatKey())
    }

    @Test
    fun deleteSwipeUpClearsAll() {
        val pipeline = PointerPipeline()
        val delete = SoftKey(KeyEvent.KEYCODE_DEL, "")

        pipeline.onDown(delete, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, 0f, -1f, 0f, 1f, moveContext())
        val move = pipeline.onScroll(0, 0f, -31f, 0f, 30f, moveContext())
        val up = pipeline.onUp()

        assertTrue(move.any { it is PointerCommand.PopupShowText && it.text == "Clear All" })
        assertTrue(up.contains(PointerCommand.DeleteClearAll))
    }

    @Test
    fun deleteSwipeDownUndoReverts() {
        val pipeline = PointerPipeline()
        val delete = SoftKey(KeyEvent.KEYCODE_DEL, "")

        pipeline.onDown(delete, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, moveContext())
        val move = pipeline.onScroll(0, 0f, 31f, 0f, -30f, moveContext())
        val up = pipeline.onUp()

        assertTrue(move.any { it is PointerCommand.PopupShowText && it.text == "Undo Delete" })
        assertTrue(up.contains(PointerCommand.DeleteUndoRevert))
    }

    @Test
    fun deleteCustomMappingToPunctuation() {
        val pipeline = PointerPipeline()
        val delete = SoftKey(KeyEvent.KEYCODE_DEL, "")
        val customContext = moveContext().copy(
            deleteSwipeDownAction = DeleteGestureAction.TO_PUNCTUATION
        )

        pipeline.onDown(delete, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, customContext)
        val move = pipeline.onScroll(0, 0f, 31f, 0f, -30f, customContext)
        val up = pipeline.onUp()

        assertTrue(move.any { it is PointerCommand.PopupShowText && it.text == "Delete to Punctuation" })
        assertTrue(up.contains(PointerCommand.DeleteToPunctuation))
    }

    @Test
    fun deleteDisabledSwipeDoesNothing() {
        val pipeline = PointerPipeline()
        val delete = SoftKey(KeyEvent.KEYCODE_DEL, "")
        val disabledContext = moveContext().copy(
            deleteSwipeUpAction = DeleteGestureAction.NONE
        )

        pipeline.onDown(delete, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, 0f, -1f, 0f, 1f, disabledContext)
        val move = pipeline.onScroll(0, 0f, -31f, 0f, 30f, disabledContext)
        val up = pipeline.onUp()

        assertFalse(move.any { it is PointerCommand.PopupShowText })
        assertFalse(up.contains(PointerCommand.DeleteClearAll))
    }

    @Test
    fun tapUsesDownKeyWhenPointerEndsElsewhere() {
        val pipeline = PointerPipeline()
        val downKey = SoftKey(KeyEvent.KEYCODE_A, "A")

        pipeline.onDown(downKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 120f, 0f, -120f, 0f, moveContext())
        val commands = pipeline.onUp()

        assertTrue(commands.contains(PointerCommand.Tap(downKey)))
    }

    @Test
    fun flickSwipeUpArmsPreviewAndCommitsOnUp() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_Q, "q", "1", 100, 100)

        pipeline.onDown(key, 50f, 50f, 0, downContext())
        pipeline.onScroll(0, 50f, 49f, 0f, 1f, moveContext())
        val moveCommands = pipeline.onScroll(0, 50f, -10f, 0f, 59f, moveContext())

        assertTrue(pipeline.isFlickArmed())
        assertTrue(moveCommands.any { it is PointerCommand.CancelTimers })
        assertTrue(moveCommands.any { it is PointerCommand.PopupShowText && it.text == "1" })

        val upCommands = pipeline.onUp()
        assertFalse(pipeline.isFlickArmed())
        assertTrue(upCommands.any { it == PointerCommand.FlickSymbol(PopupMenuMode.Text to "1") })
        assertFalse(upCommands.any { it is PointerCommand.Tap })
    }

    @Test
    fun slightVerticalJitterDoesNotTriggerFlick() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_Q, "q", "1", 100, 100)

        pipeline.onDown(key, 50f, 50f, 0, downContext())
        pipeline.onScroll(0, 50f, 48f, 0f, 2f, moveContext())
        // Moved only 15px up (trigger is 100 * 0.55 = 55px)
        val moveCommands = pipeline.onScroll(0, 50f, 35f, 0f, 13f, moveContext())

        assertFalse(pipeline.isFlickArmed())
        assertFalse(moveCommands.any { it is PointerCommand.FlickSymbol })

        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun horizontalOrDiagonalDriftDoesNotTriggerFlick() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_Q, "q", "1", 100, 100)

        pipeline.onDown(key, 50f, 50f, 0, downContext())
        pipeline.onScroll(0, 51f, 49f, -1f, 1f, moveContext())
        // Diagonal: deltaX = +50, deltaY = -35. Angle is ~55 deg (> 45 deg).
        pipeline.onScroll(0, 100f, 15f, -49f, 34f, moveContext())

        assertFalse(pipeline.isFlickArmed())

        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun flickPullBackCancelsFlickAndRestoresTap() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_Q, "q", "1", 100, 100)

        pipeline.onDown(key, 50f, 50f, 0, downContext())
        pipeline.onScroll(0, 50f, 49f, 0f, 1f, moveContext())
        // Swipe up 60px -> armed
        pipeline.onScroll(0, 50f, -10f, 0f, 59f, moveContext())
        assertTrue(pipeline.isFlickArmed())

        // Pull back to 10px from origin -> pullback threshold (< 55 * 0.5 = 27.5px)
        val pullBackCommands = pipeline.onScroll(0, 50f, 40f, 0f, -50f, moveContext())
        assertFalse(pipeline.isFlickArmed())
        assertTrue(pullBackCommands.any { it is PointerCommand.PopupShowText && it.text == "q" })

        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun flickDisabledWhenKeyboardSymbolDisabled() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_Q, "q", "1", 100, 100)
        val disabledContext = moveContext().copy(keyboardSymbolEnabled = false)

        pipeline.onDown(key, 50f, 50f, 0, downContext())
        pipeline.onScroll(0, 50f, 49f, 0f, 1f, disabledContext)
        pipeline.onScroll(0, 50f, 5f, 0f, 44f, disabledContext)

        assertFalse(pipeline.isFlickArmed())
        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun flickFastSwipeWhenKeyboardSymbolDisabledDoesNotSwallowTap() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_Q, "q", "1", 100, 100)
        val disabledContext = moveContext().copy(keyboardSymbolEnabled = false)

        pipeline.onDown(key, 50f, 50f, 0, downContext())
        // Fast swipe upwards: relDiffY will exceed triggerDistance * 2 (100 * 0.40 * 2 = 80px)
        pipeline.onScroll(0, 50f, 49f, 0f, 1f, disabledContext)
        val moveCommands = pipeline.onScroll(0, 50f, -150f, 0f, 199f, disabledContext)

        assertEquals(emptyList<PointerCommand>(), moveCommands)
        assertFalse(pipeline.isFlickArmed())
        assertFalse(pipeline.isLongPressActive())

        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun bottomRowRollingInsideKeyDoesNotArmFlickAndOutputsTap() {
        val pipeline = PointerPipeline()
        val key = testKey(
            code = KeyEvent.KEYCODE_Z,
            label = "z",
            smallLabel = ",",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = true,
        )

        // Down at (50, 190). Inside key bounds [100, 200].
        pipeline.onDown(key, 50f, 190f, 0, downContext())
        pipeline.onScroll(0, 50f, 189f, 0f, 1f, moveContext())
        // Roll up 50px to currentY = 140. Displacement is 50% (< 55%), AND currentY (140) inside key
        val moveCommands = pipeline.onScroll(0, 50f, 140f, 0f, 49f, moveContext())

        assertFalse(pipeline.isFlickArmed())
        assertFalse(moveCommands.any { it is PointerCommand.FlickSymbol })

        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun bottomRowLandingInHitTopMarginAndRollingDoesNotArmFlick() {
        val pipeline = PointerPipeline()
        val key = testKey(
            code = KeyEvent.KEYCODE_N,
            label = "n",
            smallLabel = "！",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = true,
        )

        // Down in hitTop absorption margin above layout mTop: (50, 90) vs layout mTop = 100
        pipeline.onDown(key, 50f, 90f, 0, downContext())
        pipeline.onScroll(0, 50f, 89f, 0f, 1f, moveContext())
        // Slight roll up 40px to currentY = 50. Displacement is 40px (< 70px threshold)
        val moveCommands = pipeline.onScroll(0, 50f, 50f, 0f, 39f, moveContext())

        assertFalse(pipeline.isFlickArmed())
        assertFalse(moveCommands.any { it is PointerCommand.FlickSymbol })

        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun bottomRowCrossingTopBorderArmsFlickAndCommitsOnUp() {
        val pipeline = PointerPipeline()
        val key = testKey(
            code = KeyEvent.KEYCODE_Z,
            label = "z",
            smallLabel = ",",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = true,
        )

        // Down at (50, 150).
        pipeline.onDown(key, 50f, 150f, 0, downContext())
        // Swipe up 60px with natural ~38° thumb arc (dx = 46px, dy = -60px => currentX = 96, currentY = 90)
        // Displacement is 60% (>= 55%) and currentY (90) exits top border by 10px (>= 0.10 * keyHeight)
        val moveCommands = pipeline.onScroll(0, 96f, 90f, -46f, 60f, moveContext())

        assertTrue(pipeline.isFlickArmed())
        assertTrue(moveCommands.any { it is PointerCommand.PopupShowText && it.text == "," })

        val upCommands = pipeline.onUp()
        assertFalse(pipeline.isFlickArmed())
        assertTrue(upCommands.any { it == PointerCommand.FlickSymbol(PopupMenuMode.Text to ",") })
        assertFalse(upCommands.any { it is PointerCommand.Tap })
    }

    @Test
    fun spaceAdjacentBottomRowKeyRequiresStrictDisplacement() {
        val pipeline = PointerPipeline()
        val key = testKey(
            code = KeyEvent.KEYCODE_N,
            label = "n",
            smallLabel = "！",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = true,
        )

        // Down at (50, 150). Spacebar roll crossing top border with 60px displacement (< 70px threshold)
        pipeline.onDown(key, 50f, 150f, 0, downContext())
        val moveCommands = pipeline.onScroll(0, 50f, 90f, 0f, 60f, moveContext())

        assertFalse(pipeline.isFlickArmed())
        assertFalse(moveCommands.any { it is PointerCommand.FlickSymbol })

        val upCommands = pipeline.onUp()
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
    }

    @Test
    fun fastSwipeArmsOnSingleMoveEventWithoutBeingSwallowed() {
        val pipeline = PointerPipeline()
        val key = testKey(
            code = KeyEvent.KEYCODE_N,
            label = "n",
            smallLabel = "！",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = true,
        )

        pipeline.onDown(key, 50f, 150f, 0, downContext())
        // Fast swipe with only 1 move event reaching 75px (>= 70px)
        val moveCommands = pipeline.onScroll(0, 50f, 75f, 0f, 75f, moveContext())

        assertTrue(pipeline.isFlickArmed())
        assertTrue(moveCommands.any { it is PointerCommand.PopupShowText && it.text == "！" })

        val upCommands = pipeline.onUp()
        assertFalse(pipeline.isFlickArmed())
        assertTrue(upCommands.any { it == PointerCommand.FlickSymbol(PopupMenuMode.Text to "！") })
    }

    @Test
    fun fastFlingLargeStrokeWithThumbArcArmsAndCommits() {
        val pipeline = PointerPipeline()
        val key = testKey(
            code = KeyEvent.KEYCODE_C,
            label = "c",
            smallLabel = ":",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = true,
        )

        // Down at (50, 150). Large fast fling: dy = -200px (to -50), dx = +120px (to 170)
        // Angle is ~31° (within 40° cone: 120 * 1.19 = 142.8 < 200). Exceeds 1.0 key width.
        pipeline.onDown(key, 50f, 150f, 0, downContext())
        val moveCommands = pipeline.onScroll(0, 170f, -50f, -120f, 200f, moveContext())

        assertTrue(pipeline.isFlickArmed())
        assertTrue(moveCommands.any { it is PointerCommand.PopupShowText && it.text == ":" })

        val upCommands = pipeline.onUp()
        assertFalse(pipeline.isFlickArmed())
        assertTrue(upCommands.any { it == PointerCommand.FlickSymbol(PopupMenuMode.Text to ":") })
    }

    @Test
    fun armedFlickCancelDoesNotCommit() {
        val key = testKey(
            code = KeyEvent.KEYCODE_Z,
            label = "z",
            smallLabel = ",",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = true,
        )

        // 1. Single pointer onCancel(pointerId) branch
        val pipeline1 = PointerPipeline()
        pipeline1.onDown(key, 50f, 150f, 0, downContext())
        pipeline1.onScroll(0, 90f, 80f, -40f, 70f, moveContext())
        assertTrue(pipeline1.isFlickArmed())

        val cancelCommandsSingle = pipeline1.onCancel(0)
        assertFalse(pipeline1.isFlickArmed())
        assertFalse(cancelCommandsSingle.any { it is PointerCommand.FlickSymbol })
        assertTrue(cancelCommandsSingle.any { it is PointerCommand.DismissPopup })
        assertFalse(cancelCommandsSingle.any { it is PointerCommand.Tap })

        // 2. Global / multi-pointer onCancel(null) branch
        val pipeline2 = PointerPipeline()
        pipeline2.onDown(key, 50f, 150f, 0, downContext())
        pipeline2.onScroll(0, 90f, 80f, -40f, 70f, moveContext())
        assertTrue(pipeline2.isFlickArmed())

        val cancelCommandsGlobal = pipeline2.onCancel(null)
        assertFalse(pipeline2.isFlickArmed())
        assertFalse(cancelCommandsGlobal.any { it is PointerCommand.FlickSymbol })
        assertTrue(cancelCommandsGlobal.any { it is PointerCommand.DismissPopup })
        assertFalse(cancelCommandsGlobal.any { it is PointerCommand.Tap })
    }

    @Test
    fun topRowRollingBelowNewThresholdOutputsTap() {
        val pipeline = PointerPipeline()
        val key = testKey(
            code = KeyEvent.KEYCODE_Q,
            label = "q",
            smallLabel = "1",
            width = 100,
            height = 100,
            top = 100,
            isBottomRow = false,
        )

        // Down at (50, 180).
        pipeline.onDown(key, 50f, 180f, 0, downContext())
        pipeline.onScroll(0, 50f, 179f, 0f, 1f, moveContext())
        // Swipe up 45px to currentY = 135. Displacement is 45% (< 55%), currentY (135) > mTop (100)
        val moveCommands = pipeline.onScroll(0, 50f, 135f, 0f, 44f, moveContext())

        assertFalse(pipeline.isFlickArmed())
        assertFalse(moveCommands.any { it is PointerCommand.PopupShowText && it.text == "1" })

        val upCommands = pipeline.onUp()
        assertFalse(pipeline.isFlickArmed())
        assertFalse(upCommands.any { it is PointerCommand.FlickSymbol })
        assertTrue(upCommands.any { it == PointerCommand.Tap(key) })
    }

    @Test
    fun dualPointersSequentialTapBothKeysEmitted() {
        val pipeline = PointerPipeline()
        val keyA = testKey(KeyEvent.KEYCODE_A, "a", "")
        val keyB = testKey(KeyEvent.KEYCODE_B, "b", "")

        // Pointer 0 down on 'A'
        val down0 = pipeline.onDown(key = keyA, x = 10f, y = 10f, pointerId = 0, context = downContext())
        assertEquals(1, pipeline.activePointerCount())
        assertTrue(down0.any { it is PointerCommand.ScheduleLongPress && it.pointerId == 0 })

        // Pointer 1 down on 'B' while pointer 0 is still held -> emits Tap(keyA) on roll-over
        val down1 = pipeline.onDown(key = keyB, x = 50f, y = 10f, pointerId = 1, context = downContext())
        assertEquals(2, pipeline.activePointerCount())
        assertTrue(down1.any { it is PointerCommand.Tap && it.key == keyA })
        assertTrue(down1.any { it is PointerCommand.ScheduleLongPress && it.pointerId == 1 })

        // Pointer 0 lifts first -> does not double-commit, emits CancelTimers(0)
        val up0 = pipeline.onUp(pointerId = 0)
        assertEquals(1, pipeline.activePointerCount())
        assertFalse(up0.any { it is PointerCommand.Tap })
        assertTrue(up0.any { it is PointerCommand.CancelTimers && it.pointerId == 0 })

        // Pointer 1 lifts second -> emits Tap(keyB) and CancelTimers(1)
        val up1 = pipeline.onUp(pointerId = 1)
        assertEquals(0, pipeline.activePointerCount())
        assertTrue(up1.any { it is PointerCommand.Tap && it.key == keyB })
        assertTrue(up1.any { it is PointerCommand.CancelTimers && it.pointerId == 1 })
    }

    @Test
    fun pointerCancelIsolationOnlyCancelsTargetPointer() {
        val pipeline = PointerPipeline()
        val keyA = testKey(KeyEvent.KEYCODE_A, "a", "")
        val space = SoftKey(KeyEvent.KEYCODE_SPACE, " ")

        // Pointer 0 down on 'A'
        pipeline.onDown(key = keyA, x = 10f, y = 10f, pointerId = 0, context = downContext())
        // Pointer 1 down on non-typing key (Space) does not roll-over commit 'A'
        pipeline.onDown(key = space, x = 50f, y = 10f, pointerId = 1, context = downContext())

        // Cancel only pointer 1
        val cancel1 = pipeline.onCancel(pointerId = 1)
        assertEquals(1, pipeline.activePointerCount())
        assertTrue(cancel1.any { it is PointerCommand.CancelTimers && it.pointerId == 1 })
        assertFalse(cancel1.any { it is PointerCommand.Tap })

        // Pointer 0 should still be active and emit Tap(keyA) on up
        val up0 = pipeline.onUp(pointerId = 0)
        assertEquals(0, pipeline.activePointerCount())
        assertTrue(up0.any { it is PointerCommand.Tap && it.key == keyA })
        assertTrue(up0.any { it is PointerCommand.CancelTimers && it.pointerId == 0 })
    }

    @Test
    fun cancelTimersNullOnlyOnGlobalCancel() {
        val pipeline = PointerPipeline()
        val keyA = testKey(KeyEvent.KEYCODE_A, "a", "")
        val keyB = testKey(KeyEvent.KEYCODE_B, "b", "")

        pipeline.onDown(key = keyA, x = 10f, y = 10f, pointerId = 0, context = downContext())
        pipeline.onDown(key = keyB, x = 50f, y = 10f, pointerId = 1, context = downContext())

        // Single pointer up only cancels its own timer
        val up0 = pipeline.onUp(pointerId = 0)
        assertTrue(up0.any { it is PointerCommand.CancelTimers && it.pointerId == 0 })
        assertFalse(up0.any { it is PointerCommand.CancelTimers && it.pointerId == null })

        // Global cancel (pointerId = null) cancels all timers with null
        val cancelAll = pipeline.onCancel(null)
        assertTrue(cancelAll.any { it is PointerCommand.CancelTimers && it.pointerId == null })
    }

    @Test
    fun flickAndTapInParallel() {
        val pipeline = PointerPipeline()
        val keyQ = testKey(KeyEvent.KEYCODE_Q, "q", "1", 100, 100)
        val keyW = testKey(KeyEvent.KEYCODE_W, "w", "2", 100, 100)

        // Pointer 0 flicks on 'Q'
        pipeline.onDown(key = keyQ, x = 50f, y = 50f, pointerId = 0, context = downContext())
        pipeline.onScroll(pointerId = 0, currentX = 50f, currentY = 49f, distanceX = 0f, distanceY = 1f, context = moveContext())
        val flickMove = pipeline.onScroll(pointerId = 0, currentX = 50f, currentY = -10f, distanceX = 0f, distanceY = 59f, context = moveContext())
        assertTrue(pipeline.isFlickArmed(pointerId = 0))
        assertTrue(flickMove.any { it is PointerCommand.PopupShowText && it.text == "1" })

        // In parallel, pointer 1 taps 'W'
        pipeline.onDown(key = keyW, x = 150f, y = 50f, pointerId = 1, context = downContext())
        val up1 = pipeline.onUp(pointerId = 1)
        assertTrue(up1.any { it is PointerCommand.Tap && it.key == keyW })

        // Pointer 0 lifts and commits flick symbol
        val up0 = pipeline.onUp(pointerId = 0)
        assertTrue(up0.any { it == PointerCommand.FlickSymbol(PopupMenuMode.Text to "1") })
        assertFalse(up0.any { it is PointerCommand.Tap })
    }

    @Test
    fun longPressPopupCommitOnUpReturnsTrue() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_E, "e", "3")
        pipeline.onDown(key, 50f, 50f, pointerId = 0, context = downContext())
        val longPress = pipeline.onLongPress(pointerId = 0, keyLabel = "e", keySmallLabel = "3", keyboardSymbolEnabled = true)
        assertTrue(longPress.any { it is PointerCommand.PopupShowKey })

        val upCommands = pipeline.onUp(pointerId = 0)
        assertFalse(upCommands.any { it is PointerCommand.Tap })

        // Must return true on first consume after up, then false on subsequent calls (once semantics)
        assertTrue(pipeline.consumeLongPressCommit(pointerId = 0))
        assertFalse(pipeline.consumeLongPressCommit(pointerId = 0))
    }

    @Test
    fun longPressCancelledDoesNotCommit() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_E, "e", "3")
        pipeline.onDown(key, 50f, 50f, pointerId = 0, context = downContext())
        pipeline.onLongPress(pointerId = 0, keyLabel = "e", keySmallLabel = "3", keyboardSymbolEnabled = true)

        pipeline.onCancel(pointerId = 0)
        assertFalse(pipeline.consumeLongPressCommit(pointerId = 0))
    }

    @Test
    fun longPressFallbackDismissPreviewDoesNotCommitOnUp() {
        val pipeline = PointerPipeline()
        val key = SoftKey(0, "")
        pipeline.onDown(key, 50f, 50f, pointerId = 0, context = downContext())
        val longPress = pipeline.onLongPress(pointerId = 0, keyLabel = "", keySmallLabel = "", keyboardSymbolEnabled = false)
        assertTrue(longPress.any { it is PointerCommand.DismissPreview })

        // DismissPreview handler drains consumeLongPressCommit while pointer is still down
        assertTrue(pipeline.consumeLongPressCommit(pointerId = 0))

        // After finger up, it should not commit again
        pipeline.onUp(pointerId = 0)
        assertFalse(pipeline.consumeLongPressCommit(pointerId = 0))
    }

    @Test
    fun voiceLongPressDoesNotCommitOnUp() {
        val pipeline = PointerPipeline()
        val space = SoftKey(KeyEvent.KEYCODE_SPACE, " ")
        pipeline.onDown(space, 0f, 0f, pointerId = 0, context = downContext())
        pipeline.onLongPress(pointerId = 0, keyLabel = " ", keySmallLabel = "", keyboardSymbolEnabled = true)
        pipeline.confirmVoiceStarted(started = true)

        val up = pipeline.onUp(pointerId = 0)
        assertTrue(up.contains(PointerCommand.VoiceEnd))
        assertFalse(pipeline.consumeLongPressCommit(pointerId = 0))
    }

    @Test
    fun longPressCommitMultiPointerIsolation() {
        val pipeline = PointerPipeline()
        val keyE = testKey(KeyEvent.KEYCODE_E, "e", "3")
        val keyA = testKey(KeyEvent.KEYCODE_A, "a", "")

        // Pointer 0 long presses 'E'
        pipeline.onDown(keyE, 50f, 50f, pointerId = 0, context = downContext())
        pipeline.onLongPress(pointerId = 0, keyLabel = "e", keySmallLabel = "3", keyboardSymbolEnabled = true)

        // Pointer 1 taps 'A'
        pipeline.onDown(keyA, 10f, 10f, pointerId = 1, context = downContext())
        val up1 = pipeline.onUp(pointerId = 1)
        assertTrue(up1.any { it is PointerCommand.Tap && it.key == keyA })
        assertFalse(pipeline.consumeLongPressCommit(pointerId = 1))

        // Pointer 0 lifts and commits
        val up0 = pipeline.onUp(pointerId = 0)
        assertFalse(up0.any { it is PointerCommand.Tap })
        assertTrue(pipeline.consumeLongPressCommit(pointerId = 0))
        assertFalse(pipeline.consumeLongPressCommit(pointerId = 0))
    }

    @Test
    fun multiTouchAlternateTypingEmitsBothKeys() {
        val pipeline = PointerPipeline()
        val keyS = testKey(KeyEvent.KEYCODE_S, "s", "")
        val keyH = testKey(KeyEvent.KEYCODE_H, "h", "")

        val downS = pipeline.onDown(keyS, 20f, 20f, pointerId = 0, context = downContext())
        assertFalse(downS.any { it is PointerCommand.Tap })

        // When key H goes down, key S is committed on roll-over
        val downH = pipeline.onDown(keyH, 80f, 20f, pointerId = 1, context = downContext())
        assertTrue(downH.any { it is PointerCommand.Tap && it.key == keyS })

        // Pointer 0 lifts first, does not double-commit
        val up0 = pipeline.onUp(pointerId = 0)
        assertFalse(up0.any { it is PointerCommand.Tap })

        // Pointer 1 lifts second, commits key H
        val up1 = pipeline.onUp(pointerId = 1)
        assertTrue(up1.any { it is PointerCommand.Tap && it.key == keyH })
    }

    @Test
    fun multiTouchReverseLiftOrderPreservesDownOrder() {
        val pipeline = PointerPipeline()
        val keyA = testKey(KeyEvent.KEYCODE_A, "a", "")
        val keyL = testKey(KeyEvent.KEYCODE_L, "l", "")

        // Press A, then press L while A is held
        pipeline.onDown(keyA, 20f, 20f, pointerId = 0, context = downContext())
        val downL = pipeline.onDown(keyL, 80f, 20f, pointerId = 1, context = downContext())
        // Key A committed on down of key L
        assertTrue(downL.any { it is PointerCommand.Tap && it.key == keyA })

        // Pointer 1 (L) lifts first (or together with A)
        val up1 = pipeline.onUp(pointerId = 1)
        assertTrue(up1.any { it is PointerCommand.Tap && it.key == keyL })

        // Pointer 0 (A) lifts second
        val up0 = pipeline.onUp(pointerId = 0)
        assertFalse(up0.any { it is PointerCommand.Tap })
    }

    @Test
    fun simultaneousTouchBothEmittedWithoutSwallowing() {
        val pipeline = PointerPipeline()
        val keyS = testKey(KeyEvent.KEYCODE_S, "s", "")
        val keyH = testKey(KeyEvent.KEYCODE_H, "h", "")

        pipeline.onDown(keyS, 20f, 20f, pointerId = 0, context = downContext())
        val downH = pipeline.onDown(keyH, 80f, 20f, pointerId = 1, context = downContext())
        assertTrue(downH.any { it is PointerCommand.Tap && it.key == keyS })

        // Both pointers lifted
        val up0 = pipeline.onUp(pointerId = 0)
        val up1 = pipeline.onUp(pointerId = 1)
        assertFalse(up0.any { it is PointerCommand.Tap })
        assertTrue(up1.any { it is PointerCommand.Tap && it.key == keyH })
    }

    @Test
    fun smallScrollDriftDoesNotAccumulateToSwallowTap() {
        val pipeline = PointerPipeline()
        val keyA = testKey(KeyEvent.KEYCODE_A, "a", "")

        pipeline.onDown(keyA, 50f, 50f, pointerId = 0, context = downContext())
        // Simulate small touch wobble across 20 frames totaling 100px drift (>40px trigger threshold)
        for (i in 1..20) {
            val cmds = pipeline.onScroll(pointerId = 0, currentX = 50f, currentY = 50f + i * 5f, distanceX = 0f, distanceY = 0f, context = moveContext())
            assertFalse(cmds.any { it is PointerCommand.PopupGesture })
        }
        val up = pipeline.onUp(pointerId = 0)
        assertTrue(up.any { it is PointerCommand.Tap && it.key == keyA })
    }

    @Test
    fun heldFirstKeyWithLongPressFiredDoesNotSwallowTapWhenSecondKeyArrives() {
        val pipeline = PointerPipeline()
        val keyS = testKey(KeyEvent.KEYCODE_S, "s", "")
        val keyH = testKey(KeyEvent.KEYCODE_H, "h", "")

        // Left thumb down on 's'
        pipeline.onDown(keyS, 20f, 20f, pointerId = 0, context = downContext())
        // 400ms passes, long-press timer triggers
        val longPressCmds = pipeline.onLongPress(pointerId = 0, keyLabel = "s", keySmallLabel = "", keyboardSymbolEnabled = false)
        assertTrue(longPressCmds.any { it is PointerCommand.PopupShowKey })

        // Right thumb comes down on 'h' -> multi-touch typing cancels & dismisses left thumb long-press
        val down1 = pipeline.onDown(keyH, 80f, 20f, pointerId = 1, context = downContext())
        assertTrue(down1.any { it is PointerCommand.CancelTimers && it.pointerId == 0 })
        assertTrue(down1.any { it is PointerCommand.DismissPreview && it.pointerId == 0 })

        assertTrue(down1.any { it is PointerCommand.Tap && it.key == keyS })

        // Reverse lift order must not reverse the text or commit the first key twice.
        val up1 = pipeline.onUp(pointerId = 1)
        val up0 = pipeline.onUp(pointerId = 0)
        val typed = (down1 + up1 + up0).filterIsInstance<PointerCommand.Tap>().map { it.key }
        assertEquals(listOf(keyS, keyH), typed)
        assertFalse(pipeline.consumeLongPressCommit(0))
    }

    @Test
    fun longPressUsesTheActualMenuWithOrWithoutSmallLabels() {
        for ((label, smallLabel, enabled) in listOf(
            Triple(".", "", true),
            Triple("，", "", true),
            Triple("1", "", true),
            Triple("a", "1", false),
            Triple("a", "1", true),
            Triple("unmapped", "", true),
        )) {
            val pipeline = PointerPipeline()
            val key = testKey(code = 0, label = label, smallLabel = smallLabel)
            pipeline.onDown(key, 50f, 50f, 0, downContext())
            val commands = pipeline.onLongPress(0, label, smallLabel, enabled)
            val hasMenu = label != "unmapped"
            assertEquals(label, hasMenu, commands.any { it is PointerCommand.PopupShowKey })
            assertEquals(label, !hasMenu, pipeline.onUp(0).any { it is PointerCommand.Tap })
            assertEquals(label, hasMenu, pipeline.consumeLongPressCommit(0))
            assertFalse(pipeline.consumeLongPressCommit(0))
        }
    }

    @Test
    fun isTypingKeyDistinguishesInputKeysFromFunctionKeys() {
        val pipeline = PointerPipeline()
        assertTrue(pipeline.isTypingKey(SoftKey(KeyEvent.KEYCODE_A, "a")))
        assertTrue(pipeline.isTypingKey(SoftKey(KeyEvent.KEYCODE_1, "1")))
        assertTrue(pipeline.isTypingKey(SoftKey(KeyEvent.KEYCODE_COMMA, ",")))

        assertFalse(pipeline.isTypingKey(null))
        assertFalse(pipeline.isTypingKey(SoftKey(KeyEvent.KEYCODE_SPACE, " ")))
        assertFalse(pipeline.isTypingKey(SoftKey(KeyEvent.KEYCODE_DEL, "")))
        assertFalse(pipeline.isTypingKey(SoftKey(org.bitfennec.lime.manager.InputModeSwitcher.USER_KEYCODE_LANG, "🌐")))
        assertFalse(pipeline.isTypingKey(SoftKey(org.bitfennec.lime.manager.InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, "😆")))
        assertFalse(pipeline.isTypingKey(SoftKey(org.bitfennec.lime.manager.InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION, "")))
        assertFalse(pipeline.isTypingKey(SoftKey(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift")))
        assertFalse(pipeline.isTypingKey(SoftKey(KeyEvent.KEYCODE_ENTER, "Enter")))
        assertFalse(pipeline.isTypingKey(SoftKey(org.bitfennec.lime.manager.InputModeSwitcher.USER_KEYCODE_RETURN, "Return")))
    }

    @Test
    fun swipeDownCapsArmsAndEmitsFlickCapsInEnglishMode() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_A, label = "a", smallLabel = "@", height = 100)
        pipeline.onDown(key, 50f, 50f, 0, downContext())

        val move = pipeline.onScroll(
            0,
            currentX = 50f,
            currentY = 95f,
            distanceX = 0f,
            distanceY = -45f,
            moveContext(swipeDownCapsEnabled = true, englishLetterCapsEnabled = true)
        )
        assertTrue(move.any { it is PointerCommand.PopupShowText && it.text == "A" })

        val up = pipeline.onUp(0)
        assertTrue(up.any { it is PointerCommand.FlickCaps && it.key.code == KeyEvent.KEYCODE_A })
        assertFalse(up.any { it is PointerCommand.Tap })
    }

    @Test
    fun swipeDownCapsPullbackCancelsArmedCaps() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_A, label = "a", smallLabel = "@", height = 100)
        pipeline.onDown(key, 50f, 50f, 0, downContext())

        pipeline.onScroll(
            0,
            currentX = 50f,
            currentY = 95f,
            distanceX = 0f,
            distanceY = -45f,
            moveContext(swipeDownCapsEnabled = true, englishLetterCapsEnabled = true)
        )

        val pullback = pipeline.onScroll(
            0,
            currentX = 50f,
            currentY = 60f,
            distanceX = 0f,
            distanceY = 35f,
            moveContext(swipeDownCapsEnabled = true, englishLetterCapsEnabled = true)
        )
        assertTrue(pullback.any { it is PointerCommand.PopupShowText && it.text == "a" })

        val up = pipeline.onUp(0)
        assertFalse(up.any { it is PointerCommand.FlickCaps })
    }

    @Test
    fun swipeDownCapsDisabledInChineseMode() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_A, label = "a", smallLabel = "@", height = 100)
        pipeline.onDown(key, 50f, 50f, 0, downContext())

        val move = pipeline.onScroll(
            0,
            currentX = 50f,
            currentY = 95f,
            distanceX = 0f,
            distanceY = -45f,
            moveContext(swipeDownCapsEnabled = true, englishLetterCapsEnabled = false)
        )
        assertFalse(move.any { it is PointerCommand.PopupShowText && it.text == "A" })

        val up = pipeline.onUp(0)
        assertFalse(up.any { it is PointerCommand.FlickCaps })
    }

    @Test
    fun upwardFlickAndDownwardFlickAreMutuallyExclusive() {
        val pipeline = PointerPipeline()
        val key = testKey(KeyEvent.KEYCODE_A, label = "a", smallLabel = "@", height = 100)
        pipeline.onDown(key, 50f, 50f, 0, downContext())

        val upMove = pipeline.onScroll(
            0,
            currentX = 50f,
            currentY = -10f,
            distanceX = 0f,
            distanceY = 60f,
            moveContext(swipeDownCapsEnabled = true, englishLetterCapsEnabled = true)
        )
        assertTrue(upMove.any { it is PointerCommand.PopupShowText && it.text == "@" })

        val up = pipeline.onUp(0)
        assertTrue(up.any { it is PointerCommand.FlickSymbol && it.result.second == "@" })
        assertFalse(up.any { it is PointerCommand.FlickCaps })
    }

    @Test
    fun qwerty9QBottomRowFlickSymbolThreshold() {
        val pipeline = PointerPipeline()
        val qKey = testKey(
            code = KeyEvent.KEYCODE_Q,
            label = "q",
            smallLabel = "1",
            height = 100,
            top = 200,
            isBottomRow = true
        )
        pipeline.onDown(qKey, 50f, 250f, 0, downContext())

        val move = pipeline.onScroll(
            0,
            currentX = 50f,
            currentY = 180f,
            distanceX = 0f,
            distanceY = 70f,
            moveContext(swipeDownCapsEnabled = true, englishLetterCapsEnabled = true)
        )
        assertTrue(move.any { it is PointerCommand.PopupShowText && it.text == "1" })

        val up = pipeline.onUp(0)
        assertTrue(up.any { it is PointerCommand.FlickSymbol && it.result.second == "1" })
        assertFalse(up.any { it is PointerCommand.FlickCaps })
    }

    @Test
    fun flickDistanceFloorAndReleaseRevalidation() {
        for (scale in listOf(1f, 2f)) {
            val pipeline = PointerPipeline()
            val key = testKey(height = (20 * scale).toInt())
            val context = moveContext().copy(symbolMinSwipePx = 20 * scale)
            pipeline.onDown(key, 0f, 0f, 0, downContext())
            pipeline.onScroll(currentX = 0f, currentY = -15 * scale, context = context)
            assertFalse(pipeline.isFlickArmed())
            pipeline.onScroll(currentX = 0f, currentY = -25 * scale, context = context)
            assertTrue(pipeline.isFlickArmed())
            val cancel = pipeline.onCancel(0)
            assertFalse(cancel.any { it is PointerCommand.FlickSymbol || it is PointerCommand.Tap })
            assertFalse(pipeline.onUp(0).any { it is PointerCommand.FlickSymbol || it is PointerCommand.Tap })
        }

        // The view sends the final UP position through onScroll before onUp.
        for ((x, y, emitsSymbol) in listOf(
            Triple(50f, -10f, true), // A single deliberate fast stroke still commits.
            Triple(98f, -5f, true), // Between the entry and exit cones: retain preview.
            Triple(108f, -5f, false), // Beyond the wider exit cone: restore tap.
            Triple(50f, 40f, false), // Pullback arriving at release: restore tap.
        )) {
            val pipeline = PointerPipeline()
            val key = testKey()
            pipeline.onDown(key, 50f, 50f, 0, downContext())
            pipeline.onScroll(currentX = 50f, currentY = -10f, context = moveContext())
            assertTrue(pipeline.isFlickArmed())
            pipeline.onScroll(currentX = x, currentY = y, context = moveContext())
            val up = pipeline.onUp()
            assertEquals(emitsSymbol, up.any { it is PointerCommand.FlickSymbol })
            assertEquals(!emitsSymbol, up.any { it == PointerCommand.Tap(key) })
            assertFalse(pipeline.onUp().any { it is PointerCommand.FlickSymbol || it is PointerCommand.Tap })
        }
    }

    private fun testKey(
        code: Int = KeyEvent.KEYCODE_Q,
        label: String = "q",
        smallLabel: String = "1",
        width: Int = 100,
        height: Int = 100,
        top: Int = 0,
        isBottomRow: Boolean = false,
    ) = SoftKey(code, label, smallLabel).apply {
        mLeft = 0
        mTop = top
        mRight = width
        mBottom = top + height
        this.isBottomRow = isBottomRow
        setHitBounds(0, top, width, top + height)
    }

    @Test
    fun symbolSlideUpSensitivityTiersDifferentiateFlickThresholds() {
        val key = testKey(top = 100, height = 100, isBottomRow = true)
        // SHORT (0.15 + 0.15 = 0.30 => 30px threshold)
        val shortContext = moveContext().copy(symbolSlideUpRatio = 0.15f, symbolMinSwipePx = 10f)
        val shortPipeline = PointerPipeline()
        shortPipeline.onDown(key, 50f, 150f, 0, downContext())
        shortPipeline.onScroll(currentX = 50f, currentY = 85f, context = shortContext) // 65px displacement >= 30px threshold, crosses top (15px > 10px)
        assertTrue(shortPipeline.isFlickArmed())

        // LONG (0.65 + 0.15 = 0.80 => 80px threshold)
        val longContext = moveContext().copy(symbolSlideUpRatio = 0.65f, symbolMinSwipePx = 30f)
        val longPipeline = PointerPipeline()
        longPipeline.onDown(key, 50f, 150f, 0, downContext())
        longPipeline.onScroll(currentX = 50f, currentY = 85f, context = longContext) // 65px displacement < 80px threshold
        assertFalse(longPipeline.isFlickArmed())
        longPipeline.onScroll(currentX = 50f, currentY = 50f, context = longContext) // 100px displacement >= 80px threshold
        assertTrue(longPipeline.isFlickArmed())
    }

    @Test
    fun nonBottomRowKeyUsesSlideUpTiersAndDecoupledCapsThreshold() {
        val key = testKey(top = 0, height = 100, isBottomRow = false)
        // Non-bottom row upRatio = symbolSlideUpRatio + 0.15f
        // SHORT tier: 0.15 + 0.15 = 0.30 => 30px threshold
        val shortContext = moveContext().copy(symbolSlideUpRatio = 0.15f, symbolMinSwipePx = 10f)
        val pipeline = PointerPipeline()
        pipeline.onDown(key, 50f, 50f, 0, downContext())
        pipeline.onScroll(currentX = 50f, currentY = 15f, context = shortContext) // -35px swipe >= 30px
        assertTrue(pipeline.isFlickArmed())

        // Caps downward flick is decoupled to fixed 0.40f threshold (40px) regardless of symbolSlideUpRatio
        val capsKey = testKey(code = KeyEvent.KEYCODE_A, label = "a", smallLabel = "", height = 100, isBottomRow = false)
        val capsContext = shortContext.copy(swipeDownCapsEnabled = true, englishLetterCapsEnabled = true)
        val capsPipeline = PointerPipeline()
        capsPipeline.onDown(capsKey, 50f, 50f, 0, downContext())
        // Downward swipe of 35px: under 40px caps threshold => not armed
        capsPipeline.onScroll(currentX = 50f, currentY = 85f, context = capsContext)
        assertFalse(capsPipeline.isDownFlickArmed())
        // Downward swipe of 45px: exceeds 40px caps threshold => armed
        capsPipeline.onScroll(currentX = 50f, currentY = 95f, context = capsContext)
        assertTrue(capsPipeline.isDownFlickArmed())
    }

    private fun downContext(selectionEnd: Int = 0) = PointerPipeline.DownContext(
        selectionEnd = selectionEnd,
        textBeforeCursorLength = selectionEnd,
        longPressTimeoutMs = 500L,
    )

    private fun moveContext(
        pxPerMm: Float = 1f,
        swipeDownCapsEnabled: Boolean = false,
        englishLetterCapsEnabled: Boolean = false,
    ) = PointerPipeline.MoveContext(
        symbolSlideUpRatio = 0.40f,
        symbolMinSwipePx = PointerPipeline.DEFAULT_SYMBOL_MIN_SWIPE_DP.toFloat(),
        keyboardSymbolEnabled = true,
        pxPerMm = pxPerMm,
        deleteSwipeTriggerPx = 16f,
        deleteVerticalTriggerPx = 24f,
        deleteStepPx = 14f,
        swipeDownCapsEnabled = swipeDownCapsEnabled,
        englishLetterCapsEnabled = englishLetterCapsEnabled,
    )
}
