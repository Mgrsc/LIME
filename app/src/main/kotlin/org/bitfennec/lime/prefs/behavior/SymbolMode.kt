package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class SymbolMode {
    Symbol,
    Emojicon,
    Emoticon;
    companion object : ManagedPreference.StringLikeCodec<SymbolMode> {
        override fun decode(raw: String) = SymbolMode.valueOf(raw)
    }
}