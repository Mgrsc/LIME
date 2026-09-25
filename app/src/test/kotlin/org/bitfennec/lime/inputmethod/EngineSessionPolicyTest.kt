package org.bitfennec.lime.inputmethod

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitfennec.lime.core.Rime
import org.bitfennec.lime.inputmethod.predict.PredictEngine
import org.bitfennec.lime.manager.InputModeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSessionPolicyTest {

    @Test
    fun bindEditorRestartKeepsSessionAndUpdatesFlags() {
        val key = EditorSessionKey("org.example.editor", 3, 1, "")
        val first = EnginePipeline.bindEditor(key, InputModeSwitcher.EditorFieldFlags(), restarting = false)
        val restarted = EnginePipeline.bindEditor(
            key,
            InputModeSwitcher.EditorFieldFlags(isNoPersonalizedLearning = true),
            restarting = true,
        )
        assertEquals(first, restarted)
        assertEquals(first, EnginePipeline.currentSessionId)
    }

    @Test
    fun applyEditorPolicyThenCharCaseStayOnSameSession() = runBlocking {
        val records = CopyOnWriteArrayList<String>()
        InputPipelineTrace.setTestSink(records::add)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = async(Dispatchers.Default) {
            EnginePipeline.executeExclusive {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val key = EditorSessionKey("org.example.policy", 1, 1, "")
            val flags = InputModeSwitcher.EditorFieldFlags()
            val sessionId = EnginePipeline.bindEditor(key, flags, restarting = false)
            assertTrue(
                EnginePipeline.send(
                    EngineAction.ApplyEditorPolicy(
                        schema = "pinyin",
                        charCase = 1,
                        clearComposition = true,
                    )
                )
            )
            assertTrue(EnginePipeline.send(EngineAction.SetCharCase(1)))
            release.countDown()
            withTimeout(10_000L) { EnginePipeline.stateFlow.first { it.sessionId == sessionId && it.charCase == 1 } }
            gate.await()
            assertFalse(records.any {
                it.contains("ime.action_dropped") && it.contains("apply_editor_policy")
            })
            assertEquals(sessionId, EnginePipeline.currentSessionId)
            assertEquals(1, EnginePipeline.stateFlow.value.charCase)
        } finally {
            release.countDown()
            gate.await()
            InputPipelineTrace.setTestSink(null)
        }
    }

    @Test
    fun invalidateStillDropsStaleSchemaAction() = runBlocking {
        val records = CopyOnWriteArrayList<String>()
        InputPipelineTrace.setTestSink(records::add)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = async(Dispatchers.Default) {
            EnginePipeline.executeExclusive {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val sessionId = EnginePipeline.bindEditor(
                EditorSessionKey("org.example.drop", 1, 1, ""),
                InputModeSwitcher.EditorFieldFlags(),
            )
            assertTrue(EnginePipeline.send(EngineAction.SelectSchema("english")))
            val resetSession = EnginePipeline.invalidateSession()
            assertTrue(resetSession > sessionId)
            assertTrue(EnginePipeline.send(EngineAction.SetCharCase(1)))
            release.countDown()
            withTimeout(10_000L) { EnginePipeline.stateFlow.first { it.charCase == 1 } }
            gate.await()
            assertTrue(records.any {
                it.contains("ime.action_dropped") && it.contains("select_schema")
            })
        } finally {
            release.countDown()
            gate.await()
            InputPipelineTrace.setTestSink(null)
        }
    }

    @Test
    fun applyEditorPolicyClearsPredictContextWithoutErasingLearnedPairs() = runBlocking {
        PredictEngine.user1Gram.clear()
        PredictEngine.user2Gram.clear()
        PredictEngine.resetContext()
        PredictEngine.recordCommit("甲乙", 1_000L)
        PredictEngine.recordCommit("丙丁", 1_200L)
        assertEquals(listOf("丙丁"), PredictEngine.user1Gram.getPredictions("甲乙"))

        EnginePipeline.bindEditor(
            EditorSessionKey("org.example.next", 2, 1, ""),
            InputModeSwitcher.EditorFieldFlags(),
        )
        EnginePipeline.send(
            EngineAction.ApplyEditorPolicy(
                schema = "pinyin",
                charCase = 0,
                clearComposition = true,
                gen = 7001L,
            )
        )
        withTimeout(10_000L) { EnginePipeline.stateFlow.first { it.gen == 7001L } }
        assertEquals("", PredictEngine.getLastWord())
        PredictEngine.recordCommit("戊己", 2_000L)
        assertEquals(listOf("丙丁"), PredictEngine.user1Gram.getPredictions("甲乙"))
        assertTrue(PredictEngine.user1Gram.getPredictions("甲乙").isNotEmpty())
        PredictEngine.user1Gram.clear()
        PredictEngine.user2Gram.clear()
        PredictEngine.resetContext()
    }

    @Test
    fun queuedPolicyPreservesLatestPrivacyAndFailureState() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = async(Dispatchers.Default) {
            EnginePipeline.executeExclusive {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val key = EditorSessionKey("review.editor", 1, 1, "")
            val ordinary = InputModeSwitcher.EditorFieldFlags()
            val session = EnginePipeline.bindEditor(key, ordinary)
            assertTrue(EnginePipeline.send(EngineAction.ApplyEditorPolicy(
                schema = "pinyin", charCase = 0,
                gen = 8001L,
            )))
            assertEquals(session, EnginePipeline.bindEditor(
                key, ordinary.copy(isNoPersonalizedLearning = true), restarting = true,
            ))
            EnginePipeline.setChinesePredictionEnabled(false)
            release.countDown()
            withTimeout(10_000L) { EnginePipeline.stateFlow.first { it.gen == 8001L } }
            gate.await()
            val policy = EnginePipeline.javaClass.getDeclaredField("activePolicy").apply {
                isAccessible = true
            }.get(EnginePipeline)
            val flags = policy.javaClass.getDeclaredField("flags").apply {
                isAccessible = true
            }.get(policy) as InputModeSwitcher.EditorFieldFlags
            val predictionEnabled = policy.javaClass.getDeclaredField("chinesePredictionEnabled").apply {
                isAccessible = true
            }.getBoolean(policy)
            assertTrue(flags.isNoPersonalizedLearning)
            assertFalse(predictionEnabled)
            assertFalse(Rime.isStarted)
            assertFalse(EnginePipeline.stateFlow.value.engineReady)
            assertTrue(EnginePipeline.send(EngineAction.ClearComposition(gen = 8002L)))
            withTimeout(10_000L) { EnginePipeline.stateFlow.first { it.gen == 8002L } }
            assertFalse(Rime.isStarted)
            assertFalse(EnginePipeline.stateFlow.value.engineReady)
            assertEquals(org.bitfennec.lime.inputmethod.predict.CandidateStripMode.UNAVAILABLE, EnginePipeline.stateFlow.value.stripMode)
        } finally {
            release.countDown()
            gate.await()
            EnginePipeline.setChinesePredictionEnabled(true)
            EnginePipeline.invalidateSession()
        }
    }
}
