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

    companion object {
        const val INITIAL_NOISE_FLOOR = 0.00080f
        const val MIN_NOISE_FLOOR = 0.00012f
        private const val MAX_NOISE_FLOOR = 0.020f
        private const val DIGITAL_SILENCE_FLOOR = 0.00005f
        private const val MIN_SPEECH_ON_THRESHOLD = 0.00060f
        private const val MAX_SPEECH_ON_THRESHOLD = 0.010f
        private const val SPEECH_ON_NOISE_MULTIPLIER = 2.2f
        private const val SPEECH_OFF_THRESHOLD_RATIO = 0.65f
        private const val SPEECH_OFF_NOISE_MULTIPLIER = 1.45f
        private const val NOISE_FREEZE_MULTIPLIER = 1.3f
        private const val NOISE_DOWN_KEEP_WEIGHT = 0.90f
        private const val NOISE_DOWN_SAMPLE_WEIGHT = 0.10f
        private const val NOISE_UP_KEEP_WEIGHT = 0.985f
        private const val NOISE_UP_SAMPLE_WEIGHT = 0.015f
        private const val SPEECH_ONSET_FRAMES = 3
        private const val SUB_PEAK_ADAPT_RATIO = 0.35f
        private const val SUB_PEAK_MIN_FRAMES = 8
        private const val SUB_PEAK_KEEP_WEIGHT = 0.92f
        private const val SUB_PEAK_SAMPLE_WEIGHT = 0.08f
    }

    private var state = State.SILENCE
    private var consecutiveSpeechFrames = 0
    private var currentSilenceMs = 0L
    private var currentSpeechMs = 0L
    private var totalIdleSilenceMs = 0L
    private var lastFrameVoiceActive = false
    private var utterancePeakRms = 0.0f
    private var subPeakFrames = 0

    private var noiseFloor = INITIAL_NOISE_FLOOR

    private var lastSentenceTailSilenceMs = 0L

    fun getState(): State = state
    fun isInSpeech(): Boolean = state == State.IN_SPEECH
    fun isVoiceActiveFrame(): Boolean = lastFrameVoiceActive
    fun getLastSentenceTailSilenceMs(): Long = lastSentenceTailSilenceMs
    fun getCurrentTailSilenceMs(): Long = if (state == State.IN_SPEECH) currentSilenceMs else 0L
    fun getNoiseFloor(): Float = noiseFloor
    fun resetIdleSilence() { totalIdleSilenceMs = 0L }

    private fun updateNoiseFloorOnQuietFrame(rms: Float) {
        if (rms < DIGITAL_SILENCE_FLOOR) return
        val freezeCeiling = noiseFloor * NOISE_FREEZE_MULTIPLIER
        if (rms < noiseFloor) {
            noiseFloor = (noiseFloor * NOISE_DOWN_KEEP_WEIGHT + rms * NOISE_DOWN_SAMPLE_WEIGHT)
                .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        } else if (rms < freezeCeiling) {
            noiseFloor = (noiseFloor * NOISE_UP_KEEP_WEIGHT + rms * NOISE_UP_SAMPLE_WEIGHT)
                .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        }
    }

    /** Feeds 512 samples (16kHz PCM normalized float array) and advances state machine. */
    fun processFrame(samples: FloatArray) {
        if (samples.isEmpty()) return

        val frameDurationMs = (samples.size * 1000L) / AudioRecordHelper.SAMPLE_RATE

        var sumSquares = 0.0
        var maxPeak = 0.0f
        for (sample in samples) {
            val absVal = if (sample < 0f) -sample else sample
            if (absVal > maxPeak) maxPeak = absVal
            sumSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()

        val speechOnThreshold = (noiseFloor * SPEECH_ON_NOISE_MULTIPLIER)
            .coerceIn(MIN_SPEECH_ON_THRESHOLD, MAX_SPEECH_ON_THRESHOLD)
        val speechOffThreshold = (speechOnThreshold * SPEECH_OFF_THRESHOLD_RATIO)
            .coerceAtLeast(noiseFloor * SPEECH_OFF_NOISE_MULTIPLIER)

        val isVoiceActive = rms >= speechOnThreshold || (state == State.IN_SPEECH && rms >= speechOffThreshold)
        lastFrameVoiceActive = isVoiceActive

        when (state) {
            State.SILENCE -> {
                if (isVoiceActive) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= SPEECH_ONSET_FRAMES) {
                        state = State.IN_SPEECH
                        currentSpeechMs = consecutiveSpeechFrames * frameDurationMs
                        consecutiveSpeechFrames = 0
                        currentSilenceMs = 0L
                        totalIdleSilenceMs = 0L
                        listener.onSpeechStart()
                    }
                } else {
                    consecutiveSpeechFrames = 0
                    updateNoiseFloorOnQuietFrame(rms)

                    totalIdleSilenceMs += frameDurationMs
                    if (totalIdleSilenceMs >= silenceTimeoutMs) {
                        totalIdleSilenceMs = 0L
                        listener.onSilenceTimeout()
                    }
                }
            }

            State.IN_SPEECH -> {
                currentSpeechMs += frameDurationMs

                if (rms > utterancePeakRms) {
                    utterancePeakRms = rms
                    subPeakFrames = 0
                } else if (rms < utterancePeakRms * SUB_PEAK_ADAPT_RATIO) {
                    subPeakFrames++
                    if (subPeakFrames >= SUB_PEAK_MIN_FRAMES && rms > noiseFloor) {
                        noiseFloor = (noiseFloor * SUB_PEAK_KEEP_WEIGHT + rms * SUB_PEAK_SAMPLE_WEIGHT)
                            .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                    }
                } else {
                    subPeakFrames = 0
                }

                if (isVoiceActive) {
                    currentSilenceMs = 0L
                } else {
                    currentSilenceMs += frameDurationMs
                    updateNoiseFloorOnQuietFrame(rms)
                }

                val tailSilenceReached = currentSilenceMs >= minSilenceDurationMs && currentSpeechMs >= minSpeechDurationMs
                val maxDurationReached = currentSpeechMs >= maxSpeechDurationMs

                if (tailSilenceReached || maxDurationReached) {
                    lastSentenceTailSilenceMs = currentSilenceMs
                    state = State.SILENCE
                    lastFrameVoiceActive = false
                    currentSpeechMs = 0L
                    currentSilenceMs = 0L
                    totalIdleSilenceMs = 0L
                    consecutiveSpeechFrames = 0
                    utterancePeakRms = 0.0f
                    subPeakFrames = 0
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
            lastFrameVoiceActive = false
            currentSpeechMs = 0L
            currentSilenceMs = 0L
            totalIdleSilenceMs = 0L
            consecutiveSpeechFrames = 0
            utterancePeakRms = 0.0f
            subPeakFrames = 0
            listener.onSpeechEnd()
            return true
        }
        return false
    }

    fun reset() {
        state = State.SILENCE
        lastFrameVoiceActive = false
        consecutiveSpeechFrames = 0
        currentSilenceMs = 0L
        currentSpeechMs = 0L
        totalIdleSilenceMs = 0L
        lastSentenceTailSilenceMs = 0L
        utterancePeakRms = 0.0f
        subPeakFrames = 0
        noiseFloor = INITIAL_NOISE_FLOOR
    }
}
