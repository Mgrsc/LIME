package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class ClipboardLayoutMode {
    ListView,
    GridView,
    FlexboxView;

    companion object : ManagedPreference.StringLikeCodec<ClipboardLayoutMode> {
        override fun decode(raw: String): ClipboardLayoutMode =
            ClipboardLayoutMode.valueOf(raw)
    }
}