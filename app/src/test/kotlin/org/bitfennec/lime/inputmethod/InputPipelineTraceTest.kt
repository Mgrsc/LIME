package org.bitfennec.lime.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPipelineTraceTest {

    @Test
    fun traceRecordsMetadataWithoutInputContent() {
        val records = mutableListOf<String>()
        InputPipelineTrace.setTestSink(records::add)
        try {
            val startedAtNanos = InputPipelineTrace.mark()
            InputPipelineTrace.actionEnqueued(
                sessionId = 7L,
                traceSequence = 11L,
                generation = 13L,
                action = "normal_key",
                pendingInputActions = 2,
                enqueueStartedAtNanos = startedAtNanos,
            )

            assertEquals(1, records.size)
            val record = records.single()
            assertTrue(record.contains("\"event\":\"ime.action_enqueued\""))
            assertTrue(record.contains("\"session_id\":7"))
            assertTrue(record.contains("\"trace_sequence\":11"))
            assertTrue(record.contains("\"generation\":13"))
            assertTrue(record.contains("\"pending_input_actions\":2"))
            assertFalse(record.contains("nihao"))
            assertFalse(record.contains("candidate_text"))

            InputPipelineTrace.actionDropped(
                sessionId = 7L,
                traceSequence = 11L,
                generation = 13L,
                action = "normal_key",
                pendingInputActions = 1,
                enqueueStartedAtNanos = startedAtNanos,
            )

            assertEquals(2, records.size)
            assertTrue(records.last().contains("\"event\":\"ime.action_dropped\""))

            InputPipelineTrace.actionRejected(
                sessionId = 7L,
                traceSequence = 11L,
                generation = 13L,
                action = "normal_key",
                pendingInputActions = 64,
                enqueueStartedAtNanos = startedAtNanos,
            )

            assertEquals(3, records.size)
            assertTrue(records.last().contains("\"event\":\"ime.action_rejected\""))

            InputPipelineTrace.pointerEvent(
                action = "ACTION_MOVE",
                pointerId = 3,
                x = 12.5f,
                y = 34.5f,
                eventTimeMillis = 100L,
                snapshotId = 9L,
            )

            assertEquals(4, records.size)
            assertTrue(records.last().contains("\"event\":\"ime.pointer_event\""))
            assertTrue(records.last().contains("\"pointer_id\":3"))
            assertTrue(records.last().contains("\"snapshot_id\":9"))
            assertFalse(records.last().contains("candidate_text"))
        } finally {
            InputPipelineTrace.setTestSink(null)
        }
    }
}
