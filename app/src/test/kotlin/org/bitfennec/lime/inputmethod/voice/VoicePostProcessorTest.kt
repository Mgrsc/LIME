package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePostProcessorTest {

    @Test
    fun testStripTagsAndFormat() {
        val raw = "<|zh|><|NEUTRAL|><|Speech|>今天是个好天气。"
        val cleanedFinal = VoicePostProcessor.process(raw, isFinal = true)
        assertEquals("今天是个好天气", cleanedFinal)

        val rawWithEnglish = "<|en|><|HAPPY|>Hello world!   "
        val cleanedEn = VoicePostProcessor.process(rawWithEnglish, isFinal = true)
        assertEquals("Hello world", cleanedEn)
    }

    @Test
    fun testPreviewStripsTrailingPunctuation() {
        val raw = "<|zh|>正在识别中，"
        val interim = VoicePostProcessor.process(raw, isFinal = false)
        assertEquals("正在识别中", interim)
    }

    @Test
    fun testEmptyAndWhitespaceInput() {
        assertEquals("", VoicePostProcessor.process("", isFinal = true))
        assertEquals("", VoicePostProcessor.process("<|zh|><|Speech|>", isFinal = true))
        assertEquals("", VoicePostProcessor.process("   ", isFinal = false))
    }
}
