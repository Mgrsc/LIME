package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class KeyboardSymbolSlideUpMod {
    SHORT,
    MEDIUM,
    LONG;

    val triggerRatio: Float
        get() = when (this) {
            SHORT -> 0.15f
            MEDIUM -> 0.40f
            LONG -> 0.65f
        }

    val minSwipeDp: Int
        get() = when (this) {
            SHORT -> 10
            MEDIUM -> 20
            LONG -> 30
        }

    companion object : ManagedPreference.StringLikeCodec<KeyboardSymbolSlideUpMod> {
        override fun decode(raw: String) = KeyboardSymbolSlideUpMod.valueOf(raw)
    }
}