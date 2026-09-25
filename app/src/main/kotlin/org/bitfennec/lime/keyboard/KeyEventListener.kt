package org.bitfennec.lime.keyboard

import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.prefs.behavior.PopupMenuMode

/**
 * Soft keyboard key event listener.
 */
interface KeyEventListener {
    fun responseKeyEvent(sKey: SoftKey)
    fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>)
    fun responseFlickCaps(sKey: SoftKey) {}
    fun responseHandwritingResultEvent(modalSessionId: Long, words: Array<CandidateListItem>)
    fun currentHandwritingModalSessionId(): Long? = null
    fun discardHandwritingCandidates(): Boolean = false
    fun onVoiceStart(originScreenX: Float = 0f, originScreenY: Float = 0f): Boolean = false
    fun onVoiceMove(screenX: Float, screenY: Float) {}
    fun onVoiceEnd() {}
    fun onVoiceCancel() {}
}
