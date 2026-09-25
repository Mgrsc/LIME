package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class KeyboardOneHandedMod {
    LEFT,
    RIGHT;
    companion object : ManagedPreference.StringLikeCodec<KeyboardOneHandedMod> {
        override fun decode(raw: String) = KeyboardOneHandedMod.valueOf(raw)
    }
}