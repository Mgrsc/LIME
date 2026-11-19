package org.bitfennec.lime.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.inputmethod.EngineAction
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.inputmethod.EngineState
import org.bitfennec.lime.inputmethod.InputPipelineTrace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpandedCandidatePagingTest {
    private val records = CopyOnWriteArrayList<String>()
    private val stateFlow: MutableStateFlow<EngineState>
        @Suppress("UNCHECKED_CAST")
        get() = EnginePipeline.javaClass.getDeclaredField("_stateFlow").apply {
            isAccessible = true
        }.get(EnginePipeline) as MutableStateFlow<EngineState>

    @Before
    fun setUp() = runBlocking {
        checkNotNull(EnginePipeline.readState { Unit })
        Dispatchers.setMain(Dispatchers.Unconfined)
        EnginePipeline.beginSession()
        DecodingInfo.reset()
        stateFlow.value = EngineState(
            candidatePageNo = 0,
            candidateRevision = Long.MAX_VALUE - 1,
            hasNextCandidatePage = true,
            sessionId = EnginePipeline.currentSessionId,
        )
        InputPipelineTrace.setTestSink(records::add)
    }

    @After
    fun tearDown() = runBlocking {
        checkNotNull(EnginePipeline.readState { Unit })
        InputPipelineTrace.setTestSink(null)
        DecodingInfo.reset()
        EnginePipeline.invalidateSession()
        Dispatchers.resetMain()
    }

    @Test
    fun sharedEntryDeduplicatesRequestsFromTheSameState() = runBlocking {
        withActorBlocked {
            repeat(3) { DecodingInfo.requestMoreExpandedCandidates() }
        }
        assertEquals(1, pagingRecords("ime.action_enqueued"))
    }

    @Test
    fun newRevisionRetriesRejectedRequestWithoutChangingPage() = runBlocking {
        val previous = stateFlow.value
        DecodingInfo.requestMoreExpandedCandidates()
        checkNotNull(EnginePipeline.readState { Unit })
        assertEquals(previous, stateFlow.value)

        stateFlow.value = previous.copy(candidateRevision = Long.MAX_VALUE)
        withActorBlocked {
            repeat(3) { DecodingInfo.requestMoreExpandedCandidates() }
        }

        assertEquals(previous.candidatePageNo, stateFlow.value.candidatePageNo)
        assertEquals(2, pagingRecords("ime.action_enqueued"))
    }

    @Test
    fun newPageAllowsFurtherRequestsWithTheSameRevision() = runBlocking {
        val previous = stateFlow.value
        DecodingInfo.requestMoreExpandedCandidates()
        checkNotNull(EnginePipeline.readState { Unit })

        stateFlow.value = previous.copy(candidatePageNo = previous.candidatePageNo + 1)
        withActorBlocked {
            repeat(3) { DecodingInfo.requestMoreExpandedCandidates() }
        }

        assertEquals(previous.candidateRevision, stateFlow.value.candidateRevision)
        assertEquals(2, pagingRecords("ime.action_enqueued"))
    }

    @Test
    fun queueRejectionAllowsRetryFromTheSameState() = runBlocking {
        withActorBlocked {
            var accepted = 0
            val maximumQueueAttempts = 1024
            for (attempt in 0 until maximumQueueAttempts) {
                if (!EnginePipeline.send(EngineAction.Maintenance {})) break
                accepted++
            }
            assertTrue(accepted in 1 until maximumQueueAttempts)
            DecodingInfo.requestMoreExpandedCandidates()
            assertEquals(1, pagingRecords("ime.action_rejected"))
        }

        DecodingInfo.requestMoreExpandedCandidates()
        checkNotNull(EnginePipeline.readState { Unit })
        assertEquals(1, pagingRecords("ime.action_enqueued"))
    }

    @Test
    fun externalPredictionAndLastPageDoNotRequestNativePages() = runBlocking {
        val state = stateFlow.value
        stateFlow.value = state.copy(hasNextCandidatePage = false)
        DecodingInfo.requestMoreExpandedCandidates()
        stateFlow.value = state.copy(isAssociate = true)
        DecodingInfo.requestMoreExpandedCandidates()
        stateFlow.value = state
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "external")))
        DecodingInfo.requestMoreExpandedCandidates()
        checkNotNull(EnginePipeline.readState { Unit })

        assertEquals(0, pagingRecords("ime.action_enqueued"))
    }

    private fun pagingRecords(event: String): Int = records.count {
        it.contains("\"event\":\"$event\"") &&
            it.contains("\"action\":\"load_more_expanded_candidates\"")
    }

    private suspend fun withActorBlocked(block: () -> Unit) = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val blocker = async(Dispatchers.Default) {
            EnginePipeline.readState {
                started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            withTimeout(1_000) { started.await() }
            block()
        } finally {
            release.countDown()
            blocker.await()
            withTimeout(1_000) {
                while (EnginePipeline.readState { true } != true) kotlinx.coroutines.yield()
            }
        }
    }
}
