package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class KeyboardSymbolSlideUpMod {
    SHORT,
    MEDIUM,
    LONG;

    val distanceScale: Float
        get() = when (this) {
            SHORT -> 0.8f
            MEDIUM -> 1.0f
            LONG -> 1.3f
        }

    companion object : ManagedPreference.StringLikeCodec<KeyboardSymbolSlideUpMod> {
        override fun decode(raw: String) = KeyboardSymbolSlideUpMod.valueOf(raw)
    }
}