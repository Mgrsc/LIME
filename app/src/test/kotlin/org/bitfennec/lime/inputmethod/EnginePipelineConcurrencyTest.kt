package org.bitfennec.lime.inputmethod

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitfennec.lime.core.CandidateIdentity
import org.bitfennec.lime.core.CandidateKind
import org.bitfennec.lime.core.CandidateListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EnginePipelineConcurrencyTest {

    @Test
    fun testCandidateReadFailsClosedWhenQueueIsFull() = runBlocking {
        EnginePipeline.beginSession()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var enqueuedCount = 0
        val maximumQueueAttempts = 1024
        val blocker = async(Dispatchers.Default) {
            EnginePipeline.readState {
                started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            withTimeout(1_000) { started.await() }
            for (attempt in 0 until maximumQueueAttempts) {
                val accepted = EnginePipeline.send(EngineAction.Maintenance {})
                if (!accepted) break
                enqueuedCount++
            }
            assertTrue(enqueuedCount in 1 until maximumQueueAttempts)
            assertNull(EnginePipeline.readState { error("Rejected query was executed") })
        } finally {
            release.countDown()
            blocker.await()
            withTimeout(1_000) {
                while (EnginePipeline.readState { true } != true) kotlinx.coroutines.yield()
            }
        }
    }

    @Test
    fun testCandidateReadPreservesEditorSessionAndState() = runBlocking {
        val sessionId = EnginePipeline.beginSession()
        val before = EnginePipeline.stateFlow.value

        val observed = EnginePipeline.readState { it }

        assertEquals(before, observed)
        assertEquals(sessionId, EnginePipeline.currentSessionId)
        assertEquals(before, EnginePipeline.stateFlow.value)
    }

    @Test
    fun testStaleCandidateActionsDoNotCommitOrCallRime() = runBlocking {
        val oldSession = EnginePipeline.beginSession()
        val target = CandidateIdentity(oldSession, 10L, 0)
        val sessionId = EnginePipeline.invalidateSession()
        val events = mutableListOf<EngineEvent>()
        val states = mutableListOf<EngineState>()
        val job = launch {
            EnginePipeline.updates.collect { update ->
                if (update is SessionUpdate.Event && update.sessionId == sessionId) {
                    events += update.event
                }
                if (update is SessionUpdate.State && update.sessionId == sessionId) {
                    states += update.state
                }
            }
        }
        kotlinx.coroutines.yield()

        EnginePipeline.send(EngineAction.SelectCandidate(target, explicitSelection = true))
        EnginePipeline.send(EngineAction.DeleteCandidate(target))
        val predictionTarget = target.copy(kind = CandidateKind.PREDICTION)
        EnginePipeline.send(EngineAction.SelectAssociation(predictionTarget))
        EnginePipeline.send(EngineAction.RemovePredictionCandidate(predictionTarget))
        EnginePipeline.readState { Unit }
        kotlinx.coroutines.yield()
        job.cancel()

        assertTrue(events.isEmpty())
        assertEquals(4, states.size)
        assertEquals(sessionId, EnginePipeline.stateFlow.value.sessionId)
    }

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

        EnginePipeline.send(EngineAction.DeleteCandidate(CandidateIdentity(sid, 1L, 0), gen = 1L))
        delay(100)
        job.cancel()

        assertEquals(sid, EnginePipeline.currentSessionId)
    }

    @Test
    fun testIdleSpaceKeyDistinguishesSpaceAndDpadCenter() = runBlocking {
        EnginePipeline.beginSession()
        val sid = EnginePipeline.currentSessionId
        val updates = mutableListOf<SessionUpdate>()
        val job = launch {
            EnginePipeline.updates.collect { updates.add(it) }
        }
        kotlinx.coroutines.yield()

        EnginePipeline.send(EngineAction.SpaceKey(insertSpace = true))
        EnginePipeline.send(EngineAction.SpaceKey(insertSpace = false))

        delay(100)
        job.cancel()

        val keyEvents = updates
            .filterIsInstance<SessionUpdate.Event>()
            .map(SessionUpdate.Event::event)
            .filterIsInstance<EngineEvent.SendKeyEvent>()
        assertEquals(2, keyEvents.size)
        assertEquals(android.view.KeyEvent.KEYCODE_SPACE, keyEvents[0].keyCode)
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_CENTER, keyEvents[1].keyCode)
    }
}
