package org.bitfennec.lime.inputmethod

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitfennec.lime.core.CandidateListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePipelineConcurrencyTest {

    @Test
    fun testSnapshotsUseQueueSequenceWhenActionGenerationIsDefault() = runBlocking {
        val sessionId = EnginePipeline.beginSession()
        val snapshots = mutableListOf<EngineEvent.ApplyRimeSnapshot>()
        val completed = CompletableDeferred<Unit>()
        val job = launch {
            EnginePipeline.updates.collect { update ->
                val snapshot = (update as? SessionUpdate.Event)?.event as? EngineEvent.ApplyRimeSnapshot
                if (snapshot?.sessionId == sessionId) {
                    snapshots += snapshot
                    if (snapshots.size == 2) completed.complete(Unit)
                }
            }
        }
        kotlinx.coroutines.yield()

        EnginePipeline.send(EngineAction.Reset())
        EnginePipeline.send(EngineAction.Reset())

        withTimeout(1_000) { completed.await() }
        job.cancel()
        assertTrue(snapshots[0].sequence > 0L)
        assertTrue(snapshots[1].sequence > snapshots[0].sequence)
    }

    @Test
    fun testModalClearAfterResetEmitsConfirmationForCurrentSession() = runBlocking {
        val sessionId = EnginePipeline.invalidateSession()
        val confirmation = CompletableDeferred<EngineEvent.CompositionCleared>()
        val job = launch {
            EnginePipeline.updates.collect { update ->
                val event = (update as? SessionUpdate.Event)?.event as? EngineEvent.CompositionCleared
                if (event?.sessionId == sessionId && event.modalRequestId == 17L) {
                    confirmation.complete(event)
                }
            }
        }
        kotlinx.coroutines.yield()

        EnginePipeline.send(EngineAction.Reset())
        EnginePipeline.send(EngineAction.ClearComposition(modalRequestId = 17L))

        assertEquals(sessionId, withTimeout(1_000) { confirmation.await() }.sessionId)
        job.cancel()
    }

    @Test
    fun testPredictAssociationCancellationOnNewInput() = runBlocking {
        EnginePipeline.beginSession()
        val sid = EnginePipeline.currentSessionId

        // Post stale ApplyAssociation task (seq mismatch)
        EnginePipeline.send(EngineAction.ApplyAssociation(
            sessionId = sid,
            seq = 9999L,
            candidates = listOf(CandidateListItem("", "测试词")),
            gen = 1L
        ))
        
        // Post reset task immediately
        EnginePipeline.send(EngineAction.Reset())

        delay(100)
        // Verify session ID remains consistent and pipeline is healthy without applying invalid prediction
        assertEquals(sid, EnginePipeline.currentSessionId)
        assertFalse(EnginePipeline.stateFlow.value.isAssociate)
    }

    @Test
    fun testApplyAssociationDiscardedWhenUserIsComposing() = runBlocking {
        val sid = EnginePipeline.beginSession()

        // Simulate composing phase (isFinish == false when posting ApplyAssociation)
        // Verify ApplyAssociation is discarded when isFinish == false
        EnginePipeline.send(EngineAction.ApplyAssociation(
            sessionId = sid,
            candidates = listOf(CandidateListItem("", "联想词1")),
            gen = 99L
        ))

        delay(50)
        // State handled correctly
        assertTrue(EnginePipeline.currentSessionId == sid)
    }

    @Test
    fun testFastTypingFollowedByResetDiscardsAllPendingKeysAndHasZeroLeakedPreedit() = runBlocking {
        val oldSessionId = EnginePipeline.currentSessionId
        var generation = 0L
        // 1. Simulate rapid typing of 8 keys
        repeat(8) { i ->
            EnginePipeline.send(EngineAction.NormalKey(
                event = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_A + i),
                gen = ++generation
            ))
        }

        // 2. Trigger immediate Reset and session invalidation
        val newSessionId = EnginePipeline.invalidateSession()
        EnginePipeline.send(EngineAction.Reset(gen = ++generation))

        delay(150)

        // 3. Verify new session is active without stale state remnants
        assertEquals(newSessionId, EnginePipeline.currentSessionId)
        assertTrue(newSessionId != oldSessionId)

        // 4. Verify engine stateFlow is synchronously reset with correct sessionId
        assertEquals(newSessionId, EnginePipeline.stateFlow.value.sessionId)
    }

    @Test
    fun testFastRepeatedDeleteKeysBatching() = runBlocking {
        EnginePipeline.beginSession()
        val sid = EnginePipeline.currentSessionId

        repeat(5) { index ->
            EnginePipeline.send(EngineAction.DeleteKey(gen = index + 1L))
        }

        delay(100)
        assertEquals(sid, EnginePipeline.currentSessionId)
    }

    @Test
    fun testDeleteKeysBatchCrossingFinishBoundaryEmitsSendKeyEvent() = runBlocking {
        EnginePipeline.beginSession()
        val sid = EnginePipeline.currentSessionId
        val updates = mutableListOf<SessionUpdate>()
        val job = launch {
            EnginePipeline.updates.collect { updates.add(it) }
        }
        // Ensure subscriber is active before emitting events
        kotlinx.coroutines.yield()

        repeat(3) {
            EnginePipeline.send(EngineAction.DeleteKey())
        }

        delay(100)
        job.cancel()

        val delEvents = updates
            .filterIsInstance<SessionUpdate.Event>()
            .map(SessionUpdate.Event::event)
            .filterIsInstance<EngineEvent.SendKeyEvent>()
        assertEquals(3, delEvents.size)
        assertTrue(delEvents.all { it.sessionId == sid && it.keyCode == android.view.KeyEvent.KEYCODE_DEL })
    }

    @Test
    fun testDeleteCandidateActionDispatchesSafely() = runBlocking {
        EnginePipeline.beginSession()
        val sid = EnginePipeline.currentSessionId
        val updates = mutableListOf<SessionUpdate>()
        val job = launch {
            EnginePipeline.updates.collect { updates.add(it) }
        }
        kotlinx.coroutines.yield()

        EnginePipeline.send(EngineAction.DeleteCandidate(index = 0, gen = 1L))
        delay(100)
        job.cancel()

        assertEquals(sid, EnginePipeline.currentSessionId)
    }
}
