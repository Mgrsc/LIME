package org.bitfennec.lime.manager

import android.content.SharedPreferences
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputModeSwitcherSchemaTest {

    @Before
    fun setUp() {
        AppPrefs.init(InMemorySharedPreferences())
        InputModeSwitcher.reset()
    }

    @Test
    fun currentRimeSchema_returnsEnglishWhenInEnglishMode() {
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MODE_QWERTY_CHINESE, applyEngine = false)
        assertTrue(InputModeSwitcher.isChinese)
        InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
        assertTrue(InputModeSwitcher.isEnglish)
        assertEquals(CustomConstant.SCHEMA_EN, InputModeSwitcher.currentRimeSchema())
    }

    @Test
    fun currentRimeSchema_whitelistsValidChineseSchemas() {
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MODE_QWERTY_CHINESE, applyEngine = false)
        assertTrue(InputModeSwitcher.isChinese)

        AppPrefs.getInstance().internal.pinyinModeRime.setValue(CustomConstant.SCHEMA_ZH_QWERTY)
        assertEquals(CustomConstant.SCHEMA_ZH_QWERTY, InputModeSwitcher.currentRimeSchema())

        AppPrefs.getInstance().internal.pinyinModeRime.setValue(CustomConstant.SCHEMA_ZH_T9)
        assertEquals(CustomConstant.SCHEMA_ZH_T9, InputModeSwitcher.currentRimeSchema())

        AppPrefs.getInstance().internal.pinyinModeRime.setValue(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY)
        assertEquals(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY, InputModeSwitcher.currentRimeSchema())
    }

    @Test
    fun currentRimeSchema_safelyFallsBackToQwertyOnLegacyHandwritingOrCorruptedValues() {
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MODE_QWERTY_CHINESE, applyEngine = false)

        // Legacy "handwriting" string in persistent prefs must NOT reach Rime
        AppPrefs.getInstance().internal.pinyinModeRime.setValue("handwriting")
        assertEquals(CustomConstant.SCHEMA_ZH_QWERTY, InputModeSwitcher.currentRimeSchema())

        // Unknown / corrupted values must safely fallback to QWERTY
        AppPrefs.getInstance().internal.pinyinModeRime.setValue("")
        assertEquals(CustomConstant.SCHEMA_ZH_QWERTY, InputModeSwitcher.currentRimeSchema())

        AppPrefs.getInstance().internal.pinyinModeRime.setValue("nonexistent_schema")
        assertEquals(CustomConstant.SCHEMA_ZH_QWERTY, InputModeSwitcher.currentRimeSchema())
    }

    @Test
    fun handwritingMode_retainsValidRimeSchema() {
        // Initially in T9 mode
        AppPrefs.getInstance().internal.pinyinModeRime.setValue(CustomConstant.SCHEMA_ZH_T9)
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MODE_T9_CHINESE, applyEngine = false)
        assertEquals(CustomConstant.SCHEMA_ZH_T9, InputModeSwitcher.currentRimeSchema())

        // Switch to handwriting layout via Settings selection
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MODE_HANDWRITING_CHINESE, applyEngine = false)
        assertTrue(InputModeSwitcher.isChineseHandWriting)

        // Rime schema remains valid Chinese schema (never "handwriting")
        assertEquals(CustomConstant.SCHEMA_ZH_T9, InputModeSwitcher.currentRimeSchema())
    }

    @Test
    fun transientHandwritingMode_enterAndLeaveRestoresPriorMode() {
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MODE_QWERTY_CHINESE, applyEngine = false)
        assertFalse(InputModeSwitcher.isChineseHandWriting)

        InputModeSwitcher.enterTransientHandwritingMode()
        assertTrue(InputModeSwitcher.isChineseHandWriting)

        InputModeSwitcher.leaveTransientHandwritingMode()
        assertFalse(InputModeSwitcher.isChineseHandWriting)
        assertTrue(InputModeSwitcher.isChinese && InputModeSwitcher.skbLayout == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)
    }

    @Test
    fun handwritingSettingFromEnglish_preservesSavedChineseSchema() {
        for (schema in listOf(CustomConstant.SCHEMA_ZH_T9, CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY)) {
            val layout = if (schema == CustomConstant.SCHEMA_ZH_T9) {
                InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN
            } else {
                InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN
            }
            InputModeSwitcher.switchModeForSetting(
                layout to schema
            )
            InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
            assertEquals(CustomConstant.SCHEMA_EN, InputModeSwitcher.currentRimeSchema())

            InputModeSwitcher.switchModeForSetting(
                InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING to InputModeSwitcher.currentRimeSchema()
            )

            assertTrue(InputModeSwitcher.isChineseHandWriting)
            assertEquals(schema, AppPrefs.getInstance().internal.pinyinModeRime.getValue())
            assertEquals(schema, InputModeSwitcher.currentRimeSchema())
        }
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
