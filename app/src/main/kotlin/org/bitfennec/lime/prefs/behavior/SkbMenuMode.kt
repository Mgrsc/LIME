package org.bitfennec.lime.prefs.behavior

import org.bitfennec.lime.view.preference.ManagedPreference

enum class SkbMenuMode {
    SwitchKeyboard,
    KeyboardHeight,
    DarkTheme,
    Feedback,
    NumberRow,
    JianFan,
    LockEnglish,
    SymbolShow,
    CandidatesMore,
    EmojiInput,
    Handwriting,
    Custom,
    SettingsMenu,
    Settings,
    FloatKeyboard,
    OneHanded,
    PinyinT9,
    Pinyin26Jian,
    PinyinHandWriting,
    Pinyin26Double,
    ClipBoard,
    CloseSKB,
    Emojicon,
    Emoticon,
    LockClipBoard,
    TextEdit;

    companion object : ManagedPreference.StringLikeCodec<SkbMenuMode> {
        fun decodeOrNull(raw: String): SkbMenuMode? =
            runCatching { SkbMenuMode.valueOf(raw) }.getOrNull()

        override fun decode(raw: String): SkbMenuMode =
            decodeOrNull(raw) ?: SkbMenuMode.Custom
    }
}