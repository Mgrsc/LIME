package org.bitfennec.lime.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class QwertyPinYinUtilsTest {

    @Test
    fun keepsRawSyllablesMissingFromFirstCandidateComment() {
        assertEquals("xi'an'", QwertyPinYinUtils.getQwertyComposition("xi'an", "xi"))
    }
}
