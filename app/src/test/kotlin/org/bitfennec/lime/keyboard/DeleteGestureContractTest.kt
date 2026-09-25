package org.bitfennec.lime.keyboard

import android.view.KeyEvent
import org.bitfennec.lime.R
import org.bitfennec.lime.entity.StringQueue
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.prefs.behavior.DeleteGestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteGestureContractTest {

    private val delKey = SoftKey(KeyEvent.KEYCODE_DEL, "")

    private fun downContext(selectionEnd: Int = 10) = PointerPipeline.DownContext(
        selectionEnd = selectionEnd,
        textBeforeCursorLength = selectionEnd,
        longPressTimeoutMs = 500L,
    )

    private fun moveContext(
        upAction: DeleteGestureAction = DeleteGestureAction.CLEAR_ALL,
        leftAction: DeleteGestureAction = DeleteGestureAction.SWIPE_SELECT,
        downAction: DeleteGestureAction = DeleteGestureAction.UNDO_REVERT,
    ) = PointerPipeline.MoveContext(
        symbolSlideUpRatio = 0.40f,
        keyboardSymbolEnabled = true,
        pxPerMm = 1f,
        deleteSwipeTriggerPx = 16f,
        deleteVerticalTriggerPx = 24f,
        deleteStepPx = 14f,
        deleteSwipeUpAction = upAction,
        deleteSwipeLeftAction = leftAction,
        deleteSwipeDownAction = downAction,
    )

    @Test
    fun testDefaultThreeWayActions() {
        val pipeline = PointerPipeline()

        // 1. Up -> CLEAR_ALL
        pipeline.onDown(delKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 0f, -1f, 0f, 1f, moveContext())
        val upMove = pipeline.onScroll(0, 0f, -31f, 0f, 30f, moveContext())
        val upCommit = pipeline.onUp()
        assertTrue(upMove.any { it is PointerCommand.PopupShowText && it.text == "Clear All" })
        assertTrue(upCommit.contains(PointerCommand.DeleteClearAll))

        // 2. Down -> UNDO_REVERT
        pipeline.onDown(delKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, moveContext())
        val downMove = pipeline.onScroll(0, 0f, 31f, 0f, -30f, moveContext())
        val downCommit = pipeline.onUp()
        assertTrue(downMove.any { it is PointerCommand.PopupShowText && it.text == "Undo Delete" })
        assertTrue(downCommit.contains(PointerCommand.DeleteUndoRevert))

        // 3. Left -> SWIPE_SELECT / COMMIT
        pipeline.onDown(delKey, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, -1f, 0f, 1f, 0f, moveContext())
        val leftMove = pipeline.onScroll(0, -31f, 0f, 30f, 0f, moveContext())
        val leftCommit = pipeline.onUp()
        assertTrue(leftMove.any { it is PointerCommand.DeleteSelect && it.charCount == 2 })
        assertTrue(leftCommit.any { it is PointerCommand.DeleteCommit && it.charCount == 2 })
    }

    @Test
    fun testPrefsHotReloadWithoutRestart() {
        val pipeline = PointerPipeline()

        // Session Gesture 1: Down is default UNDO_REVERT
        pipeline.onDown(delKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, moveContext())
        pipeline.onScroll(0, 0f, 31f, 0f, -30f, moveContext())
        val commit1 = pipeline.onUp()
        assertTrue(commit1.contains(PointerCommand.DeleteUndoRevert))

        // Simulate user altering Prefs in the same session: Down switched to TO_PUNCTUATION
        val hotUpdatedContext = moveContext(downAction = DeleteGestureAction.TO_PUNCTUATION)

        // Session Gesture 2: Down immediately dispatches TO_PUNCTUATION without pipeline recreation
        pipeline.onDown(delKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, hotUpdatedContext)
        val move2 = pipeline.onScroll(0, 0f, 31f, 0f, -30f, hotUpdatedContext)
        val commit2 = pipeline.onUp()
        assertTrue(move2.any { it is PointerCommand.PopupShowText && it.text == "Delete to Punctuation" })
        assertTrue(commit2.contains(PointerCommand.DeleteToPunctuation))
        assertFalse(commit2.contains(PointerCommand.DeleteUndoRevert))
    }

    @Test
    fun testUndoStackBoundsAndLIFOOrder() {
        val stack = StringQueue(maxSize = 20)

        // Push 25 items: items 0..4 should be evicted
        for (i in 0 until 25) {
            stack.push("text_$i")
        }

        assertEquals(20, stack.size())

        // LIFO order pop: latest items popped first
        for (i in 24 downTo 5) {
            assertEquals("text_$i", stack.popInReverseOrder())
        }

        // Now empty
        assertEquals(0, stack.size())
        assertTrue(stack.isEmpty())
        assertNull("Empty stack pop should safely return null", stack.popInReverseOrder())
    }

    @Test
    fun testUndoStackLifecycleConsecutiveVsInterrupted() {
        val stack = StringQueue(maxSize = 5)
        var isUndoing = false

        fun commitText(text: String) {
            if (!isUndoing) {
                stack.clear()
            }
        }

        fun undo(): String? {
            val restored = stack.popInReverseOrder() ?: return null
            isUndoing = true
            try {
                commitText(restored)
            } finally {
                isUndoing = false
            }
            return restored
        }

        // 1. Consecutive deletions accumulate in stack
        stack.push("delete_1")
        stack.push("delete_2")
        stack.push("delete_3")
        assertEquals(3, stack.size())

        // 2. Undo restores without clearing remaining stack
        assertEquals("delete_3", undo())
        assertEquals(2, stack.size())
        assertEquals("delete_2", undo())
        assertEquals(1, stack.size())

        // 3. User types new content -> clears remaining undo stack to prevent stale insertion
        commitText("user_typed_new_letter")
        assertTrue(stack.isEmpty())
        assertNull(undo())
    }

    @Test
    fun testSwipeSelectCancellationRestoresCursor() {
        val pipeline = PointerPipeline()
        pipeline.onDown(delKey, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, -1f, 0f, 1f, 0f, moveContext())
        // Slide left enough to trigger selection
        pipeline.onScroll(0, -31f, 0f, 30f, 0f, moveContext())

        // Slide back towards center to cancel
        val returnMove = pipeline.onScroll(0, -2f, 0f, -29f, 0f, moveContext())
        assertTrue("Returning towards center should emit DeleteCancel", returnMove.any { it is PointerCommand.DeleteCancel })

        // Releasing after cancel should not commit deletion
        val upCommands = pipeline.onUp()
        assertFalse(upCommands.any { it is PointerCommand.DeleteCommit })
    }

    @Test
    fun testSwipeSelectConfiguredOnUpAndDownDirection() {
        // Verify SWIPE_SELECT works properly along vertical axes if configured on Up or Down
        val pipeline = PointerPipeline()
        val upSelectContext = moveContext(upAction = DeleteGestureAction.SWIPE_SELECT)

        pipeline.onDown(delKey, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, 0f, -1f, 0f, 1f, upSelectContext)
        val upMove = pipeline.onScroll(0, 0f, -40f, 0f, 39f, upSelectContext)
        val upCommit = pipeline.onUp()

        assertTrue(upMove.any { it is PointerCommand.DeleteSelect && it.charCount >= 1 })
        assertTrue(upCommit.any { it is PointerCommand.DeleteCommit && it.charCount >= 1 })

        val downSelectContext = moveContext(downAction = DeleteGestureAction.SWIPE_SELECT)
        pipeline.onDown(delKey, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, downSelectContext)
        val downMove = pipeline.onScroll(0, 0f, 40f, 0f, -39f, downSelectContext)
        val downCommit = pipeline.onUp()

        assertTrue(downMove.any { it is PointerCommand.DeleteSelect && it.charCount >= 1 })
        assertTrue(downCommit.any { it is PointerCommand.DeleteCommit && it.charCount >= 1 })
    }

    @Test
    fun testActionCapsuleIconsAndSelectTooltip() {
        val pipeline = PointerPipeline()

        // 1. CLEAR_ALL carries ic_gesture_clear
        pipeline.onDown(delKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 0f, -1f, 0f, 1f, moveContext())
        val upMove = pipeline.onScroll(0, 0f, -31f, 0f, 30f, moveContext())
        pipeline.onUp()
        val clearCmd = upMove.filterIsInstance<PointerCommand.PopupShowText>().firstOrNull()
        assertNotNull(clearCmd)
        assertEquals(R.drawable.ic_gesture_clear, clearCmd?.iconRes)

        // 2. SWIPE_SELECT carries ic_gesture_select and dynamic select tooltip
        pipeline.onDown(delKey, 0f, 0f, 0, downContext(selectionEnd = 5))
        pipeline.onScroll(0, -1f, 0f, 1f, 0f, moveContext())
        val selectMove = pipeline.onScroll(0, -31f, 0f, 30f, 0f, moveContext())
        val selectCmd = selectMove.filterIsInstance<PointerCommand.PopupShowText>().firstOrNull()
        assertNotNull(selectCmd)
        assertEquals(R.drawable.ic_gesture_select, selectCmd?.iconRes)

        // Return slide cancels popup
        val returnMove = pipeline.onScroll(0, -2f, 0f, -29f, 0f, moveContext())
        assertTrue(returnMove.contains(PointerCommand.DismissPopup))
    }

    @Test
    fun testUndoStackSafetyBuffer5Deep() {
        val stack = StringQueue(maxSize = 5)
        for (i in 0 until 10) {
            stack.push("item_$i")
        }
        assertEquals(5, stack.size())
        assertEquals("item_9", stack.popInReverseOrder())
        assertEquals("item_8", stack.popInReverseOrder())
        assertEquals("item_7", stack.popInReverseOrder())
        assertEquals("item_6", stack.popInReverseOrder())
        assertEquals("item_5", stack.popInReverseOrder())
        assertNull(stack.popInReverseOrder())
    }

    @Test
    fun testUndoGestureShowsEmptyStateWhenStackIsEmpty() {
        val pipeline = PointerPipeline()

        // 1. When hasUndoContent = true -> ic_gesture_undo & "Undo Delete"
        pipeline.onDown(delKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, moveContext(downAction = DeleteGestureAction.UNDO_REVERT))
        val activeMove = pipeline.onScroll(0, 0f, 31f, 0f, -30f, moveContext(downAction = DeleteGestureAction.UNDO_REVERT))
        pipeline.onUp()
        val activeCmd = activeMove.filterIsInstance<PointerCommand.PopupShowText>().firstOrNull()
        assertNotNull(activeCmd)
        assertEquals(R.drawable.ic_gesture_undo, activeCmd?.iconRes)
        assertEquals("Undo Delete", activeCmd?.text)

        // 2. When hasUndoContent = false -> ic_gesture_none & "Nothing to undo"
        val emptyContext = moveContext(downAction = DeleteGestureAction.UNDO_REVERT).copy(hasUndoContent = false)
        pipeline.onDown(delKey, 0f, 0f, 0, downContext())
        pipeline.onScroll(0, 0f, 1f, 0f, -1f, emptyContext)
        val emptyMove = pipeline.onScroll(0, 0f, 31f, 0f, -30f, emptyContext)
        pipeline.onUp()
        val emptyCmd = emptyMove.filterIsInstance<PointerCommand.PopupShowText>().firstOrNull()
        assertNotNull(emptyCmd)
        assertEquals(R.drawable.ic_gesture_none, emptyCmd?.iconRes)
        assertEquals("Nothing to undo", emptyCmd?.text)
    }
}
