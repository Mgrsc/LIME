package org.bitfennec.lime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.core.Rime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RimePhraseConsolidationInstrumentationTest {

    private lateinit var targetContext: Context

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        Launcher.instance.ensureRimeAssets()
        Rime.startup(targetContext, fullCheck = false)
        Rime.selectRimeSchema("t9_pinyin")
        Rime.setUserDictWritesEnabled(true)
        Rime.clearCompositionSnapshot()
    }

    @After
    fun tearDown() {
        Rime.clearCompositionSnapshot()
        Rime.setUserDictWritesEnabled(false)
    }

    @Test
    fun testInterCommitPhraseConsolidation() {
        // Step 1: Input 56645426 ("kongjian") and select "空间"
        for (ch in "56645426") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        var snap = Rime.snapshotState()
        assertNotNull(snap)
        val kongJianCandidates = snap!!.page.candidates.map { it.text }
        android.util.Log.i("RimeTest", "Candidates for 56645426: $kongJianCandidates")
        val kongJianIndex = snap.page.candidates.indexOfFirst { it.text == "空间" }
        assertTrue("Candidate '空间' should appear for 56645426 (got $kongJianCandidates)", kongJianIndex >= 0)

        snap = Rime.selectCandidateOnPageSnapshot(snap.page.pageNo, snap.page.pageNo, kongJianIndex)
        assertNotNull(snap)
        assertTrue("CommitText should contain '空间'", snap!!.commitText?.contains("空间") == true)

        // Step 2: Immediately within 3000ms, input 54546 ("jijin") and select "基金"
        for (ch in "54546") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        snap = Rime.snapshotState()
        assertNotNull(snap)
        val jiJinCandidates = snap!!.page.candidates.map { it.text }
        android.util.Log.i("RimeTest", "Candidates for 54546: $jiJinCandidates")
        val jiJinIndex = snap.page.candidates.indexOfFirst { it.text == "基金" }
        assertTrue("Candidate '基金' should appear for 54546 (got $jiJinCandidates)", jiJinIndex >= 0)

        snap = Rime.selectCandidateOnPageSnapshot(snap.page.pageNo, snap.page.pageNo, jiJinIndex)
        assertNotNull(snap)
        assertTrue("CommitText should contain '基金'", snap!!.commitText?.contains("基金") == true)

        // Step 3: Query "5664542654546" (full code for kongjianjijin)
        // Verify "空间基金" was consolidated and is recalled
        Rime.clearCompositionSnapshot()
        for (ch in "5664542654546") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        var recallSnap = Rime.snapshotState()
        assertNotNull(recallSnap)
        var recallCandidates = recallSnap!!.page.candidates.map { it.text }
        android.util.Log.i("RimeTest", "Candidates for 5664542654546: $recallCandidates")
        assertTrue("Consolidated phrase '空间基金' must appear for 5664542654546", recallCandidates.contains("空间基金"))

        // Step 4: Query "5555" (4-initial abbreviation for k-j-j-j)
        Rime.clearCompositionSnapshot()
        for (ch in "5555") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        recallSnap = Rime.snapshotState()
        assertNotNull(recallSnap)
        recallCandidates = recallSnap!!.page.candidates.map { it.text }
        android.util.Log.i("RimeTest", "Candidates for 5555: $recallCandidates")
        assertTrue("Consolidated phrase '空间基金' must appear for 5555", recallCandidates.contains("空间基金"))

        // Cleanup: remove the learned phrase from user dictionary to avoid polluting userdb
        val phraseIdx = recallSnap.page.candidates.indexOfFirst { it.text == "空间基金" }
        if (phraseIdx >= 0) {
            Rime.deleteCandidateOnPageSnapshot(recallSnap.page.pageNo, recallSnap.page.pageNo, phraseIdx)
        }
        Rime.clearCompositionSnapshot()
    }

    @Test
    fun testT9SelectNiAndHaoPrefix() {
        Rime.clearCompositionSnapshot()
        for (ch in "64426") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        var snap = Rime.snapshotState()
        assertNotNull(snap)
        android.util.Log.i("RimeTest", "Initial candidates for 64426: ${snap!!.page.candidates.map { it.text }}")

        // Step 1: Select prefix "ni"
        snap = Rime.selectT9PrefixSnapshot("ni")
        assertNotNull(snap)
        android.util.Log.i("RimeTest", "After select 'ni': pageNo=${snap!!.page.pageNo}, preedit=${snap.composition.preedit}, t9_preedit=${snap.t9Metadata?.preedit}, candidates=${snap.page.candidates.map { it.text }}")

        // Simulate load_more_expanded_candidates advancing pages
        var curPage = snap.page.pageNo
        val nextSnap1 = Rime.moveCandidatePageSnapshot(curPage, 1)
        if (nextSnap1 != null) {
            curPage = nextSnap1.page.pageNo
            val nextSnap2 = Rime.moveCandidatePageSnapshot(curPage, 1)
            if (nextSnap2 != null) curPage = nextSnap2.page.pageNo
        }
        android.util.Log.i("RimeTest", "Advanced to page: $curPage")

        // Step 2: Select prefix "hao"
        snap = Rime.selectT9PrefixSnapshot("hao")
        assertNotNull(snap)
        android.util.Log.i("RimeTest", "After select 'hao': pageNo=${snap!!.page.pageNo}, preedit=${snap.composition.preedit}, t9_preedit=${snap.t9Metadata?.preedit}, candidates=${snap.page.candidates.map { it.text }}")
        assertEquals(0, snap.page.pageNo)
        assertTrue("Candidates must contain '你好' after selecting 'hao'", snap.page.candidates.any { it.text == "你好" })
        assertEquals("ni'hao", snap.t9Metadata?.preedit)

        Rime.clearCompositionSnapshot()
    }

    @Test
    fun testSheiACandidateRanking() {
        Rime.selectRimeSchema("pinyin")
        Rime.clearCompositionSnapshot()
        for (ch in "sheia") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        val snap = Rime.snapshotState()
        assertNotNull(snap)
        val candidates = snap!!.page.candidates.map { it.text }
        android.util.Log.i("RimeTest", "Candidates for 'sheia' in pinyin: $candidates, preedit=${snap.composition.preedit}")
        assertTrue("Candidate list should not be empty", candidates.isNotEmpty())
        assertEquals("谁啊", candidates.first())
        val sheiAIndex = candidates.indexOf("谁啊")
        val shiHeiAIndex = candidates.indexOf("是黑啊")
        val sheHeiAnIndex = candidates.indexOf("涉黑案")
        assertTrue("Full-spelling candidate '谁啊' must exist", sheiAIndex >= 0)
        if (shiHeiAIndex >= 0) {
            assertTrue("Full spelling '谁啊' ($sheiAIndex) must precede abbreviation '是黑啊' ($shiHeiAIndex)", sheiAIndex < shiHeiAIndex)
        }
        if (sheHeiAnIndex >= 0) {
            assertTrue("Full spelling '谁啊' ($sheiAIndex) must precede abbreviation '涉黑案' ($sheHeiAnIndex)", sheiAIndex < sheHeiAnIndex)
        }
        Rime.clearCompositionSnapshot()
    }

    @Test
    fun testNmAbbreviationCandidateRanking() {
        Rime.selectRimeSchema("pinyin")
        Rime.clearCompositionSnapshot()

        // Best-effort cleanup of any previously learned "嗯呒" candidate in userdb
        for (ch in "nm") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        val initialSnap = Rime.snapshotState()
        if (initialSnap != null) {
            val idx = initialSnap.page.candidates.indexOfFirst { it.text == "嗯呒" }
            if (idx >= 0) {
                Rime.deleteCandidateOnPageSnapshot(initialSnap.page.pageNo, initialSnap.page.pageNo, idx)
            }
        }
        Rime.clearCompositionSnapshot()

        // 1. Verify "nm" abbreviation ranking
        for (ch in "nm") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        val snap = Rime.snapshotState()
        assertNotNull(snap)
        val candidates = snap!!.page.candidates.map { it.text }
        android.util.Log.i("RimeTest", "Candidates for 'nm' in pinyin: $candidates, preedit=${snap.composition.preedit}")
        assertTrue("Candidate list for 'nm' should not be empty", candidates.isNotEmpty())
        assertTrue(
            "Top candidate for 'nm' must be an abbreviation word (e.g. 那么, 你们, 农民), got: ${candidates.first()}",
            candidates.first() in setOf("那么", "农民", "你妹", "纳米", "难免", "你们")
        )
        assertFalse("Candidates for 'nm' must not contain '嗯呒', got: $candidates", candidates.contains("嗯呒"))
        Rime.clearCompositionSnapshot()

        // 2. Verify single consonant ranking: 'n' must not treat '嗯' as top candidate
        Rime.processKeySnapshot('n'.code, 0)
        val nSnap = Rime.snapshotState()
        assertNotNull(nSnap)
        val nCandidates = nSnap!!.page.candidates.map { it.text }
        android.util.Log.i("RimeTest", "Candidates for single 'n': $nCandidates")
        assertNotEquals("Top candidate for single 'n' must not be '嗯', got: ${nCandidates.first()}", "嗯", nCandidates.first())
        Rime.clearCompositionSnapshot()

        // 3. Verify 'en' and 'ng' produce '嗯'
        for (ch in "en") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        val enSnap = Rime.snapshotState()
        assertNotNull(enSnap)
        val enCandidates = enSnap!!.page.candidates.map { it.text }
        assertTrue("Candidates for 'en' must contain '嗯', got: $enCandidates", enCandidates.contains("嗯"))
        Rime.clearCompositionSnapshot()

        for (ch in "ng") {
            Rime.processKeySnapshot(ch.code, 0)
        }
        val ngSnap = Rime.snapshotState()
        assertNotNull(ngSnap)
        val ngCandidates = ngSnap!!.page.candidates.map { it.text }
        assertTrue("Candidates for 'ng' must contain '嗯', got: $ngCandidates", ngCandidates.contains("嗯"))
        Rime.clearCompositionSnapshot()
    }
}

