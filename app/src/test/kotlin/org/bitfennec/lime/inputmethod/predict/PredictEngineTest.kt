package org.bitfennec.lime.inputmethod.predict

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PredictEngineTest {

    companion object {
        // Paths assume Gradle test runner working directory is the :app module root.
        private val SYSTEM_PREDICT_ASSET = File("src/main/assets/predict/system_predict.tsv")
        private val REGRESSION_CASES = File("../tools/dictionary/prediction-regression.tsv")
        private val BASELINE_REPORT_DIR = File("build/reports/lexicon")
    }

    @Before
    fun setUp() {
        PredictEngine.user1Gram.clear()
        PredictEngine.user2Gram.clear()
        PredictEngine.resetContext()
        PredictEngine.resetCaches()
    }

    @After
    fun tearDown() {
        PredictEngine.user1Gram.clear()
        PredictEngine.user2Gram.clear()
        PredictEngine.resetContext()
        PredictEngine.resetCaches()
    }

    @Test
    fun testBundledPredictionBaseline() {
        val asset = SYSTEM_PREDICT_ASSET
        val cases = REGRESSION_CASES
        val loaded = linkedMapOf<String, MutableList<String>>()
        asset.forEachLine { line ->
            val fields = line.split('\t')
            assertEquals(3, fields.size)
            assertTrue(fields[2].toLong() > 0)
            val words = loaded.getOrPut(fields[0]) { mutableListOf() }
            assertFalse(words.contains(fields[1]))
            words.add(fields[1])
            assertTrue(words.size <= 8)
        }
        try {
            SystemPredictTable.replaceForTest(loaded.mapValues { it.value.toTypedArray() })
            val rows = cases.readLines().drop(1).map { line ->
                val fields = line.split('\t')
                assertEquals(8, fields.size)
                PredictEngine.resetContext()
                val context = fields[3]
                val candidates = PredictEngine.predictTypeACandidates(context, "")
                assertTrue(candidates.size <= 8)
                assertEquals(candidates.size, candidates.map { it.text }.distinct().size)
                assertTrue(candidates.all { it.source == PredictionSource.SYSTEM })
                val exact = SystemPredictTable.getSystemPredictions(context).orEmpty().toList()
                val accepted = fields[6].split('|').filter(String::isNotEmpty)
                assertTrue("Unexpected candidates for $context: $candidates", candidates.all { it.text in accepted })
                assertTrue("Missing candidates for $context", fields[7] == "true" || candidates.isNotEmpty())
                val rejected = fields[4].split('|').filter(String::isNotEmpty)
                assertFalse("Rejected candidates for $context", candidates.any { it.text in rejected })
                listOf(
                    fields[0], fields[1], fields[2], fields[3],
                    exact.joinToString("|"), candidates.joinToString("|") { it.text },
                    candidates.count { it.text in rejected }.toString(),
                ).joinToString("\t")
            }
            val directory = BASELINE_REPORT_DIR
            check(directory.isDirectory || directory.mkdirs())
            val report = File(directory, "prediction-baseline.tsv")
            report.writeText(
                "id\tsplit\tcategory\tcontext\texact\tcandidates\trejected_count\n" +
                    rows.joinToString("\n", postfix = "\n"),
            )
        } finally {
            SystemPredictTable.replaceForTest(emptyMap())
            PredictEngine.resetContext()
            PredictEngine.user1Gram.clear()
            PredictEngine.user2Gram.clear()
        }
    }

    @Test
    fun testNegativeCacheDoesNotHideLearnedAssociations() {
        val context = "甲乙"
        val previous = "丙丁"
        val learned = "戊己"
        assertTrue(PredictEngine.predictTypeACandidates(context, "").isEmpty())

        PredictEngine.recordCommit(previous, 1000L)
        PredictEngine.recordCommit(context, 2000L)
        PredictEngine.recordCommit(learned, 3000L)

        assertEquals(
            listOf(PredictionCandidate(learned, PredictionSource.USER_1GRAM)),
            PredictEngine.predictTypeACandidates(context, ""),
        )
        assertEquals(
            listOf(PredictionCandidate(learned, PredictionSource.USER_2GRAM)),
            PredictEngine.predictTypeACandidates(context, previous),
        )

        assertTrue(PredictEngine.removeUserPair(context, learned))
        assertTrue(PredictEngine.predictTypeACandidates(context, "其他").isEmpty())
        assertEquals(
            listOf(PredictionCandidate(learned, PredictionSource.USER_2GRAM)),
            PredictEngine.predictTypeACandidates(context, previous),
        )
        assertTrue(PredictEngine.user2Gram.removeTri(previous, context, learned))
        assertTrue(PredictEngine.predictTypeACandidates(context, previous).isEmpty())
    }

    @Test
    fun testLearningInvalidatesHotCache() {
        // 1. Initial query for system phrase caches results in hotCache
        val firstQuery = PredictEngine.predictTypeA("中国")
        assertTrue(firstQuery.contains("人民"))
        assertFalse(firstQuery.contains("留青科技"))

        // 2. Consecutive commits learn new phrase pair
        val t0 = 1000000L
        PredictEngine.recordCommit("中国", t0)
        PredictEngine.recordCommit("留青科技", t0 + 1000)

        // 3. Subsequent query invalidates hotCache and prioritizes learned phrase
        val secondQuery = PredictEngine.predictTypeA("中国")
        assertTrue(secondQuery.contains("留青科技"))
        assertEquals("留青科技", secondQuery.first())
    }

    @Test
    fun testSystemPredictExactMatch() {
        val predictions = PredictEngine.predictTypeA("中国")
        assertTrue("Predictions for 中国 should not be empty", predictions.isNotEmpty())
        assertTrue("Predictions for 中国 should contain 人民", predictions.contains("人民"))
        assertTrue("Predictions for 中国 should contain 银行", predictions.contains("银行"))
    }

    @Test
    fun testRepeatedTypeAQueryKeepsSystemPredictions() {
        val first = PredictEngine.predictTypeA("你好")
        val second = PredictEngine.predictTypeA("你好")

        assertTrue(first.isNotEmpty())
        assertEquals(first, second)
    }

    @Test
    fun testTypeACandidatesExposePredictionSource() {
        val candidates = PredictEngine.predictTypeACandidates("中国")

        assertTrue(candidates.any { it.text == "人民" && it.source == PredictionSource.SYSTEM })
    }

    @Test
    fun testTypeADroppedWhenNextKeyComes() {
        assertFalse(PredictRequestValidator.acceptsTypeA(1, 1, 2, 2, "r", false))
        assertFalse(PredictRequestValidator.acceptsTypeA(1, 1, 2, 2, "", true))
        assertTrue(PredictRequestValidator.acceptsTypeA(1, 1, 2, 2, "", false))
    }

    @Test
    fun testUnknownContextDoesNotBorrowSuffixPredictions() {
        assertTrue(PredictEngine.predictTypeA("中国人民").isEmpty())
        assertTrue(PredictEngine.predictTypeA("庚辛壬癸甲乙").isEmpty())
        assertTrue(PredictEngine.predictTypeA("这是很长的中国").isEmpty())
        assertTrue(PredictEngine.predictTypeA("中国").isNotEmpty())
    }

    @Test
    fun testBackoffReturnsEmptyWhenNoMatch() {
        // Rare words with no associations return empty list without fallback artifacts
        val predictions = PredictEngine.predictTypeA("未知生僻词汇测试")
        assertTrue("Predictions for unknown word should be completely empty", predictions.isEmpty())
    }

    @Test
    fun testUser1GramFiltersSingleChar() {
        val t0 = 1000000L
        // Single-character pair is rejected by 2..6 length filter
        PredictEngine.recordCommit("的", t0)
        PredictEngine.recordCommit("了", t0 + 1000)
        val singleCharPreds = PredictEngine.predictTypeA("的")
        assertFalse("Single char should not learn '了'", singleCharPreds.contains("了"))

        // Multi-character phrase is learned correctly
        PredictEngine.recordCommit("留青", t0 + 2000)
        PredictEngine.recordCommit("输入法", t0 + 3000)
        val userPreds = PredictEngine.predictTypeA("留青")
        assertTrue("User prediction should contain '输入法'", userPreds.contains("输入法"))
        assertEquals("User learned word should be ranked first", "输入法", userPreds.first())
    }

    @Test
    fun testUser2GramLearning() {
        val t0 = 1000000L
        PredictEngine.recordCommit("但是", t0)
        PredictEngine.recordCommit("我们", t0 + 2000)
        PredictEngine.recordCommit("必须", t0 + 4000)

        val triPreds = PredictEngine.predictTypeA("我们", "但是")
        assertTrue("User 2-gram should contain '必须'", triPreds.contains("必须"))
    }

    @Test
    fun testFifteenSecondTimeoutBreaksAssociation() {
        val t0 = 1000000L
        PredictEngine.recordCommit("留青", t0)
        // Commit after 15s window (20000ms) does not form association
        PredictEngine.recordCommit("测试", t0 + 20000)

        val preds = PredictEngine.predictTypeA("留青")
        assertFalse("Timeout over 15s should not form pair", preds.contains("测试"))
    }

    @Test
    fun testFifteenSecondTimeoutResetsTriChain() {
        val t0 = 1000000L
        PredictEngine.recordCommit("留青", t0)
        PredictEngine.recordCommit("测试", t0 + 20000)
        PredictEngine.recordCommit("后续", t0 + 21000)

        assertFalse(
            PredictEngine.predictTypeACandidates("测试", "留青")
                .any { it.text == "后续" && it.source == PredictionSource.USER_2GRAM }
        )
    }

    @Test
    fun testFunctionWordsFilteredFromCandidates() {
        val predictions = PredictEngine.predictTypeA("今天")
        // Function words never appear in candidate list
        for (w in predictions) {
            assertFalse("Candidate '$w' should not be a functional word", PredictCaches.FUNCTION_WORDS.contains(w))
        }
    }

    @Test
    fun testRemoveUserPair() {
        val t0 = 1000000L
        PredictEngine.recordCommit("临时词对", t0)
        PredictEngine.recordCommit("删除测试", t0 + 1000)
        assertTrue(PredictEngine.predictTypeA("临时词对").contains("删除测试"))

        PredictEngine.removeUserPair("临时词对", "删除测试")
        assertFalse("Removed pair should no longer appear in predictions", PredictEngine.predictTypeA("临时词对").contains("删除测试"))
    }

    @Test
    fun testRemoveUserPredictionKeepsSystemPredictions() {
        val t0 = 1000000L
        PredictEngine.recordCommit("中国", t0)
        PredictEngine.recordCommit("留青科技", t0 + 1000)
        PredictEngine.recordCommit("中国", t0 + 2000)

        val learned = PredictEngine.predictTypeACandidates("中国")
        assertTrue(learned.any { it.text == "留青科技" && it.source == PredictionSource.USER_1GRAM })
        assertTrue(PredictEngine.removeUserPrediction(PredictionSource.USER_1GRAM, "留青科技"))

        val afterRemoval = PredictEngine.predictTypeACandidates("中国")
        assertFalse(afterRemoval.any { it.text == "留青科技" })
        assertTrue(afterRemoval.any { it.text == "人民" && it.source == PredictionSource.SYSTEM })
    }

    @Test
    fun testUserMemoriesEnforcePairCapacities() {
        val user1 = UserPredict1GramMemory(maxCapacity = 2)
        user1.recordPair("甲甲", "乙乙", 1)
        user1.recordPair("丙丙", "丁丁", 2)
        user1.recordPair("戊戊", "己己", 3)
        assertEquals(2, user1.pairCount())

        val user2 = UserPredict2GramMemory(maxCapacity = 2)
        user2.recordTri("甲甲", "乙乙", "丙丙", 1)
        user2.recordTri("丁丁", "戊戊", "己己", 2)
        user2.recordTri("庚庚", "辛辛", "壬壬", 3)
        assertEquals(2, user2.triCount())
    }

    @Test
    fun testCandidateStripModeTransitions() {
        assertEquals(CandidateStripMode.EMPTY, CandidateStripMode.valueOf("EMPTY"))
        assertEquals(CandidateStripMode.DECODING, CandidateStripMode.valueOf("DECODING"))
        assertEquals(CandidateStripMode.PREDICT, CandidateStripMode.valueOf("PREDICT"))
    }

    @Test
    fun testSystemAssetPredictionsOverrideFallback() {
        try {
            SystemPredictTable.replaceForTest(mapOf("中国" to arrayOf("经济", "人民")))

            assertEquals(listOf("经济", "人民"), SystemPredictTable.getSystemPredictions("中国")?.toList())
        } finally {
            SystemPredictTable.replaceForTest(emptyMap())
        }
    }
}
