package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class DeleteGestureAction {
    NONE,
    SWIPE_SELECT,
    CLEAR_ALL,
    UNDO_REVERT,
    TO_PUNCTUATION;

    companion object : ManagedPreference.StringLikeCodec<DeleteGestureAction> {
        override fun decode(raw: String): DeleteGestureAction =
            runCatching { DeleteGestureAction.valueOf(raw) }.getOrDefault(NONE)
    }
}
