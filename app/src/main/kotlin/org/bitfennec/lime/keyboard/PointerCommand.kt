package org.bitfennec.lime.keyboard

import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.prefs.behavior.PopupMenuMode

sealed interface PointerCommand {
    data class Tap(val key: SoftKey) : PointerCommand
    data class FlickSymbol(val result: Pair<PopupMenuMode, String>) : PointerCommand
    data class FlickCaps(val key: SoftKey) : PointerCommand
    data class DeleteSelect(val charCount: Int, val initialCursor: Int) : PointerCommand
    data class DeleteCommit(val charCount: Int, val initialCursor: Int) : PointerCommand
    data class DeleteCancel(val initialCursor: Int) : PointerCommand
    data object DeleteClearAll : PointerCommand
    data object DeleteUndoRevert : PointerCommand
    data object DeleteToPunctuation : PointerCommand
    data class PopupShowKey(val label: String, val smallLabel: String, val key: SoftKey) : PointerCommand
    data class PopupShowMenu(val key: SoftKey, val distanceY: Float) : PointerCommand
    data class PopupShowText(val text: String, val key: SoftKey, val iconRes: Int = 0) : PointerCommand
    data class PopupFocus(val deltaX: Float, val deltaY: Float) : PointerCommand
    data class PopupGesture(val distanceY: Float) : PointerCommand
    data class Haptic(val event: HapticEvent) : PointerCommand
    data class VoiceStart(val key: SoftKey) : PointerCommand
    data class VoiceMove(val x: Float, val y: Float) : PointerCommand
    data object VoiceEnd : PointerCommand
    data object VoiceCancel : PointerCommand
    data class ScheduleLongPress(val timeoutMs: Long, val pointerId: Int = 0) : PointerCommand
    data class ScheduleRepeat(val pointerId: Int = 0) : PointerCommand
    data class CancelTimers(val pointerId: Int? = null) : PointerCommand
    data class DismissPreview(val pointerId: Int) : PointerCommand
    data object DismissPopup : PointerCommand
}
