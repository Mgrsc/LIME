package org.bitfennec.lime.data.emojicon

import org.bitfennec.lime.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies integrity of emoji and emoticon metadata.
 */
class EmojiconDataTest {

    @Test
    fun testEmojiconDataStructureAndCategories() {
        val data = EmojiconData.emojiconData
        assertNotNull(data)
        assertTrue(data.containsKey(R.drawable.icon_emojibar_smileys))
        assertTrue(data.containsKey(R.drawable.icon_emojibar_people))
        assertTrue(data.containsKey(R.drawable.icon_emojibar_food))
        assertTrue(data.containsKey(R.drawable.icon_emojibar_car))
        assertTrue(data.containsKey(R.drawable.icon_emojibar_activity))
        assertTrue(data.containsKey(R.drawable.icon_emojibar_objects))
        assertTrue(data.containsKey(R.drawable.icon_emojibar_symbols))
        assertTrue(data.containsKey(R.drawable.icon_emojibar_flags))
        val smileys = data[R.drawable.icon_emojibar_smileys]
        assertNotNull(smileys)
        assertTrue(smileys!!.isNotEmpty())
        assertTrue(smileys.contains("😀"))
    }

    @Test
    fun testEmojiMatchShowsRawWhenCompatMissing() {
        assertTrue(LimeEmojiCompat.getEmojiMatch(null, "😀"))
        assertTrue(LimeEmojiCompat.getEmojiMatch(null, "👨‍👩‍👧‍👦"))
    }

    @Test
    fun testEmojiMatchCompositeFallback() {
        val original = LimeEmojiCompat.isHostTestEnvironment
        try {
            LimeEmojiCompat.isHostTestEnvironment = false
            // Composite emojis (ZWJ, keycaps, flags) must be retained even without compat
            assertTrue(LimeEmojiCompat.getEmojiMatch(null, "👨‍👩‍👧‍👦"))
            assertTrue(LimeEmojiCompat.getEmojiMatch(null, "🏳️‍🌈"))
            assertTrue(LimeEmojiCompat.getEmojiMatch(null, "1️⃣"))
        } finally {
            LimeEmojiCompat.isHostTestEnvironment = original
        }
    }

    @Test
    fun testEmoticonDataContent() {
        val emoticonData = EmojiconData.emoticonData
        assertNotNull(emoticonData)
        assertTrue(emoticonData.containsKey(R.drawable.icon_emoticon_1))
        val group1 = emoticonData[R.drawable.icon_emoticon_1]
        assertNotNull(group1)
        assertTrue(group1!!.isNotEmpty())
        assertTrue(group1.contains("(^_^;)"))
    }

    @Test
    fun testJapaneseKanaVoicedHa() {
        val hiragana = EmojiconData.japaneseHiraganaSymbols
        val katakana = EmojiconData.japaneseKatakanaSymbols
        assertTrue(hiragana.windowed(3).contains(listOf("は", "ば", "ぱ")))
        assertTrue(katakana.windowed(3).contains(listOf("ハ", "バ", "パ")))
        assertTrue((hiragana + katakana).none { it.contains("假") })
    }

    @Test
    fun testSymbolCategoriesAndPresets() {
        val chineseSymbols = EmojiconData.chineseSymbols
        assertTrue(chineseSymbols.isNotEmpty())
        assertTrue(chineseSymbols.contains("，") && chineseSymbols.contains("。"))
        assertTrue(chineseSymbols.contains("@"))
        assertTrue(!chineseSymbols.contains("＠"))

        val englishSymbols = EmojiconData.englishSymbols
        assertTrue(englishSymbols.isNotEmpty())
        assertTrue(englishSymbols.contains(",") && englishSymbols.contains("."))

        val presets = EmojiconData.SymbolPreset
        assertNotNull(presets)
        assertTrue(presets.containsKey("（"))
        assertTrue(presets["（"] == "）")
        assertTrue(presets["【"] == "】")
        assertTrue(presets["《"] == "》")
    }
}
