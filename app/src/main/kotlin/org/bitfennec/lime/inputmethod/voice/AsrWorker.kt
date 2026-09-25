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
    private val workerScope = CoroutineScope(SupervisorJob() + ImeDispatchers.asrDispatcher)

    private val previewCounter = AtomicLong(0)
    private val epoch = AtomicLong(0)
    private val hasPendingFinal = AtomicBoolean(false)
    private val isTranscribing = AtomicBoolean(false)

    @Volatile
    private var pendingPreviewSamples: FloatArray? = null

    private fun isCurrentEpoch(captured: Long): Boolean = captured == epoch.get()

    /** Submits interim streaming preview task (coalescing). */
    fun submitPreview(samples: FloatArray) {
        if (hasPendingFinal.get()) return
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

        // 1. Flag immediately to invalidate any ongoing or queued PREVIEW
        val capturedEpoch = epoch.get()
        hasPendingFinal.set(true)
        previewCounter.incrementAndGet()
        pendingPreviewSamples = null
        android.util.Log.i("AsrWorker", """{"event":"voice.asr_submit_final","sample_count":${samples.size}}""")

        workerScope.launch {
            try {
                if (!isCurrentEpoch(capturedEpoch)) return@launch
                val rawText = transcribe(samples)
                if (!isCurrentEpoch(capturedEpoch)) return@launch
                val finalText = VoicePostProcessor.process(rawText, isFinal = true)
                if (!isCurrentEpoch(capturedEpoch)) return@launch
                onFinalResult(finalText)
            } finally {
                if (isCurrentEpoch(capturedEpoch)) {
                    hasPendingFinal.set(false)
                    isTranscribing.set(false)
                }
            }
        }
    }

    /** Concludes session: processes trailing utterance and invokes onFinished callback. */
    fun finishSession(remainingSamples: FloatArray?, onFinished: () -> Unit) {
        val remainingCount = remainingSamples?.size ?: 0
        val capturedEpoch = epoch.get()
        android.util.Log.i("AsrWorker", """{"event":"voice.asr_finish_session","remaining_samples":$remainingCount}""")
        workerScope.launch {
            try {
                if (!isCurrentEpoch(capturedEpoch)) return@launch
                if (remainingSamples != null && remainingSamples.isNotEmpty()) {
                    hasPendingFinal.set(true)
                    previewCounter.incrementAndGet()
                    pendingPreviewSamples = null

                    val rawText = transcribe(remainingSamples)
                    if (!isCurrentEpoch(capturedEpoch)) return@launch
                    val finalText = VoicePostProcessor.process(rawText, isFinal = true)
                    if (!isCurrentEpoch(capturedEpoch)) return@launch
                    onFinalResult(finalText)
                }
            } finally {
                if (isCurrentEpoch(capturedEpoch)) {
                    hasPendingFinal.set(false)
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
                    if (!isCurrentEpoch(capturedEpoch) || hasPendingFinal.get()) {
                        pendingPreviewSamples = null
                        break
                    }

                    val samplesToDecode = pendingPreviewSamples ?: break
                    pendingPreviewSamples = null
                    val currentGen = previewCounter.get()

                    val rawText = transcribe(samplesToDecode)

                    // Discard if a FINAL task arrived, session was invalidated, or newer PREVIEW was queued
                    if (!isCurrentEpoch(capturedEpoch) || hasPendingFinal.get() || currentGen != previewCounter.get()) {
                        continue
                    }

                    val previewText = VoicePostProcessor.process(rawText, isFinal = false)
                    if (previewText.isNotEmpty() && isCurrentEpoch(capturedEpoch) &&
                        !hasPendingFinal.get() && currentGen == previewCounter.get()
                    ) {
                        onInterimResult(previewText)
                    }
                }
            } finally {
                if (isCurrentEpoch(capturedEpoch)) {
                    isTranscribing.set(false)
                    if (pendingPreviewSamples != null && !hasPendingFinal.get()) {
                        if (isTranscribing.compareAndSet(false, true)) {
                            dispatchNextTask()
                        }
                    }
                }
            }
        }
    }

    /** Key press arbitration: suppresses pending previews during typing. */
    fun onUserKeyActivity() {
        previewCounter.incrementAndGet()
        pendingPreviewSamples = null
    }

    fun reset() {
        epoch.incrementAndGet()
        previewCounter.incrementAndGet()
        pendingPreviewSamples = null
        hasPendingFinal.set(false)
        isTranscribing.set(false)
    }
}
