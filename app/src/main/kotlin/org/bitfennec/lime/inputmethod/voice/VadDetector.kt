package org.bitfennec.lime.inputmethod.voice

import kotlin.math.sqrt

/**
 * Adaptive Voice Activity Detector (VAD).
 * State machine: States are SILENCE and IN_SPEECH; transitions emit speech-start and speech-end callbacks.
 * Key parameters:
 * - Frame size: 512 samples (32ms @ 16kHz)
 * - Trailing silence: 700ms endpoint cutoff
 * - Max utterance: 10.0s forced segmentation
 * - Silence timeout: 12.0s sleep timeout
 */
class VadDetector(
    private val minSilenceDurationMs: Long = 700L,
    private val maxSpeechDurationMs: Long = 10000L,
    private val silenceTimeoutMs: Long = 12000L,
    private val minSpeechDurationMs: Long = 200L,
    private val listener: Listener
) {

    interface Listener {
        fun onSpeechStart()
        fun onSpeechEnd()
        fun onSilenceTimeout()
    }

    enum class State {
        SILENCE,
        IN_SPEECH
    }

    private var state = State.SILENCE
    private var consecutiveSpeechFrames = 0
    private var currentSilenceMs = 0L
    private var currentSpeechMs = 0L
    private var totalIdleSilenceMs = 0L

    // Adaptive noise floor tracking (RMS energy)
    private var noiseFloor = 0.0010f

    private var lastSentenceTailSilenceMs = 0L

    fun getState(): State = state
    fun isInSpeech(): Boolean = state == State.IN_SPEECH
    fun getLastSentenceTailSilenceMs(): Long = lastSentenceTailSilenceMs
    fun getCurrentTailSilenceMs(): Long = if (state == State.IN_SPEECH) currentSilenceMs else 0L
    fun getNoiseFloor(): Float = noiseFloor

    /** Feeds 512 samples (16kHz PCM normalized float array) and advances state machine. */
    fun processFrame(samples: FloatArray) {
        if (samples.isEmpty()) return

        val frameDurationMs = (samples.size * 1000L) / AudioRecordHelper.SAMPLE_RATE

        // 1. Compute frame RMS energy and peak amplitude
        var sumSquares = 0.0
        var maxPeak = 0.0f
        for (sample in samples) {
            val absVal = if (sample < 0f) -sample else sample
            if (absVal > maxPeak) maxPeak = absVal
            sumSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()

        // 2. Dynamic adaptive hysteresis thresholds (~2.2x noiseFloor, clamped [0.0016, 0.010])
        val speechOnThreshold = (noiseFloor * 2.2f).coerceIn(0.0016f, 0.010f)
        val speechOffThreshold = (speechOnThreshold * 0.65f).coerceAtLeast(noiseFloor * 1.3f)

        val isVoiceActive = rms >= speechOnThreshold || (state == State.IN_SPEECH && rms >= speechOffThreshold)

        when (state) {
            State.SILENCE -> {
                if (isVoiceActive) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= 5) { // ~160ms onset to reject transient spikes
                        state = State.IN_SPEECH
                        currentSpeechMs = consecutiveSpeechFrames * frameDurationMs
                        consecutiveSpeechFrames = 0
                        currentSilenceMs = 0L
                        totalIdleSilenceMs = 0L
                        listener.onSpeechStart()
                    }
                } else {
                    consecutiveSpeechFrames = 0
                    // Asymmetric noise floor tracking:
                    // - If quieter than noiseFloor, track down smoothly
                    // - If barely above noiseFloor (<1.3x), track up very slowly (0.005)
                    // - If >=1.3x noiseFloor, FREEZE noiseFloor to prevent swallowing quiet whispers!
                    if (rms < noiseFloor) {
                        noiseFloor = (noiseFloor * 0.95f + rms * 0.05f).coerceIn(0.0005f, 0.020f)
                    } else if (rms < noiseFloor * 1.3f) {
                        noiseFloor = (noiseFloor * 0.995f + rms * 0.005f).coerceIn(0.0005f, 0.020f)
                    }

                    totalIdleSilenceMs += frameDurationMs
                    if (totalIdleSilenceMs >= silenceTimeoutMs) {
                        totalIdleSilenceMs = 0L
                        listener.onSilenceTimeout()
                    }
                }
            }

            State.IN_SPEECH -> {
                currentSpeechMs += frameDurationMs

                if (isVoiceActive) {
                    currentSilenceMs = 0L
                } else {
                    currentSilenceMs += frameDurationMs
                }

                // Segmentation triggers:
                // A. Trailing silence reaches 700ms with valid speech duration
                // B. Max utterance reaches 10.0s forced cutoff
                val tailSilenceReached = currentSilenceMs >= minSilenceDurationMs && currentSpeechMs >= minSpeechDurationMs
                val maxDurationReached = currentSpeechMs >= maxSpeechDurationMs

                if (tailSilenceReached || maxDurationReached) {
                    lastSentenceTailSilenceMs = currentSilenceMs
                    state = State.SILENCE
                    currentSpeechMs = 0L
                    currentSilenceMs = 0L
                    totalIdleSilenceMs = 0L
                    consecutiveSpeechFrames = 0
                    listener.onSpeechEnd()
                }
            }
        }
    }

    /** Forces current utterance to finalize (e.g. on release or complete button). */
    fun forceSpeechEnd(): Boolean {
        if (state == State.IN_SPEECH) {
            lastSentenceTailSilenceMs = currentSilenceMs
            state = State.SILENCE
            currentSpeechMs = 0L
            currentSilenceMs = 0L
            totalIdleSilenceMs = 0L
            consecutiveSpeechFrames = 0
            listener.onSpeechEnd()
            return true
        }
        return false
    }

    fun reset() {
        state = State.SILENCE
        consecutiveSpeechFrames = 0
        currentSilenceMs = 0L
        currentSpeechMs = 0L
        totalIdleSilenceMs = 0L
        lastSentenceTailSilenceMs = 0L
        noiseFloor = 0.0010f
    }
}
