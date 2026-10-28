package org.bitfennec.lime.inputmethod.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bitfennec.lime.inputmethod.ImeDispatchers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ASR task flow controller and arbitration dispatcher.
 * Core mechanisms:
 * 1. Queue depth = 1: incoming PREVIEW coalesces/replaces pending task;
 * 2. FINAL has preemptive priority: cancels ongoing PREVIEW to minimize final recognition latency;
 * 3. Bound to dedicated single-thread ImeDispatchers.asrDispatcher.
 */
class AsrWorker(
    private val onInterimResult: (text: String) -> Unit,
    private val onFinalResult: (text: String) -> Unit,
    private val transcribe: (FloatArray) -> String = { VoiceRecognitionEngine.transcribeSync(it) },
) {
    companion object {
        private const val MAX_FALLBACK_SAMPLE_GAP = 600 * (AudioRecordHelper.SAMPLE_RATE / 1000)
    }

    private val workerScope = CoroutineScope(SupervisorJob() + ImeDispatchers.asrDispatcher)

    private val previewCounter = AtomicLong(0)
    private val epoch = AtomicLong(0)
    private val isTranscribing = AtomicBoolean(false)
    private val stateLock = Any()

    private var activeFinalTasks = 0
    private var utteranceSequence = 0L
    private var lastPreviewUtteranceSeq = -1L
    private var lastPreviewRawText: String = ""
    private var lastPreviewSampleCount = 0

    @Volatile
    private var pendingPreviewSamples: FloatArray? = null

    private data class FinalTaskSnapshot(
        val capturedEpoch: Long,
        val fallbackRawPreview: String,
        val prevSampleCount: Int,
    )

    private fun isCurrentEpoch(captured: Long): Boolean = captured == epoch.get()

    private fun hasPendingFinal(): Boolean = synchronized(stateLock) { activeFinalTasks > 0 }

    private fun releaseFinalSlot(capturedEpoch: Long) {
        synchronized(stateLock) {
            if (capturedEpoch == epoch.get() && activeFinalTasks > 0) {
                activeFinalTasks--
            }
        }
    }

    private fun captureFinalSnapshot(reserveFinalSlot: Boolean): FinalTaskSnapshot {
        synchronized(stateLock) {
            val capturedEpoch = epoch.get()
            val curSeq = utteranceSequence
            val fallbackRaw = if (lastPreviewUtteranceSeq == curSeq) lastPreviewRawText else ""
            val prevSamples = if (lastPreviewUtteranceSeq == curSeq) lastPreviewSampleCount else 0
            utteranceSequence = curSeq + 1
            lastPreviewUtteranceSeq = -1L
            lastPreviewRawText = ""
            lastPreviewSampleCount = 0
            if (reserveFinalSlot) {
                activeFinalTasks++
                previewCounter.incrementAndGet()
                pendingPreviewSamples = null
            }
            return FinalTaskSnapshot(
                capturedEpoch = capturedEpoch,
                fallbackRawPreview = fallbackRaw,
                prevSampleCount = prevSamples,
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
    ) {
        val sampleCount = samples.size
        if (!isCurrentEpoch(snapshot.capturedEpoch)) {
            android.util.Log.d(
                "AsrWorker",
                """{"event":"voice.asr_result","kind":"$kind","result":"dropped","drop_reason":"epoch_mismatch_before_decode","sample_count":$sampleCount}"""
            )
            return
        }
        val rawText = transcribe(samples)
        if (!isCurrentEpoch(snapshot.capturedEpoch)) {
            android.util.Log.d(
                "AsrWorker",
                """{"event":"voice.asr_result","kind":"$kind","result":"dropped","drop_reason":"epoch_mismatch_after_decode","sample_count":$sampleCount,"raw_length":${rawText.length}}"""
            )
            return
        }
        val processedText = VoicePostProcessor.process(rawText, isFinal = true)
        if (!isCurrentEpoch(snapshot.capturedEpoch)) {
            android.util.Log.d(
                "AsrWorker",
                """{"event":"voice.asr_result","kind":"$kind","result":"dropped","drop_reason":"epoch_mismatch_after_postprocess","sample_count":$sampleCount,"raw_length":${rawText.length},"processed_length":${processedText.length}}"""
            )
            return
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
    }

    /** Submits interim streaming preview task (coalescing). */
    fun submitPreview(samples: FloatArray) {
        if (hasPendingFinal()) return
        if (samples.size < 400 * (AudioRecordHelper.SAMPLE_RATE / 1000)) return // Decode only if >= 400ms audio

        val gen = previewCounter.incrementAndGet()
        pendingPreviewSamples = samples
        android.util.Log.d("AsrWorker", """{"event":"voice.asr_submit_preview","sample_count":${samples.size},"gen":$gen}""")

        if (isTranscribing.compareAndSet(false, true)) {
            dispatchNextTask()
        }
    }

    /** Submits final recognition task (invalidates pending previews). Dropped if [reset] runs before callback. */
    fun submitFinal(samples: FloatArray) {
        if (samples.isEmpty()) return

        val snapshot = captureFinalSnapshot(reserveFinalSlot = true)
        android.util.Log.i(
            "AsrWorker",
            """{"event":"voice.asr_submit_final","sample_count":${samples.size},"epoch":${snapshot.capturedEpoch},"last_preview_raw_length":${snapshot.fallbackRawPreview.length},"last_preview_samples":${snapshot.prevSampleCount}}"""
        )

        workerScope.launch {
            try {
                decodeAndDeliverFinal(kind = "final", samples = samples, snapshot = snapshot)
            } finally {
                releaseFinalSlot(snapshot.capturedEpoch)
                if (isCurrentEpoch(snapshot.capturedEpoch)) {
                    isTranscribing.set(false)
                }
            }
        }
    }

    /** Concludes session: processes trailing utterance and invokes onFinished callback. */
    fun finishSession(remainingSamples: FloatArray?, onFinished: () -> Unit) {
        val remainingCount = remainingSamples?.size ?: 0
        val hasRemaining = remainingCount > 0
        val snapshot = captureFinalSnapshot(reserveFinalSlot = hasRemaining)
        android.util.Log.i(
            "AsrWorker",
            """{"event":"voice.asr_finish_session","remaining_samples":$remainingCount,"epoch":${snapshot.capturedEpoch},"last_preview_raw_length":${snapshot.fallbackRawPreview.length},"last_preview_samples":${snapshot.prevSampleCount}}"""
        )
        workerScope.launch {
            try {
                if (!isCurrentEpoch(snapshot.capturedEpoch)) {
                    android.util.Log.d(
                        "AsrWorker",
                        """{"event":"voice.asr_result","kind":"finish","result":"dropped","drop_reason":"epoch_mismatch_before_decode","sample_count":$remainingCount}"""
                    )
                    return@launch
                }
                if (remainingSamples != null && remainingSamples.isNotEmpty()) {
                    decodeAndDeliverFinal(kind = "finish", samples = remainingSamples, snapshot = snapshot)
                }
            } finally {
                if (hasRemaining) {
                    releaseFinalSlot(snapshot.capturedEpoch)
                }
                if (isCurrentEpoch(snapshot.capturedEpoch)) {
                    isTranscribing.set(false)
                }
                onFinished()
            }
        }
    }

    private fun dispatchNextTask() {
        val capturedEpoch = epoch.get()
        workerScope.launch {
            try {
                while (true) {
                    if (!isCurrentEpoch(capturedEpoch) || hasPendingFinal()) {
                        pendingPreviewSamples = null
                        break
                    }

                    val samplesToDecode: FloatArray
                    val currentGen: Long
                    val capturedUtteranceSeq: Long
                    synchronized(stateLock) {
                        if (!isCurrentEpoch(capturedEpoch) || activeFinalTasks > 0) {
                            pendingPreviewSamples = null
                            break
                        }
                        samplesToDecode = pendingPreviewSamples ?: break
                        pendingPreviewSamples = null
                        currentGen = previewCounter.get()
                        capturedUtteranceSeq = utteranceSequence
                    }

                    val rawText = transcribe(samplesToDecode)

                    if (!isCurrentEpoch(capturedEpoch) || hasPendingFinal() || currentGen != previewCounter.get()) {
                        continue
                    }

                    val previewText = VoicePostProcessor.process(rawText, isFinal = false)
                    if (previewText.isNotEmpty()) {
                        var shouldDeliver = false
                        synchronized(stateLock) {
                            if (isCurrentEpoch(capturedEpoch) &&
                                activeFinalTasks == 0 &&
                                currentGen == previewCounter.get() &&
                                capturedUtteranceSeq == utteranceSequence
                            ) {
                                lastPreviewUtteranceSeq = capturedUtteranceSeq
                                lastPreviewRawText = rawText
                                lastPreviewSampleCount = samplesToDecode.size
                                shouldDeliver = true
                            }
                        }
                        if (shouldDeliver) {
                            onInterimResult(previewText)
                        }
                    }
                }
            } finally {
                if (isCurrentEpoch(capturedEpoch)) {
                    isTranscribing.set(false)
                    if (pendingPreviewSamples != null && !hasPendingFinal()) {
                        if (isTranscribing.compareAndSet(false, true)) {
                            dispatchNextTask()
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
            pendingPreviewSamples = null
        }
    }

    /** Key press arbitration: suppresses pending previews during typing. */
    fun onUserKeyActivity() {
        synchronized(stateLock) {
            previewCounter.incrementAndGet()
            pendingPreviewSamples = null
            lastPreviewUtteranceSeq = -1L
            lastPreviewRawText = ""
            lastPreviewSampleCount = 0
        }
    }

    fun reset() {
        synchronized(stateLock) {
            epoch.incrementAndGet()
            previewCounter.incrementAndGet()
            pendingPreviewSamples = null
            utteranceSequence++
            lastPreviewUtteranceSeq = -1L
            lastPreviewRawText = ""
            lastPreviewSampleCount = 0
            activeFinalTasks = 0
            isTranscribing.set(false)
        }
    }
}
