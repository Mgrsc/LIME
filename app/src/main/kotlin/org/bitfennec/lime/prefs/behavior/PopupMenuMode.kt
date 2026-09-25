package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class PopupMenuMode {
    Text,
    Clear,
    SwitchIME,
    EnglishCell,
    Revertl,
    Move,
    Enter,
    EMOJI,
    None;

    companion object : ManagedPreference.StringLikeCodec<PopupMenuMode> {
        override fun decode(raw: String) = PopupMenuMode.valueOf(raw)
    }
}