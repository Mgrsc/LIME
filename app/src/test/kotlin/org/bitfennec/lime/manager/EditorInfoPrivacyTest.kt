package org.bitfennec.lime.manager

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInfoPrivacyTest {

    @Test
    fun testChinesePostCommitPredictionForMessageEditors() {
        fun flags(inputType: Int, imeOptions: Int = 0) = InputModeSwitcher.parseEditorFlags(
            android.view.inputmethod.EditorInfo().apply {
                this.inputType = inputType
                this.imeOptions = imeOptions
            }
        )

        // Captured from QQ on V1981A: text + multiline + no suggestions, normal variation (0xa0001).
        // Privacy boundary decision:
        // Mainstream Chinese IM apps (QQ, WeChat) use textMultiLine | textNoSuggestions to disable
        // English spell check underlines. Treating multiline normal fields as message fields enables
        // post-commit Chinese prediction for chat input.
        // Privacy safety is preserved because:
        // 1) Any password variation (0xa0081, 0xa0091, 0xa00e1, 0x12) disables prediction unconditionally.
        // 2) IME_FLAG_NO_PERSONALIZED_LEARNING (0x01000000) disables prediction unconditionally.
        // 3) Single-line text with textNoSuggestions (0x80001, search/OTP/commands) disables prediction.
        val qq = flags(0xa0001, 0x44000006)
        assertTrue(qq.isNoSuggestions)
        assertFalse(qq.isPrivateOrSensitive)
        assertTrue(qq.allowsChinesePostCommitPrediction)
        assertTrue(flags(0x20001).allowsChinesePostCommitPrediction)
        assertTrue(flags(0x80041).allowsChinesePostCommitPrediction) // Short message
        assertTrue(flags(0x80051).allowsChinesePostCommitPrediction) // Long message

        assertFalse(flags(0x80001).allowsChinesePostCommitPrediction) // Single-line no suggestions
        assertFalse(flags(0xa0011).allowsChinesePostCommitPrediction) // URI
        assertFalse(flags(0xa0021).allowsChinesePostCommitPrediction) // Email
        assertFalse(flags(0xa00b1).allowsChinesePostCommitPrediction) // Filter
        for (passwordType in listOf(0xa0081, 0xa0091, 0xa00e1, 0x12)) {
            assertFalse(flags(passwordType).allowsChinesePostCommitPrediction)
        }
        assertFalse(flags(0xa0001, 0x01000000).allowsChinesePostCommitPrediction)
    }

    @Test
    fun testEditorFieldFlagsPrivacyFlag() {
        val normalFlags = InputModeSwitcher.EditorFieldFlags(
            isPassword = false,
            isNoPersonalizedLearning = false
        )
        assertFalse(normalFlags.isPrivateOrSensitive)
        assertFalse(normalFlags.isModalInputBlocked)

        val passwordFlags = InputModeSwitcher.EditorFieldFlags(
            isPassword = true,
            isNoPersonalizedLearning = false
        )
        assertTrue(passwordFlags.isPrivateOrSensitive)
        assertTrue(passwordFlags.isModalInputBlocked)

        val incognitoFlags = InputModeSwitcher.EditorFieldFlags(
            isPassword = false,
            isNoPersonalizedLearning = true
        )
        assertTrue(incognitoFlags.isPrivateOrSensitive)
        assertFalse(incognitoFlags.isModalInputBlocked)

        val bothFlags = InputModeSwitcher.EditorFieldFlags(
            isPassword = true,
            isNoPersonalizedLearning = true
        )
        assertTrue(bothFlags.isPrivateOrSensitive)
        assertTrue(bothFlags.isModalInputBlocked)

        val noSuggestionsFlags = InputModeSwitcher.EditorFieldFlags(isNoSuggestions = true)
        assertFalse(noSuggestionsFlags.isModalInputBlocked)
    }

    @Test
    fun testEditorFieldFlagsAttributes() {
        val flags = InputModeSwitcher.EditorFieldFlags(
            isPassword = true,
            isNoPersonalizedLearning = true,
            isNoSuggestions = true,
            isUri = true,
            isEmail = false,
            isNumber = false,
            isCapCharacters = true,
            isCapWords = false,
            isCapSentences = false
        )
        assertTrue(flags.isPassword)
        assertTrue(flags.isNoPersonalizedLearning)
        assertTrue(flags.isNoSuggestions)
        assertTrue(flags.isUri)
        assertFalse(flags.isEmail)
        assertTrue(flags.isCapCharacters)
        assertTrue(flags.isPrivateOrSensitive)
    }

    @Test
    fun testDefaultFlags() {
        val defaultFlags = InputModeSwitcher.EditorFieldFlags()
        assertFalse(defaultFlags.isPassword)
        assertFalse(defaultFlags.isNoPersonalizedLearning)
        assertFalse(defaultFlags.isNoSuggestions)
        assertFalse(defaultFlags.isPrivateOrSensitive)
        assertFalse(defaultFlags.isCapCharacters)
        assertFalse(defaultFlags.isCapWords)
        assertFalse(defaultFlags.isCapSentences)
    }

    @Test
    fun testPasswordVariationsAllSilencePredict() {
        // 1. TYPE_TEXT_VARIATION_PASSWORD
        val textPasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        }
        val textPassFlags = InputModeSwitcher.parseEditorFlags(textPasswordInfo)
        assertTrue(textPassFlags.isPassword)
        assertTrue(textPassFlags.isModalInputBlocked)
        assertTrue(textPassFlags.isPrivateOrSensitive)

        // 2. TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val visiblePasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val visiblePassFlags = InputModeSwitcher.parseEditorFlags(visiblePasswordInfo)
        assertTrue(visiblePassFlags.isPassword)
        assertTrue(visiblePassFlags.isPrivateOrSensitive)

        // 3. TYPE_TEXT_VARIATION_WEB_PASSWORD
        val webPasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        val webPassFlags = InputModeSwitcher.parseEditorFlags(webPasswordInfo)
        assertTrue(webPassFlags.isPassword)
        assertTrue(webPassFlags.isPrivateOrSensitive)

        // 4. TYPE_NUMBER_VARIATION_PASSWORD
        val numberPasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_NUMBER or android.view.inputmethod.EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val numberPassFlags = InputModeSwitcher.parseEditorFlags(numberPasswordInfo)
        assertTrue(numberPassFlags.isPassword)
        assertTrue(numberPassFlags.isNumber)
        assertTrue(numberPassFlags.isPrivateOrSensitive)

        // 5. IME_FLAG_NO_PERSONALIZED_LEARNING
        val noLearningInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val noLearningFlags = InputModeSwitcher.parseEditorFlags(noLearningInfo)
        assertTrue(noLearningFlags.isNoPersonalizedLearning)
        assertTrue(noLearningFlags.isPrivateOrSensitive)

        // 6. TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val noSuggestionsInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val noSuggestionsFlags = InputModeSwitcher.parseEditorFlags(noSuggestionsInfo)
        assertTrue(noSuggestionsFlags.isNoSuggestions)

        // 7. TYPE_TEXT_VARIATION_URI is not password
        val uriInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_URI
        }
        val uriFlags = InputModeSwitcher.parseEditorFlags(uriInfo)
        assertTrue(uriFlags.isUri)
        assertFalse(uriFlags.isPassword)
        assertFalse(uriFlags.isPrivateOrSensitive)

        val emailInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val emailFlags = InputModeSwitcher.parseEditorFlags(emailInfo)
        assertTrue(emailFlags.isEmail)
        assertFalse(emailFlags.isPassword)
        assertFalse(emailFlags.isPrivateOrSensitive)
    }

    @Test
    fun testT9SoftKeyMetaStateNeverCarriesCaseModifiers() {
        assertEquals(0, InputModeSwitcher.resolveSoftKeyMetaState(true, InputModeSwitcher.MASK_CASE_LOWER))
        assertEquals(0, InputModeSwitcher.resolveSoftKeyMetaState(true, InputModeSwitcher.MASK_CASE_UPPER))
        assertEquals(0, InputModeSwitcher.resolveSoftKeyMetaState(true, InputModeSwitcher.MASK_CASE_CAPS))
        assertEquals(KeyEvent.META_SHIFT_ON, InputModeSwitcher.resolveSoftKeyMetaState(false, InputModeSwitcher.MASK_CASE_UPPER))
        assertEquals(KeyEvent.META_CAPS_LOCK_ON, InputModeSwitcher.resolveSoftKeyMetaState(false, InputModeSwitcher.MASK_CASE_CAPS))
    }

    @Test
    fun testEmailOrUriLiteralsUseAsciiSyntaxCharacters() {
        assertEquals("@", InputModeSwitcher.normalizeEditorLiteral("＠", true))
        assertEquals(".", InputModeSwitcher.normalizeEditorLiteral("。", true))
        assertEquals(".", InputModeSwitcher.normalizeEditorLiteral("．", true))
        assertEquals("_", InputModeSwitcher.normalizeEditorLiteral("＿", true))
        assertEquals("-", InputModeSwitcher.normalizeEditorLiteral("−", true))
        assertEquals("+", InputModeSwitcher.normalizeEditorLiteral("＋", true))
        assertEquals("。", InputModeSwitcher.normalizeEditorLiteral("。", false))
    }

    @Test
    fun testSkbModeForNumberPasswordForcesNumericLayout() {
        // NUMBER_PASSWORD -> MUST force MASK_SKB_LAYOUT_NUMBER (never fall back or switch to QWERTY)
        val numberPasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_NUMBER or android.view.inputmethod.EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val numberPasswordFlags = InputModeSwitcher.parseEditorFlags(numberPasswordInfo)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER, InputModeSwitcher.skbModeForEditorFlags(numberPasswordFlags))
    }

    @Test
    fun testSkbModeForNumericVariationsForcesNumericLayout() {
        val numberInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_NUMBER
        }
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER, InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(numberInfo)))

        val phoneInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_PHONE
        }
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER, InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(phoneInfo)))

        val datetimeInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_DATETIME
        }
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER, InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(datetimeInfo)))
    }

    @Test
    fun testSkbModeForPasswordVariationsForcesEnglishLower() {
        val textPasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertEquals(InputModeSwitcher.MODE_SKB_ENGLISH_LOWER, InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(textPasswordInfo)))

        val visiblePasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        assertEquals(InputModeSwitcher.MODE_SKB_ENGLISH_LOWER, InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(visiblePasswordInfo)))

        val webPasswordInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        assertEquals(InputModeSwitcher.MODE_SKB_ENGLISH_LOWER, InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(webPasswordInfo)))
    }

    @Test
    fun testSkbModeForEmailVariationsForcesEnglishLower() {
        val emailInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        assertEquals(InputModeSwitcher.MODE_SKB_ENGLISH_LOWER, InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(emailInfo)))
    }

    @Test
    fun testSkbModeForNormalTextReturnsNullForDefaultResolution() {
        val normalTextInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT
        }
        assertNull(InputModeSwitcher.skbModeForEditorFlags(InputModeSwitcher.parseEditorFlags(normalTextInfo)))
    }
}
