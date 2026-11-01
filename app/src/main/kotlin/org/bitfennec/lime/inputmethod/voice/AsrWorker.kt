package org.bitfennec.lime.inputmethod.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bitfennec.lime.inputmethod.ImeDispatchers
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

class AsrWorker(
    private val onInterimResult: (text: String) -> Unit,
    private val onFinalResult: (text: String) -> Unit,
    private val transcribe: ((FloatArray) -> String)? = null,
    private val clockNanos: () -> Long = System::nanoTime,
    private val diagnosticTraceId: Long = 0L,
) {
    companion object {
        private const val MAX_FALLBACK_SAMPLE_GAP = 600 * (AudioRecordHelper.SAMPLE_RATE / 1000)
        private const val MIN_DECODE_SAMPLES = 400 * (AudioRecordHelper.SAMPLE_RATE / 1000)
    }

    private val workerScope = CoroutineScope(SupervisorJob() + ImeDispatchers.asrDispatcher)

    private val previewCounter = AtomicLong(0)
    private val diagnosticTaskCounter = AtomicLong(0)
    private val epoch = AtomicLong(0)
    private val stateLock = Any()

    private var activeFinalTasks = 0
    private var previewInFlight = false
    private var sessionFinishing = false
    private var previewFinishedAtNanos: Long? = null
    private var previewCooldownNanos = 0L
    private var lastPreviewInputSamples: FloatArray? = null
    private var utteranceSequence = 0L
    private var lastPreviewUtteranceSeq = -1L
    private var lastPreviewRawText: String = ""
    private var lastPreviewSampleCount = 0

    private data class FinalTaskSnapshot(
        val capturedEpoch: Long,
        val fallbackRawPreview: String,
        val prevSampleCount: Int,
        val taskId: Long,
        val submittedAtNanos: Long,
    )

    private data class PreviewTaskSnapshot(
        val capturedEpoch: Long,
        val generation: Long,
        val utteranceSeq: Long,
        val taskId: Long,
        val submittedAtNanos: Long,
    )

    private fun isCurrentEpoch(captured: Long): Boolean = captured == epoch.get()

    private fun releaseFinalSlot(capturedEpoch: Long) {
        synchronized(stateLock) {
            if (capturedEpoch == epoch.get() && activeFinalTasks > 0) {
                activeFinalTasks--
            }
        }
    }

    private fun captureFinalSnapshot(reserveFinalSlot: Boolean, finishing: Boolean = false): FinalTaskSnapshot {
        synchronized(stateLock) {
            val capturedEpoch = epoch.get()
            val curSeq = utteranceSequence
            val fallbackRaw = if (lastPreviewUtteranceSeq == curSeq) lastPreviewRawText else ""
            val prevSamples = if (lastPreviewUtteranceSeq == curSeq) lastPreviewSampleCount else 0
            utteranceSequence = curSeq + 1
            lastPreviewUtteranceSeq = -1L
            lastPreviewRawText = ""
            lastPreviewSampleCount = 0
            lastPreviewInputSamples = null
            previewFinishedAtNanos = null
            previewCounter.incrementAndGet()
            sessionFinishing = sessionFinishing || finishing
            if (reserveFinalSlot) {
                activeFinalTasks++
            }
            return FinalTaskSnapshot(
                capturedEpoch = capturedEpoch,
                fallbackRawPreview = fallbackRaw,
                prevSampleCount = prevSamples,
                taskId = diagnosticTaskCounter.incrementAndGet(),
                submittedAtNanos = clockNanos(),
            )
        }
    }

    private fun selectDeliveredText(
        processedText: String,
        fallbackRawPreview: String,
        prevSampleCount: Int,
        finalSampleCount: Int,
    ): Pair<String, Boolean> {
        if (processedText.isNotEmpty()) {
            return processedText to false
        }
        val minPreviewSamples = (finalSampleCount - MAX_FALLBACK_SAMPLE_GAP).coerceAtLeast(1)
        val maxPreviewSamples = finalSampleCount + MAX_FALLBACK_SAMPLE_GAP
        val coverageSufficient = fallbackRawPreview.isNotEmpty() &&
            prevSampleCount in minPreviewSamples..maxPreviewSamples
        if (!coverageSufficient) {
            return processedText to false
        }
        val fallbackProcessed = VoicePostProcessor.process(fallbackRawPreview, isFinal = true)
        return if (fallbackProcessed.isNotEmpty()) {
            fallbackProcessed to true
        } else {
            processedText to false
        }
    }

    private fun decodeAndDeliverFinal(
        kind: String,
        samples: FloatArray,
        snapshot: FinalTaskSnapshot,
    ): VoiceLatencyTrace.Status {
        val sampleCount = samples.size
        if (!isCurrentEpoch(snapshot.capturedEpoch)) {
            android.util.Log.d(
                "AsrWorker",
                """{"event":"voice.asr_result","kind":"$kind","result":"dropped","drop_reason":"epoch_mismatch_before_decode","sample_count":$sampleCount}"""
            )
            return VoiceLatencyTrace.Status.DROPPED
        }
        val decodeSamples = if (sampleCount < MIN_DECODE_SAMPLES) samples.copyOf(MIN_DECODE_SAMPLES) else samples
        val rawText = transcribeSamples(decodeSamples, snapshot.taskId)
        if (!isCurrentEpoch(snapshot.capturedEpoch)) {
            android.util.Log.d(
                "AsrWorker",
                """{"event":"voice.asr_result","kind":"$kind","result":"dropped","drop_reason":"epoch_mismatch_after_decode","sample_count":$sampleCount,"raw_length":${rawText.length}}"""
            )
            return VoiceLatencyTrace.Status.DROPPED
        }
        val processedText = VoicePostProcessor.process(rawText, isFinal = true)
        if (!isCurrentEpoch(snapshot.capturedEpoch)) {
            android.util.Log.d(
                "AsrWorker",
                """{"event":"voice.asr_result","kind":"$kind","result":"dropped","drop_reason":"epoch_mismatch_after_postprocess","sample_count":$sampleCount,"raw_length":${rawText.length},"processed_length":${processedText.length}}"""
            )
            return VoiceLatencyTrace.Status.DROPPED
        }
        val (deliveredText, usedPreviewFallback) = selectDeliveredText(
            processedText = processedText,
            fallbackRawPreview = snapshot.fallbackRawPreview,
            prevSampleCount = snapshot.prevSampleCount,
            finalSampleCount = sampleCount,
        )
        android.util.Log.i(
            "AsrWorker",
            """{"event":"voice.asr_result","kind":"$kind","result":"delivered","sample_count":$sampleCount,"raw_length":${rawText.length},"processed_length":${processedText.length},"delivered_length":${deliveredText.length},"used_preview_fallback":$usedPreviewFallback,"last_preview_raw_length":${snapshot.fallbackRawPreview.length},"last_preview_samples":${snapshot.prevSampleCount}}"""
        )
        onFinalResult(deliveredText)
        return if (deliveredText.isEmpty()) VoiceLatencyTrace.Status.EMPTY else VoiceLatencyTrace.Status.SUCCESS
    }

    private fun transcribeSamples(samples: FloatArray, taskId: Long): String =
        transcribe?.invoke(samples) ?: VoiceRecognitionEngine.transcribeSync(
            samples, diagnosticTraceId = diagnosticTraceId, diagnosticTaskId = taskId,
        )

    private fun traceFinalTask(
        kind: VoiceLatencyTrace.Kind,
        sampleCount: Int,
        snapshot: FinalTaskSnapshot,
        action: () -> VoiceLatencyTrace.Status,
    ) {
        val startedAt = clockNanos()
        VoiceLatencyTrace.record(
            VoiceLatencyTrace.Event.TASK_STARTED, diagnosticTraceId, snapshot.taskId,
            kind = kind, sampleCount = sampleCount,
            queueMs = TimeUnit.NANOSECONDS.toMillis(startedAt - snapshot.submittedAtNanos),
        )
        var status = VoiceLatencyTrace.Status.FAILED
        try {
            status = action()
        } finally {
            VoiceLatencyTrace.record(
                VoiceLatencyTrace.Event.TASK_COMPLETED, diagnosticTraceId, snapshot.taskId,
                kind = kind, status = status, sampleCount = sampleCount,
                durationMs = TimeUnit.NANOSECONDS.toMillis(clockNanos() - startedAt),
            )
        }
    }

    fun canSubmitPreview(): Boolean = synchronized(stateLock) {
        val finishedAt = previewFinishedAtNanos
        !sessionFinishing && activeFinalTasks == 0 && !previewInFlight &&
            (finishedAt == null || clockNanos() - finishedAt >= previewCooldownNanos)
    }

    fun submitPreview(samples: FloatArray) {
        if (samples.size < MIN_DECODE_SAMPLES) return
        val snapshot = synchronized(stateLock) {
            if (!canSubmitPreview() || samples.contentEquals(lastPreviewInputSamples)) return
            previewInFlight = true
            lastPreviewInputSamples = samples
            PreviewTaskSnapshot(
                capturedEpoch = epoch.get(),
                generation = previewCounter.incrementAndGet(),
                utteranceSeq = utteranceSequence,
                taskId = diagnosticTaskCounter.incrementAndGet(),
                submittedAtNanos = clockNanos(),
            )
        }
        android.util.Log.d("AsrWorker", """{"event":"voice.asr_submit_preview","sample_count":${samples.size},"gen":${snapshot.generation}}""")
        VoiceLatencyTrace.record(
            VoiceLatencyTrace.Event.TASK_QUEUED, diagnosticTraceId, snapshot.taskId,
            kind = VoiceLatencyTrace.Kind.PREVIEW, sampleCount = samples.size,
        )
        dispatchPreview(samples, snapshot)
    }

    fun submitFinal(samples: FloatArray) {
        if (samples.isEmpty()) return

        val snapshot = captureFinalSnapshot(reserveFinalSlot = true)
        VoiceLatencyTrace.record(
            VoiceLatencyTrace.Event.TASK_QUEUED, diagnosticTraceId, snapshot.taskId,
            kind = VoiceLatencyTrace.Kind.FINAL, sampleCount = samples.size,
        )
        android.util.Log.i(
            "AsrWorker",
            """{"event":"voice.asr_submit_final","sample_count":${samples.size},"epoch":${snapshot.capturedEpoch},"last_preview_raw_length":${snapshot.fallbackRawPreview.length},"last_preview_samples":${snapshot.prevSampleCount}}"""
        )

        workerScope.launch {
            try {
                traceFinalTask(VoiceLatencyTrace.Kind.FINAL, samples.size, snapshot) {
                    decodeAndDeliverFinal(kind = "final", samples = samples, snapshot = snapshot)
                }
            } finally {
                releaseFinalSlot(snapshot.capturedEpoch)
            }
        }
    }

    fun finishSession(remainingSamples: FloatArray?, onFinished: () -> Unit) {
        val remainingCount = remainingSamples?.size ?: 0
        val hasRemaining = remainingCount > 0
        val snapshot = captureFinalSnapshot(reserveFinalSlot = hasRemaining, finishing = true)
        VoiceLatencyTrace.record(
            VoiceLatencyTrace.Event.TASK_QUEUED, diagnosticTraceId, snapshot.taskId,
            kind = VoiceLatencyTrace.Kind.FINISH, sampleCount = remainingCount,
        )
        android.util.Log.i(
            "AsrWorker",
            """{"event":"voice.asr_finish_session","remaining_samples":$remainingCount,"epoch":${snapshot.capturedEpoch},"last_preview_raw_length":${snapshot.fallbackRawPreview.length},"last_preview_samples":${snapshot.prevSampleCount}}"""
        )
        workerScope.launch {
            try {
                traceFinalTask(VoiceLatencyTrace.Kind.FINISH, remainingCount, snapshot) {
                    if (!isCurrentEpoch(snapshot.capturedEpoch)) {
                        android.util.Log.d(
                            "AsrWorker",
                            """{"event":"voice.asr_result","kind":"finish","result":"dropped","drop_reason":"epoch_mismatch_before_decode","sample_count":$remainingCount}"""
                        )
                        VoiceLatencyTrace.Status.DROPPED
                    } else if (remainingSamples != null && remainingSamples.isNotEmpty()) {
                        decodeAndDeliverFinal(kind = "finish", samples = remainingSamples, snapshot = snapshot)
                    } else {
                        VoiceLatencyTrace.Status.SUCCESS
                    }
                }
            } finally {
                if (hasRemaining) {
                    releaseFinalSlot(snapshot.capturedEpoch)
                }
                onFinished()
            }
        }
    }

    private fun isCurrentPreview(snapshot: PreviewTaskSnapshot): Boolean =
        isCurrentEpoch(snapshot.capturedEpoch) &&
            !sessionFinishing && activeFinalTasks == 0 &&
            snapshot.generation == previewCounter.get() &&
            snapshot.utteranceSeq == utteranceSequence

    private fun dispatchPreview(samples: FloatArray, snapshot: PreviewTaskSnapshot) {
        workerScope.launch {
            var startedAtNanos: Long? = null
            val taskStartedAt = clockNanos()
            var status = VoiceLatencyTrace.Status.DROPPED
            VoiceLatencyTrace.record(
                VoiceLatencyTrace.Event.TASK_STARTED, diagnosticTraceId, snapshot.taskId,
                kind = VoiceLatencyTrace.Kind.PREVIEW, sampleCount = samples.size,
                queueMs = TimeUnit.NANOSECONDS.toMillis(taskStartedAt - snapshot.submittedAtNanos),
            )
            try {
                if (!synchronized(stateLock) { isCurrentPreview(snapshot) }) return@launch
                startedAtNanos = clockNanos()
                status = VoiceLatencyTrace.Status.FAILED
                val rawText = transcribeSamples(samples, snapshot.taskId)
                status = VoiceLatencyTrace.Status.DROPPED
                if (!synchronized(stateLock) { isCurrentPreview(snapshot) }) return@launch

                val previewText = VoicePostProcessor.process(rawText, isFinal = false)
                if (previewText.isNotEmpty()) {
                    val shouldDeliver = synchronized(stateLock) {
                        if (isCurrentPreview(snapshot)) {
                            lastPreviewUtteranceSeq = snapshot.utteranceSeq
                            lastPreviewRawText = rawText
                            lastPreviewSampleCount = samples.size
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldDeliver) {
                        onInterimResult(previewText)
                        status = VoiceLatencyTrace.Status.SUCCESS
                    }
                } else {
                    status = VoiceLatencyTrace.Status.EMPTY
                }
            } finally {
                VoiceLatencyTrace.record(
                    VoiceLatencyTrace.Event.TASK_COMPLETED, diagnosticTraceId, snapshot.taskId,
                    kind = VoiceLatencyTrace.Kind.PREVIEW, status = status, sampleCount = samples.size,
                    durationMs = TimeUnit.NANOSECONDS.toMillis(clockNanos() - taskStartedAt),
                )
                synchronized(stateLock) {
                    if (isCurrentEpoch(snapshot.capturedEpoch)) {
                        previewInFlight = false
                        if (isCurrentPreview(snapshot)) {
                            startedAtNanos?.let { startedAt ->
                                val finishedAt = clockNanos()
                                previewFinishedAtNanos = finishedAt
                                previewCooldownNanos = finishedAt - startedAt
                            }
                        }
                    }
                }
            }
        }
    }

    fun onSpeechStart() {
        synchronized(stateLock) {
            utteranceSequence++
            lastPreviewUtteranceSeq = -1L
            lastPreviewRawText = ""
            lastPreviewSampleCount = 0
            previewCounter.incrementAndGet()
            lastPreviewInputSamples = null
            previewFinishedAtNanos = null
        }
    }

    fun onUserKeyActivity() {
        synchronized(stateLock) {
            previewCounter.incrementAndGet()
            lastPreviewInputSamples = null
            previewFinishedAtNanos = null
            lastPreviewUtteranceSeq = -1L
            lastPreviewRawText = ""
            lastPreviewSampleCount = 0
        }
    }

    fun reset() {
        synchronized(stateLock) {
            epoch.incrementAndGet()
            previewCounter.incrementAndGet()
            lastPreviewInputSamples = null
            previewFinishedAtNanos = null
            utteranceSequence++
            lastPreviewUtteranceSeq = -1L
            lastPreviewRawText = ""
            lastPreviewSampleCount = 0
            activeFinalTasks = 0
            previewInFlight = false
            sessionFinishing = false
        }
    }
}
