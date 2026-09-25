package org.bitfennec.lime.data.emojicon

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LimeEmojiCompat {
    @VisibleForTesting
    internal var isHostTestEnvironment: Boolean = (Build.VERSION.SDK_INT == 0)

    private val systemFontPaint: Paint by lazy {
        Paint().apply {
            typeface = Typeface.DEFAULT
        }
    }
    private val _emojiCompatFlow = MutableStateFlow<EmojiCompat?>(null)

    fun init(context: Context) {
        try {
            if (!EmojiCompat.isConfigured()) {
                val config = DefaultEmojiCompatConfig.create(context)?.apply {
                    setReplaceAll(true)
                    registerInitCallback(object : EmojiCompat.InitCallback() {
                        override fun onInitialized() {
                            _emojiCompatFlow.value = EmojiCompat.get()
                            EmojiconData.prewarm()
                        }
                        override fun onFailed(throwable: Throwable?) {
                            _emojiCompatFlow.value = null
                        }
                    })
                }
                if (config != null) {
                    EmojiCompat.init(config)
                }
            } else {
                val current = EmojiCompat.get()
                when (current.loadState) {
                    EmojiCompat.LOAD_STATE_SUCCEEDED -> {
                        _emojiCompatFlow.value = current
                        EmojiconData.prewarm()
                    }
                    EmojiCompat.LOAD_STATE_LOADING -> {
                        current.registerInitCallback(object : EmojiCompat.InitCallback() {
                            override fun onInitialized() {
                                _emojiCompatFlow.value = EmojiCompat.get()
                                EmojiconData.prewarm()
                            }
                            override fun onFailed(throwable: Throwable?) {
                                _emojiCompatFlow.value = null
                            }
                        })
                    }
                    else -> {
                        _emojiCompatFlow.value = null
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun isCompositeEmoji(emoji: String): Boolean {
        for (i in emoji.indices) {
            val c = emoji[i]
            if (c == '\u200D' || c == '\u20E3') return true
            if (c == '\uD83C' && i + 1 < emoji.length) {
                val next = emoji[i + 1]
                if (next in '\uDDE6'..'\uDDFF' || next in '\uDFFB'..'\uDFFF') return true
            }
        }
        return false
    }

    private fun getLoadedCompat(emojiCompat: EmojiCompat?): EmojiCompat? {
        if (emojiCompat != null) {
            return emojiCompat.takeIf { it.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED }
        }
        return try {
            if (EmojiCompat.isConfigured()) {
                EmojiCompat.get().takeIf { it.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getEmojiMatch(emojiCompat: EmojiCompat?, emoji: String): Boolean {
        if (isHostTestEnvironment) return true
        val compat = getLoadedCompat(emojiCompat)
        if (compat != null) {
            val compatHasGlyph = try {
                // Intentionally using the deprecated overload: it probes glyphs with the
                // font's own metadataVersion. Switching to getEmojiMatch(emoji, metadataVersion)
                // filters out whole categories when the host app does not declare the key (default 0).
                @Suppress("DEPRECATION")
                compat.hasEmojiGlyph(emoji)
            } catch (_: Throwable) {
                false
            }
            if (compatHasGlyph) return true
        }
        if (isCompositeEmoji(emoji)) return true
        return try {
            systemFontPaint.hasGlyph(emoji)
        } catch (_: Throwable) {
            true
        }
    }

    fun getAsFlow(): StateFlow<EmojiCompat?> {
        return _emojiCompatFlow.asStateFlow()
    }
}
