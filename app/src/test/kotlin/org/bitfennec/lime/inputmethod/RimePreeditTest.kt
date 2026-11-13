package org.bitfennec.lime.inputmethod

import org.bitfennec.lime.core.RimeCandidatePage
import org.bitfennec.lime.core.RimeComposition
import org.bitfennec.lime.core.RimePageCandidate
import org.bitfennec.lime.core.RimeSnapshot
import org.bitfennec.lime.core.RimeT9Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class RimePreeditTest {
    @After
    fun clearPresentation() {
        applySnapshot(RimeSnapshot())
    }

    @Test
    fun preservesNativeBoundariesForFuzzyAndAlternativeSpellings() {
        val cases = listOf(
            Triple("wengzi", "weng zi", "wen zi"),
            Triple("shengme", "sheng me", "shen me"),
            Triple("zhenme", "zhen me", "zen me"),
            Triple("lvyou", "lü you", "lv you"),
            Triple("duen", "duen", "dun"),
            Triple("xiagn", "xiagn", "xiang"),
        )
        cases.forEach { (raw, preedit, comment) ->
            applySnapshot(raw, preedit, comment)
            assertEquals(raw, preedit, RimeEngine.showComposition)
        }
    }

    @Test
    fun preservesExplicitSeparatorsDuringDeletion() {
        listOf(
            Triple("weng'zi", "weng'zi", "wen zi"),
            Triple("weng'z", "weng'z", "weng zhu"),
            Triple("weng'", "weng'", "weng"),
        ).forEach { (raw, preedit, comment) ->
            applySnapshot(raw, preedit, comment)
            assertEquals(raw, preedit, RimeEngine.showComposition)
        }
    }

    @Test
    fun prefetchPreservesPresentationAndCursorUntilAnExplicitUpdate() {
        val composition = RimeComposition(
            length = 5,
            cursorPos = 3,
            preedit = "l\u00fc you",
            displayBoundaries = intArrayOf(0, 1, 2, 3, 4, 5, 6),
            rawBoundaries = intArrayOf(0, 1, 2, 2, 3, 4, 5),
            displayCursorPos = 4,
        )
        val initial = RimeSnapshot(schemaId = "pinyin", rawInput = "lvyou", composition = composition)
        applySnapshot(initial)
        val published = RimeEngine.compositionPresentation
        val prefetched = initial.copy(composition = composition.copy(preedit = "lv you", displayCursorPos = 3))
        applySnapshot(prefetched, resetExpandedCandidates = false)
        assertSame(published, RimeEngine.compositionPresentation)
        assertEquals(4, RimeEngine.compositionPresentation.displayCursor)
        assertTrue(RimeEngine.matchesComposition(published.revision))

        applySnapshot(prefetched)
        assertEquals("lv you", RimeEngine.compositionPresentation.text)
        assertEquals(3, RimeEngine.compositionPresentation.displayCursor)
        assertFalse(RimeEngine.matchesComposition(published.revision))
    }

    @Test
    fun invalidatesStaleTargetsAfterInputSchemaAndClearTransitions() {
        applySnapshot("jdcm", "j d c m", "")
        val first = RimeEngine.compositionPresentation.revision
        applySnapshot("jdhj", "j d h j", "")
        assertFalse(RimeEngine.matchesComposition(first))
        val second = RimeEngine.compositionPresentation.revision
        applySnapshot(RimeSnapshot(schemaId = "double_pinyin_flypy", rawInput = "jdhj", composition = RimeComposition(length = 4, preedit = "j d h j")))
        assertFalse(RimeEngine.matchesComposition(second))
        val third = RimeEngine.compositionPresentation.revision
        applySnapshot(RimeSnapshot())
        assertFalse(RimeEngine.matchesComposition(third))
        assertTrue(RimeEngine.compositionPresentation.text.isEmpty())
        assertTrue(RimeEngine.compositionPresentation.boundaries.isEmpty())
    }

    @Test
    fun invalidatesTargetsWhenSelectionOrT9LockChangesWithoutChangingText() {
        val initial = RimeSnapshot(
            schemaId = "t9_pinyin",
            rawInput = "64",
            composition = RimeComposition(length = 2, cursorPos = 2, preedit = "ni", selEnd = 2),
            t9Metadata = RimeT9Metadata(),
        )
        applySnapshot(initial)
        val unlocked = RimeEngine.compositionPresentation.revision
        applySnapshot(initial.copy(t9Metadata = RimeT9Metadata(lockedCount = 1)))
        assertFalse(RimeEngine.matchesComposition(unlocked))
        val locked = RimeEngine.compositionPresentation.revision
        applySnapshot(initial.copy(composition = initial.composition.copy(selStart = 1),
            t9Metadata = RimeT9Metadata(lockedCount = 1)))
        assertFalse(RimeEngine.matchesComposition(locked))
    }

    private fun applySnapshot(raw: String, preedit: String, comment: String) {
        val snapshot = RimeSnapshot(
            schemaId = "pinyin",
            rawInput = raw,
            composition = RimeComposition(
                length = raw.length,
                cursorPos = raw.length,
                preedit = preedit,
                literalText = raw,
            ),
            page = RimeCandidatePage(
                candidates = arrayOf(RimePageCandidate(0, "", comment)),
            ),
        )
        applySnapshot(snapshot)
    }

    private fun applySnapshot(snapshot: RimeSnapshot, resetExpandedCandidates: Boolean = true) {
        val update = RimeEngine::class.java.getDeclaredMethod(
            "updateCandidatesOrCommitText",
            RimeSnapshot::class.java,
            Boolean::class.javaPrimitiveType,
        )
        update.isAccessible = true
        update.invoke(RimeEngine, snapshot, resetExpandedCandidates)
    }
}
