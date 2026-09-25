package org.bitfennec.lime.inputmethod

import android.util.Log
import org.bitfennec.lime.BuildConfig

/**
 * Debug-only, metadata-only trace for the IME input pipeline.
 *
 * Enable on a debug build with: adb shell setprop log.tag.LimeInputTrace DEBUG
 */
internal object InputPipelineTrace {
    private const val TAG = "LimeInputTrace"

    @Volatile
    private var testSink: ((String) -> Unit)? = null

    fun mark(): Long = if (isEnabled()) System.nanoTime() else 0L

    fun actionEnqueued(
        sessionId: Long,
        traceSequence: Long,
        generation: Long,
        action: String,
        pendingInputActions: Int,
        enqueueStartedAtNanos: Long,
    ) {
        emit(
            event = "ime.action_enqueued",
            sessionId = sessionId,
            traceSequence = traceSequence,
            generation = generation,
            action = action,
            pendingInputActions = pendingInputActions,
            durationMs = elapsedMs(enqueueStartedAtNanos),
        )
    }

    fun actionDequeued(
        sessionId: Long,
        traceSequence: Long,
        generation: Long,
        action: String,
        pendingInputActions: Int,
        enqueueStartedAtNanos: Long,
    ) {
        emit(
            event = "ime.action_dequeued",
            sessionId = sessionId,
            traceSequence = traceSequence,
            generation = generation,
            action = action,
            pendingInputActions = pendingInputActions,
            durationMs = elapsedMs(enqueueStartedAtNanos),
        )
    }

    fun actionDropped(
        sessionId: Long,
        traceSequence: Long,
        generation: Long,
        action: String,
        pendingInputActions: Int,
        enqueueStartedAtNanos: Long,
    ) {
        emit(
            event = "ime.action_dropped",
            sessionId = sessionId,
            traceSequence = traceSequence,
            generation = generation,
            action = action,
            pendingInputActions = pendingInputActions,
            durationMs = elapsedMs(enqueueStartedAtNanos),
        )
    }

    fun actionRejected(
        sessionId: Long,
        traceSequence: Long,
        generation: Long,
        action: String,
        pendingInputActions: Int,
        enqueueStartedAtNanos: Long,
    ) {
        emit(
            event = "ime.action_rejected",
            sessionId = sessionId,
            traceSequence = traceSequence,
            generation = generation,
            action = action,
            pendingInputActions = pendingInputActions,
            durationMs = elapsedMs(enqueueStartedAtNanos),
        )
    }

    fun actionDeferred(
        sessionId: Long,
        traceSequence: Long,
        generation: Long,
        action: String,
        pendingInputActions: Int,
        enqueueStartedAtNanos: Long,
    ) {
        emit(
            event = "ime.action_deferred",
            sessionId = sessionId,
            traceSequence = traceSequence,
            generation = generation,
            action = action,
            pendingInputActions = pendingInputActions,
            durationMs = elapsedMs(enqueueStartedAtNanos),
        )
    }

    fun rimeProcessed(
        sessionId: Long,
        traceSequence: Long,
        generation: Long,
        action: String,
        startedAtNanos: Long,
    ) {
        emit(
            event = "ime.rime_processed",
            sessionId = sessionId,
            traceSequence = traceSequence,
            generation = generation,
            action = action,
            durationMs = elapsedMs(startedAtNanos),
        )
    }

    fun snapshotPublished(
        sessionId: Long,
        traceSequence: Long,
        generation: Long,
        candidateCount: Int,
        preeditLength: Int,
    ) {
        emit(
            event = "ime.snapshot_published",
            sessionId = sessionId,
            traceSequence = traceSequence,
            generation = generation,
            candidateCount = candidateCount,
            preeditLength = preeditLength,
        )
    }

    fun candidateRendered(
        sessionId: Long,
        traceSequence: Long,
        candidateCount: Int,
        startedAtNanos: Long,
    ) {
        emit(
            event = "ime.candidates_rendered",
            sessionId = sessionId,
            traceSequence = traceSequence,
            candidateCount = candidateCount,
            durationMs = elapsedMs(startedAtNanos),
        )
    }

    fun inputConnectionWrite(
        sessionId: Long,
        traceSequence: Long,
        operation: String,
        textLength: Int,
        startedAtNanos: Long,
    ) {
        emit(
            event = "ime.input_connection_write",
            sessionId = sessionId,
            traceSequence = traceSequence,
            action = operation,
            textLength = textLength,
            durationMs = elapsedMs(startedAtNanos),
        )
    }

    fun pointerEvent(
        action: String,
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
        snapshotId: Long,
    ) {
        emit(
            event = "ime.pointer_event",
            sessionId = 0L,
            traceSequence = 0L,
            action = action,
            pointerId = pointerId,
            x = x,
            y = y,
            eventTimeMillis = eventTimeMillis,
            snapshotId = snapshotId,
        )
    }

    internal fun setTestSink(sink: ((String) -> Unit)?) {
        testSink = sink
    }

    private fun isEnabled(): Boolean = testSink != null || (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG))

    private fun elapsedMs(startedAtNanos: Long): Long? =
        if (startedAtNanos == 0L) null else (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND

    private fun emit(
        event: String,
        sessionId: Long,
        traceSequence: Long,
        generation: Long = 0L,
        action: String? = null,
        pendingInputActions: Int? = null,
        candidateCount: Int? = null,
        preeditLength: Int? = null,
        textLength: Int? = null,
        durationMs: Long? = null,
        pointerId: Int? = null,
        x: Float? = null,
        y: Float? = null,
        eventTimeMillis: Long? = null,
        snapshotId: Long? = null,
    ) {
        val sink = testSink
        if (sink == null && !isEnabled()) return
        val payload = buildString {
            append('{')
            append("\"timestamp_ms\":").append(System.currentTimeMillis())
            append(",\"event\":\"").append(event).append('"')
            append(",\"session_id\":").append(sessionId)
            append(",\"trace_sequence\":").append(traceSequence)
            append(",\"generation\":").append(generation)
            action?.let { append(",\"action\":\"").append(it).append('"') }
            pendingInputActions?.let { append(",\"pending_input_actions\":").append(it) }
            candidateCount?.let { append(",\"candidate_count\":").append(it) }
            preeditLength?.let { append(",\"preedit_length\":").append(it) }
            textLength?.let { append(",\"text_length\":").append(it) }
            durationMs?.let { append(",\"duration_ms\":").append(it) }
            pointerId?.let { append(",\"pointer_id\":").append(it) }
            x?.let { append(",\"x\":").append(it) }
            y?.let { append(",\"y\":").append(it) }
            eventTimeMillis?.let { append(",\"event_time_ms\":").append(it) }
            snapshotId?.let { append(",\"snapshot_id\":").append(it) }
            append('}')
        }
        sink?.invoke(payload) ?: Log.d(TAG, payload)
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
