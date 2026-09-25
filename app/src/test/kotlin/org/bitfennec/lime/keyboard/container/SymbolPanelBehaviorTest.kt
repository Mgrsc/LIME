package org.bitfennec.lime.keyboard.container

import android.database.Observable
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.data.emojicon.EmojiconData
import org.bitfennec.lime.manager.InputModeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import android.content.SharedPreferences
import org.bitfennec.lime.prefs.AppPrefs

/**
 * Focused regression tests for symbol panel overhaul:
 * 1. Category and subcategory structures
 * 2. Intentional duplicate preservation & deduplication
 * 3. History sanitizer mapping and preservation
 * 4. SymbolPreset pair bracket behavior (no '<' pairing)
 * 5. Editor flags parsing for WEB_EMAIL_ADDRESS
 * 6. Editor session generation tracking
 */
class SymbolPanelBehaviorTest {

    @Before
    fun setUp() {
        AppPrefs.init(InMemorySharedPreferences())
        InputModeSwitcher.resetEditorSessionForTesting()
    }

    @Test
    fun testSymbolCategoriesAndTitles() {
        val categories = EmojiconData.SymbolCategory.values()
        assertEquals(8, categories.size)
        assertEquals(EmojiconData.SymbolCategory.COMMON, categories[0])
        assertEquals(EmojiconData.SymbolCategory.CHINESE, categories[1])
        assertEquals(EmojiconData.SymbolCategory.ENGLISH, categories[2])
        assertEquals(EmojiconData.SymbolCategory.MATH, categories[3])
        assertEquals(EmojiconData.SymbolCategory.SEQUENCE, categories[4])
        assertEquals(EmojiconData.SymbolCategory.ARROW, categories[5])
        assertEquals(EmojiconData.SymbolCategory.MORE, categories[6])
        assertEquals(EmojiconData.SymbolCategory.RECENTS, categories[7])

        assertEquals(R.string.symbol_tab_common, categories[0].titleRes)
        assertEquals(R.string.symbol_tab_recents, categories[7].titleRes)
    }

    @Test
    fun testMoreSubCategoriesCompleteness() {
        val subCategories = EmojiconData.SymbolMoreSubCategory.values()
        assertEquals(10, subCategories.size)
        for (subCat in subCategories) {
            val list = EmojiconData.moreSubCategorySymbols[subCat]
            assertNotNull("Subcategory list must not be null for $subCat", list)
            assertTrue("Subcategory list must not be empty for $subCat", list!!.isNotEmpty())
        }
    }

    @Test
    fun testCommonSymbolsDimensionsAndRepetition() {
        val cnCommon = EmojiconData.commonChineseSymbols
        assertEquals(24, cnCommon.size)

        val enCommon = EmojiconData.commonEnglishSymbols
        assertEquals(24, enCommon.size)

        // Verify intentional repetition of '.' in row 1 (index 1) and row 3 (index 13) of English Common
        assertEquals(".", enCommon[1])
        assertEquals(".", enCommon[13])
        assertEquals(2, enCommon.count { it == "." })
    }

    @Test
    fun testMathAndGraphicDeduplication() {
        val math = EmojiconData.mathSymbols
        assertEquals(1, math.count { it == "∪" })
        assertEquals(1, math.count { it == "∩" })

        val shapes = EmojiconData.graphicsSymbols
        assertEquals(1, shapes.count { it == "○" })
    }

    @Test
    fun testHistorySanitizer() {
        // Obsolete full-width variants convert to ASCII
        assertEquals("@", EmojiconData.sanitizeObsoleteSymbol("＠"))
        assertEquals("#", EmojiconData.sanitizeObsoleteSymbol("＃"))
        assertEquals("$", EmojiconData.sanitizeObsoleteSymbol("＄"))
        assertEquals("%", EmojiconData.sanitizeObsoleteSymbol("％"))
        assertEquals("^", EmojiconData.sanitizeObsoleteSymbol("＾"))
        assertEquals("&", EmojiconData.sanitizeObsoleteSymbol("＆"))
        assertEquals("*", EmojiconData.sanitizeObsoleteSymbol("＊"))
        assertEquals("=", EmojiconData.sanitizeObsoleteSymbol("＝"))
        assertEquals("_", EmojiconData.sanitizeObsoleteSymbol("＿"))
        assertEquals("`", EmojiconData.sanitizeObsoleteSymbol("｀"))
        assertEquals("+", EmojiconData.sanitizeObsoleteSymbol("＋"))
        assertEquals("\\", EmojiconData.sanitizeObsoleteSymbol("＼"))
        assertEquals("/", EmojiconData.sanitizeObsoleteSymbol("／"))
        assertEquals("<", EmojiconData.sanitizeObsoleteSymbol("＜"))
        assertEquals(">", EmojiconData.sanitizeObsoleteSymbol("＞"))
        assertEquals("|", EmojiconData.sanitizeObsoleteSymbol("｜"))
        assertEquals("〜", EmojiconData.sanitizeObsoleteSymbol("〜"))
        assertEquals(".", EmojiconData.sanitizeObsoleteSymbol("．"))
        assertEquals("1", EmojiconData.sanitizeObsoleteSymbol("１"))
        assertEquals("0", EmojiconData.sanitizeObsoleteSymbol("０"))

        // Math minus (U+2212) and em-dash/ellipsis must remain intact
        assertEquals("−", EmojiconData.sanitizeObsoleteSymbol("−"))
        assertEquals("——", EmojiconData.sanitizeObsoleteSymbol("——"))
        assertEquals("……", EmojiconData.sanitizeObsoleteSymbol("……"))

        // Standard characters remain unchanged
        assertEquals("@", EmojiconData.sanitizeObsoleteSymbol("@"))
        assertEquals("。", EmojiconData.sanitizeObsoleteSymbol("。"))
    }

    @Test
    fun testSymbolPresetDoesNotAutoPairLessThan() {
        val presets = EmojiconData.SymbolPreset
        assertFalse("Math less-than '<' must not auto-pair", presets.containsKey("<"))
        assertNull(presets["<"])

        // Normal brackets still pair
        assertEquals(")", presets["("])
        assertEquals("]", presets["["])
        assertEquals("}", presets["{"])
        assertEquals("）", presets["（"])
        assertEquals("”", presets["“"])
        assertEquals("》", presets["《"])
    }

    @Test
    fun testWebEmailAddressDetectionAndNormalization() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        }
        val flags = InputModeSwitcher.parseEditorFlags(editorInfo)
        assertTrue("TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS must be identified as isEmail", flags.isEmail)
        assertFalse(flags.isUri)

        // normalizeEditorLiteral should normalize slash and at in email/uri fields
        assertEquals("/", InputModeSwitcher.normalizeEditorLiteral("／", isEmailOrUri = true))
        assertEquals("@", InputModeSwitcher.normalizeEditorLiteral("＠", isEmailOrUri = true))
        assertEquals("／", InputModeSwitcher.normalizeEditorLiteral("／", isEmailOrUri = false))
    }

    @Test
    fun testEditorSessionIdTracking() {
        val info1 = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val initialSession = InputModeSwitcher.currentEditorSessionId

        // Fresh start: restarting = false -> session increments
        InputModeSwitcher.requestInputWithSkb(info1, restarting = false)
        val session1 = InputModeSwitcher.currentEditorSessionId
        assertEquals(initialSession + 1, session1)

        // Restarting in same field -> session does not increment
        InputModeSwitcher.requestInputWithSkb(info1, restarting = true)
        assertEquals(session1, InputModeSwitcher.currentEditorSessionId)

        // Next new field -> session increments
        InputModeSwitcher.requestInputWithSkb(info1, restarting = false)
        assertEquals(session1 + 1, InputModeSwitcher.currentEditorSessionId)
    }

    @Test
    fun testSymbolAdapterUpdateDataAndIsRecent() {
        val adapter = org.bitfennec.lime.adapter.SymbolAdapter(
            context = null,
            viewType = org.bitfennec.lime.prefs.behavior.SymbolMode.Symbol,
            isRecent = false,
            onClickSymbol = { _, _ -> }
        )
        val notifications = mutableListOf<String>()
        var expectedItems = emptyList<String>()
        var expectedRecent = false
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun record(event: String) {
                assertEquals(expectedItems, adapter.mDatas)
                assertEquals(expectedItems.size, adapter.itemCount)
                assertEquals(expectedRecent, adapter.isRecent)
                notifications.add(event)
            }

            override fun onChanged() = record("reset")
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                record("insert:$positionStart:$itemCount")
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
                record("remove:$positionStart:$itemCount")
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
                record("change:$positionStart:$itemCount")
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                record("move:$fromPosition:$toPosition:$itemCount")
        }
        // Local JVM Android stubs skip both Observable initialization and observer registration.
        // Seed its observer list directly; keep AndroidX notification dispatch real.
        val observableField = RecyclerView.Adapter::class.java.getDeclaredField("mObservable").apply {
            isAccessible = true
        }
        val observersField = Observable::class.java.getDeclaredField("mObservers").apply {
            isAccessible = true
        }
        observersField.set(observableField.get(adapter), arrayListOf(observer))

        assertFalse(adapter.isRecent)
        assertEquals(0, adapter.itemCount)

        val symbols = listOf("，", "。", "！")
        expectedItems = symbols
        expectedRecent = true
        adapter.updateData(symbols, isRecent = true)
        assertTrue(adapter.isRecent)
        assertEquals(3, adapter.itemCount)
        assertEquals("，", adapter.mDatas[0])
        assertEquals(listOf("insert:0:3"), notifications)

        notifications.clear()
        adapter.updateData(symbols, isRecent = true)
        assertEquals(3, adapter.itemCount)
        assertTrue(notifications.isEmpty())

        expectedRecent = false
        adapter.updateData(symbols, isRecent = false)
        assertFalse(adapter.isRecent)
        assertEquals(listOf("change:0:3"), notifications)

        // Exercise DiffUtil dispatch when the list shrinks to empty.
        notifications.clear()
        expectedItems = emptyList()
        adapter.updateData(emptyList(), isRecent = false)
        assertEquals(0, adapter.itemCount)
        assertTrue(adapter.mDatas.isEmpty())
        assertEquals(listOf("remove:0:3"), notifications)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { pending[key!!] = this }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) target.clear()
                for ((k, v) in pending) {
                    if (v === this) target.remove(k) else target[k] = v
                }
            }
        }
    }
}
