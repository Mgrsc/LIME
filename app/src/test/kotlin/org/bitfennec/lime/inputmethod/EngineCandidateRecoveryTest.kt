package org.bitfennec.lime.inputmethod

import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.core.RimeSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineCandidateRecoveryTest {
    private val refreshRequests = AtomicInteger()
    private lateinit var originalRefreshSnapshotProvider: () -> RimeSnapshot?

    @Before
    fun setUp() = runBlocking {
        checkNotNull(EnginePipeline.readState { Unit })
        EnginePipeline.beginSession()
        checkNotNull(EnginePipeline.readState {
            RimeEngine.reset()
            setEngineField("currentSchema", "pinyin")
            setEngineField("rawInput", "nihao")
            setEngineField("compositionLiteralText", "nihao")
            RimeEngine.showCandidates = listOf(CandidateListItem("", "\u4f60\u597d"))
            originalRefreshSnapshotProvider = RimeEngine.refreshSnapshotProvider
            RimeEngine.refreshSnapshotProvider = {
                refreshRequests.incrementAndGet()
                null
            }
        })
    }

    @After
    fun tearDown() = runBlocking {
        checkNotNull(EnginePipeline.readState { Unit })
        checkNotNull(EnginePipeline.readState {
            if (::originalRefreshSnapshotProvider.isInitialized) {
                RimeEngine.refreshSnapshotProvider = originalRefreshSnapshotProvider
            }
            RimeEngine.reset()
        })
        EnginePipeline.invalidateSession()
        Unit
    }

    @Test
    fun cleanupBeforeSetupPreservesRefreshProvider() = runBlocking {
        val provider = checkNotNull(EnginePipeline.readState { RimeEngine.refreshSnapshotProvider })
        EngineCandidateRecoveryTest().tearDown()
        assertSame(provider, EnginePipeline.readState { RimeEngine.refreshSnapshotProvider })
    }

    @Test
    fun unavailableRefreshReturnsNullWithoutThrowing() = runBlocking {
        assertNull(EnginePipeline.readState { RimeEngine.refreshState() })
        assertEquals(1, refreshRequests.get())
    }

    @Test
    fun rejectedSpacePreservesCompositionWithoutCommit() =
        assertRejectedConfirmation(EngineAction.SpaceKey())

    @Test
    fun rejectedKeyFinalizationDoesNotCommitRawInput() =
        assertRejectedConfirmation(EngineAction.CommitThenKey(KeyEvent.KEYCODE_TAB))

    @Test
    fun rejectedTextFinalizationDoesNotCommitInputOrPunctuation() =
        assertRejectedConfirmation(EngineAction.CommitThenText(",", moveCursorLeft = true))

    @Test
    fun throwingRefreshPreservesCompositionWithoutCommit() = runBlocking {
        val failure = UnsatisfiedLinkError("Injected snapshot failure")
        checkNotNull(EnginePipeline.readState {
            RimeEngine.refreshSnapshotProvider = {
                refreshRequests.incrementAndGet()
                throw failure
            }
        })
        assertRejectedConfirmation(EngineAction.SpaceKey())
    }

    private fun assertRejectedConfirmation(action: EngineAction) = runBlocking {
        val events = CopyOnWriteArrayList<EngineEvent>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            EnginePipeline.updates.collect { update ->
                if (update is SessionUpdate.Event) events += update.event
            }
        }
        try {
            assertTrue(EnginePipeline.send(action))
            checkNotNull(EnginePipeline.readState { Unit })
            kotlinx.coroutines.yield()
            assertTrue("Rejected confirmation emitted an editor event: $events", events.isEmpty())
            assertEquals("Confirmation did not reach the controlled refresh", 1, refreshRequests.get())
            assertEquals("nihao", RimeEngine.rawCompositionText())
            assertFalse(EnginePipeline.stateFlow.value.engineReady)
            assertTrue(EnginePipeline.stateFlow.value.candidates.isEmpty())
        } finally {
            observer.cancel()
        }
    }

    private fun setEngineField(name: String, value: Any) {
        RimeEngine.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .set(RimeEngine, value)
    }
}
