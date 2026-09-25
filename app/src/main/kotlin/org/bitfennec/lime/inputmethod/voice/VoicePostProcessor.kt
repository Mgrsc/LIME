package org.bitfennec.lime.inputmethod.voice

import org.bitfennec.lime.core.Rime
import org.bitfennec.lime.prefs.AppPrefs

/**
 * Post-processor for speech recognition output.
 * Responsibilities:
 * 1. Strip SenseVoice formatting tags (e.g. <|zh|><|NEUTRAL|><|Speech|>);
 * 2. Perform s2t conversion according to active Rime session mode;
 * 3. Normalize whitespace;
 * 4. Filter trailing punctuation according to user preferences.
 */
object VoicePostProcessor {

    private val SENSE_VOICE_TAG_REGEX = Regex("<\\|[^|>]*\\|>")
    private val TRAILING_PUNCTUATION_REGEX = Regex("[，。！？,.!?；;、…~]+$")
    private val MULTI_SPACE_REGEX = Regex("[ \\t]+")

    fun process(rawOutput: String, isFinal: Boolean): String {
        if (rawOutput.isEmpty()) return ""

        // 1. Strip SenseVoice formatting tags
        var text = rawOutput.replace(SENSE_VOICE_TAG_REGEX, "")

        // 2. Normalize whitespace
        text = text.replace(MULTI_SPACE_REGEX, " ").trim()

        if (text.isEmpty()) return ""

        // 3. Script alignment: perform s2t if active Rime session is in Traditional Chinese mode
        val isTraditional = runCatching { Rime.isTraditionalMode() }.getOrDefault(false)
        if (isTraditional) {
            text = runCatching { Rime.convertS2T(text) }.getOrDefault(text)
        }

        // 4. Punctuation post-processing:
        // Interim: strip trailing punctuation for clean UI preview
        // Final: respect user preference on trailing punctuation
        val removeTrailing = if (isFinal) {
            runCatching {
                AppPrefs.getInstance().voice.removeTrailingPunctuation.getValue()
            }.getOrDefault(true)
        } else {
            true
        }

        if (removeTrailing) {
            text = text.replace(TRAILING_PUNCTUATION_REGEX, "").trim()
        }

        return text
    }
}
