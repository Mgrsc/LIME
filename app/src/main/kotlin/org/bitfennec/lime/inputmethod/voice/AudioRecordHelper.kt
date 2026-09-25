package org.bitfennec.lime.inputmethod.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 16kHz PCM mono audio recorder and VAD-aware stream collector.
 * Features:
 * 1. AudioSource.VOICE_RECOGNITION preferred, graceful fallback to MIC;
 * 2. Fixed 512-sample frames (32ms @ 16kHz) assembled from short reads;
 * 3. Pre-roll ring buffer (12 frames / ~384ms; ~256ms net lookback after 5-frame onset);
 * 4. Utterance aggregation with growing-window snapshots and final segmentation.
 */
class AudioRecordHelper {

    enum class VoiceCaptureMode {
        STANDARD,
        WHISPER
    }

    interface AudioCaptureListener {
        fun onAmplitudeUpdate(amplitude: Float)
        fun onSpeechStart()
        fun onSentenceFinal(samples: FloatArray)
        fun onSilenceTimeout()
        fun onCaptureModeChanged(mode: VoiceCaptureMode) {}
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 512 // 32ms @ 16kHz
        private const val PRE_SPEECH_FRAMES = 12 // ~384ms; trial net lookback ~256ms after 5-frame onset
        private const val MAX_SESSION_SAMPLES = 10 * SAMPLE_RATE // 10s max buffer cap (~320KB)
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    var forceStandard: Boolean = false
    private var currentMode = VoiceCaptureMode.STANDARD
    private var speechRmsEma = 0.0f
    private var consecutiveWhisperFrames = 0
    private var consecutiveStandardFrames = 0
    private var whisperSilenceHangover = 0
    private var currentGain = 1.0f
    private var sentenceGeneration = 0

    private var captureListener: AudioCaptureListener? = null

    fun toggleMode(): VoiceCaptureMode {
        val next = if (currentMode == VoiceCaptureMode.STANDARD) VoiceCaptureMode.WHISPER else VoiceCaptureMode.STANDARD
        currentMode = next
        mainScope.launch {
            if (isRecording.get() && !isAborted.get()) captureListener?.onCaptureModeChanged(next)
        }
        return next
    }

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isAborted = AtomicBoolean(false)
    private val lifecycleLock = Any()

    @Volatile
    private var stopCallback: ((FloatArray?) -> Unit)? = null
    @Volatile
    private var captureClosed = true
    private var pendingStopResult: FloatArray? = null

    // Ring pre-roll buffer (stores last 12 frames)
    private val preSpeechRingBuffer = Array(PRE_SPEECH_FRAMES) { ShortArray(FRAME_SIZE) }
    private var preSpeechIndex = 0
    private var preSpeechCount = 0

    // Current utterance audio accumulator
    private val sentenceSamples = ArrayList<Short>()
    private val sessionSamples = ArrayList<Short>()
    private val sentenceLock = Any()

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var vadDetector: VadDetector? = null

    /** Starts audio recording. */
    @SuppressLint("MissingPermission")
    fun startRecording(listener: AudioCaptureListener): Boolean {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) return false
        val bufferSize = max(minBufferSize, SAMPLE_RATE / 5)

        synchronized(lifecycleLock) {
            if (recordingThread?.isAlive == true) {
                android.util.Log.w("AudioRecordHelper", """{"event":"voice.record_start_rejected","reason":"previous_thread_alive"}""")
                return false
            }
            if (isRecording.get()) return true
            captureClosed = false
            stopCallback = null
            pendingStopResult = null
            isAborted.set(false)
            isRecording.set(true)
        }

        captureListener = listener
        currentMode = VoiceCaptureMode.STANDARD
        speechRmsEma = 0.0f
        consecutiveWhisperFrames = 0
        consecutiveStandardFrames = 0
        whisperSilenceHangover = 0
        currentGain = 1.0f
        sentenceGeneration = 0
        synchronized(sentenceLock) {
            sentenceSamples.clear()
            sessionSamples.clear()
            preSpeechIndex = 0
            preSpeechCount = 0
        }

        // Initialize VAD detector
        val vad = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 10000L,
            silenceTimeoutMs = 12000L,
            minSpeechDurationMs = 150L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {
                    if (isAborted.get()) return
                    android.util.Log.i("AudioRecordHelper", """{"event":"voice.speech_start"}""")
                    synchronized(sentenceLock) {
                        sentenceSamples.clear()
                        val startIdx = (preSpeechIndex - preSpeechCount + PRE_SPEECH_FRAMES) % PRE_SPEECH_FRAMES
                        for (k in 0 until preSpeechCount) {
                            val slot = (startIdx + k) % PRE_SPEECH_FRAMES
                            val buf = preSpeechRingBuffer[slot]
                            for (i in 0 until FRAME_SIZE) {
                                sentenceSamples.add(buf[i])
                            }
                        }
                        preSpeechCount = 0
                        preSpeechIndex = 0
                    }
                    mainScope.launch {
                        if (isRecording.get() && !isAborted.get()) listener.onSpeechStart()
                    }
                }

                override fun onSpeechEnd() {
                    if (isAborted.get()) return
                    val tailSilenceMs = vadDetector?.getLastSentenceTailSilenceMs() ?: 0L
                    val finalSentence = extractSentenceSamples(tailSilenceMs)
                    android.util.Log.i("AudioRecordHelper", """{"event":"voice.speech_end","sample_count":${finalSentence.size},"tail_silence_ms":$tailSilenceMs}""")
                    if (finalSentence.isNotEmpty() && !isAborted.get()) {
                        listener.onSentenceFinal(finalSentence)
                    }
                }

                override fun onSilenceTimeout() {
                    if (isAborted.get()) return
                    android.util.Log.i("AudioRecordHelper", """{"event":"voice.silence_timeout"}""")
                    mainScope.launch {
                        if (isRecording.get() && !isAborted.get()) listener.onSilenceTimeout()
                    }
                }
            }
        )
        vadDetector = vad

        recordingThread = Thread({
            var record: AudioRecord? = null

            // Prefer AudioSource.VOICE_RECOGNITION, fallback to MIC
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )

            for (source in sources) {
                try {
                    val candidate = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        val srcName = if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION" else "MIC"
                        android.util.Log.i("AudioRecordHelper", """{"event":"voice.record_init_success","source":"$srcName","buffer_size":$bufferSize}""")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("AudioRecordHelper", """{"event":"voice.record_source_failed","source":$source,"error":"${e.message}"}""")
                }
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                android.util.Log.e("AudioRecordHelper", """{"event":"voice.record_init_failed","error":"no initialized AudioRecord"}""")
                isRecording.set(false)
                finishCaptureThread(ownedRecord = null, frame = ShortArray(0), pendingCount = 0)
                return@Thread
            }

            audioRecord = record
            try {
                record.startRecording()
            } catch (e: Throwable) {
                android.util.Log.e("AudioRecordHelper", """{"event":"voice.record_start_error","error":"${e.message}"}""", e)
                isRecording.set(false)
                finishCaptureThread(ownedRecord = record, frame = ShortArray(0), pendingCount = 0)
                return@Thread
            }
            if (isAborted.get() || !isRecording.get()) {
                finishCaptureThread(ownedRecord = record, frame = ShortArray(0), pendingCount = 0)
                return@Thread
            }

            val frame = ShortArray(FRAME_SIZE)
            val floatChunk = FloatArray(FRAME_SIZE)
            var pendingCount = 0

            fun dispatchFullFrame() {
                var maxVal = 0
                var sumSq = 0.0
                for (i in 0 until FRAME_SIZE) {
                    val sample = frame[i].toInt()
                    val absVal = if (sample < 0) -sample else sample
                    if (absVal > maxVal) maxVal = absVal
                    val norm = absVal.toDouble() / 32768.0
                    sumSq += norm * norm
                    floatChunk[i] = sample.toFloat() / 32768.0f
                }

                vad.processFrame(floatChunk)

                val rms = sqrt(sumSq / FRAME_SIZE).toFloat()
                val inSpeech = vad.isInSpeech()

                val noise = max(0.0008f, vad.getNoiseFloor())

                val hasAudibleSignal = rms > noise * 1.15f
                if (!forceStandard) {
                    if (hasAudibleSignal) {
                        whisperSilenceHangover = 0
                        speechRmsEma = if (speechRmsEma <= 0f) rms else (speechRmsEma * 0.80f + rms * 0.20f)
                        val whisperCeiling = min(0.006f, noise * 4.0f)
                        val normalFloor = max(0.012f, noise * 8.0f)

                        if (speechRmsEma < whisperCeiling) {
                            consecutiveWhisperFrames++
                            consecutiveStandardFrames = 0
                            if (consecutiveWhisperFrames >= 10 && currentMode != VoiceCaptureMode.WHISPER) {
                                currentMode = VoiceCaptureMode.WHISPER
                                android.util.Log.i("AudioRecordHelper", """{"event":"voice.mode_switch","mode":"whisper","noise_floor":$noise,"speech_rms":$speechRmsEma}""")
                                mainScope.launch {
                                    if (isRecording.get() && !isAborted.get()) listener.onCaptureModeChanged(VoiceCaptureMode.WHISPER)
                                }
                            }
                        } else if (speechRmsEma >= normalFloor) {
                            consecutiveStandardFrames++
                            consecutiveWhisperFrames = 0
                            if (consecutiveStandardFrames >= 4 && currentMode != VoiceCaptureMode.STANDARD) {
                                currentMode = VoiceCaptureMode.STANDARD
                                android.util.Log.i("AudioRecordHelper", """{"event":"voice.mode_switch","mode":"standard","noise_floor":$noise,"speech_rms":$speechRmsEma}""")
                                mainScope.launch {
                                    if (isRecording.get() && !isAborted.get()) listener.onCaptureModeChanged(VoiceCaptureMode.STANDARD)
                                }
                            }
                        } else {
                            consecutiveWhisperFrames = 0
                            consecutiveStandardFrames = 0
                        }
                    } else {
                        whisperSilenceHangover++
                        if (whisperSilenceHangover > 4) {
                            consecutiveWhisperFrames = 0
                            consecutiveStandardFrames = 0
                        }
                    }
                }

                val targetGain = if (currentMode == VoiceCaptureMode.WHISPER && hasAudibleSignal) 1.8f else 1.0f
                currentGain = currentGain * 0.88f + targetGain * 0.12f

                synchronized(sentenceLock) {
                    appendGainedSamples(sessionSamples, frame, FRAME_SIZE, respectSessionCap = true)
                    if (inSpeech) {
                        appendGainedSamples(sentenceSamples, frame, FRAME_SIZE, respectSessionCap = false)
                    } else {
                        System.arraycopy(frame, 0, preSpeechRingBuffer[preSpeechIndex], 0, FRAME_SIZE)
                        preSpeechIndex = (preSpeechIndex + 1) % PRE_SPEECH_FRAMES
                        if (preSpeechCount < PRE_SPEECH_FRAMES) preSpeechCount++
                    }
                }

                val effectiveRms = if (currentGain > 1.02f) rms * currentGain else rms
                val peak = maxVal.toDouble() / 32768.0
                val combined = 0.65 * peak + 0.35 * effectiveRms
                val normalized = min(1.0f, max(0.06f, (combined * 16.0).pow(0.72).toFloat()))

                mainScope.launch {
                    if (isRecording.get() && !isAborted.get()) {
                        listener.onAmplitudeUpdate(normalized)
                    }
                }
            }

            while (true) {
                val currentRecord = audioRecord ?: break
                val received = try {
                    currentRecord.read(frame, pendingCount, FRAME_SIZE - pendingCount)
                } catch (_: Throwable) {
                    -1
                }

                if (received < 0) {
                    if (isAborted.get() || !isRecording.get()) break
                    android.util.Log.e("AudioRecordHelper", """{"event":"voice.record_read_error","error_code":$received}""")
                    isRecording.set(false)
                    break
                }

                if (received > 0) {
                    pendingCount += received
                }

                if (pendingCount == FRAME_SIZE && !isAborted.get()) {
                    dispatchFullFrame()
                    pendingCount = 0
                }

                if (isAborted.get()) {
                    pendingCount = 0
                    break
                }
                if (!isRecording.get()) break
            }

            finishCaptureThread(ownedRecord = record, frame = frame, pendingCount = pendingCount)
        }, "Voice-AudioRecord-Thread").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }

        return true
    }

    /** Retrieves accumulated audio snapshot for growing-window interim preview. */
    fun getCurrentSentenceSnapshot(): FloatArray {
        synchronized(sentenceLock) {
            if (sentenceSamples.isNotEmpty()) return shortsToFloatArray(sentenceSamples, sentenceSamples.size)
            if (sessionSamples.isNotEmpty()) return shortsToFloatArray(sessionSamples, sessionSamples.size)
            return FloatArray(0)
        }
    }

    /** Computes sample count after trimming trailing silence (preserves 150ms tail, min 400ms threshold). */
    private fun trimTailSilence(size: Int, tailSilenceMs: Long): Int {
        val minValidSamples = 400 * (SAMPLE_RATE / 1000)
        val safetyMarginMs = 150L
        val trimMs = if (tailSilenceMs > safetyMarginMs) tailSilenceMs - safetyMarginMs else 0L
        val trimSamples = (trimMs * (SAMPLE_RATE / 1000)).toInt()
        return max(minValidSamples, size - trimSamples)
    }

    /** Extracts and resets current utterance audio (trims trailing silence, preserves 150ms tail). */
    private fun extractSentenceSamples(tailSilenceMs: Long = 0L): FloatArray {
        synchronized(sentenceLock) {
            val size = sentenceSamples.size
            val minValidSamples = 400 * (SAMPLE_RATE / 1000)
            val result = if (size >= minValidSamples) {
                sentenceGeneration++
                shortsToFloatArray(sentenceSamples, trimTailSilence(size, tailSilenceMs))
            } else {
                FloatArray(0)
            }
            sentenceSamples.clear()
            sessionSamples.clear()
            preSpeechIndex = 0
            preSpeechCount = 0
            return result
        }
    }

    private fun extractRemainingForStop(tailSilenceMs: Long): FloatArray? {
        synchronized(sentenceLock) {
            preSpeechIndex = 0
            preSpeechCount = 0
            val size = sentenceSamples.size
            val minValidSamples = 400 * (SAMPLE_RATE / 1000)
            val result = if (size >= minValidSamples) {
                shortsToFloatArray(sentenceSamples, trimTailSilence(size, tailSilenceMs))
            } else if (sessionSamples.size >= minValidSamples && sentenceGeneration == 0) {
                val sessionSize = sessionSamples.size
                val targetSize = trimTailSilence(sessionSize, tailSilenceMs)
                android.util.Log.i("AudioRecordHelper", """{"event":"voice.fallback_session_audio","samples":$targetSize,"raw_session":$sessionSize}""")
                shortsToFloatArray(sessionSamples, targetSize)
            } else {
                null
            }
            sentenceSamples.clear()
            sessionSamples.clear()
            sentenceGeneration = 0
            whisperSilenceHangover = 0
            return result
        }
    }

    private fun shortsToFloatArray(src: ArrayList<Short>, count: Int): FloatArray {
        val arr = FloatArray(count)
        for (i in 0 until count) {
            arr[i] = src[i].toFloat() / 32768.0f
        }
        return arr
    }

    private fun appendGainedSamples(
        dest: ArrayList<Short>,
        src: ShortArray,
        count: Int,
        respectSessionCap: Boolean,
    ) {
        if (respectSessionCap && dest.size + count > MAX_SESSION_SAMPLES) return
        if (currentGain > 1.02f) {
            for (i in 0 until count) {
                dest.add((src[i] * currentGain).toInt().coerceIn(-32768, 32767).toShort())
            }
        } else {
            for (i in 0 until count) {
                dest.add(src[i])
            }
        }
    }

    private fun appendRemainder(frame: ShortArray, count: Int) {
        if (count <= 0) return
        synchronized(sentenceLock) {
            appendGainedSamples(sessionSamples, frame, count, respectSessionCap = true)
            if (vadDetector?.isInSpeech() == true) {
                appendGainedSamples(sentenceSamples, frame, count, respectSessionCap = false)
            }
        }
    }

    private fun discardBuffers() {
        synchronized(sentenceLock) {
            preSpeechIndex = 0
            preSpeechCount = 0
            sentenceSamples.clear()
            sessionSamples.clear()
            sentenceGeneration = 0
            whisperSilenceHangover = 0
        }
    }

    private fun releaseOwnedRecorder(ownedRecord: AudioRecord?) {
        if (ownedRecord == null) return
        try {
            ownedRecord.release()
        } catch (e: Throwable) {
            android.util.Log.w("AudioRecordHelper", """{"event":"voice.record_release_error","error":"${e.message}"}""")
        }
        synchronized(lifecycleLock) {
            if (audioRecord === ownedRecord) {
                audioRecord = null
            }
        }
    }

    private fun finishCaptureThread(ownedRecord: AudioRecord?, frame: ShortArray, pendingCount: Int) {
        val aborted = isAborted.get()
        if (!aborted && pendingCount > 0) {
            appendRemainder(frame, pendingCount)
        }

        val remaining = if (aborted) {
            discardBuffers()
            vadDetector?.reset()
            null
        } else {
            val tailSilenceMs = vadDetector?.getCurrentTailSilenceMs() ?: 0L
            vadDetector?.reset()
            extractRemainingForStop(tailSilenceMs)
        }
        captureListener = null
        releaseOwnedRecorder(ownedRecord)

        val callback: ((FloatArray?) -> Unit)?
        synchronized(lifecycleLock) {
            captureClosed = true
            if (isAborted.get()) {
                callback = null
                stopCallback = null
                pendingStopResult = null
            } else {
                callback = stopCallback
                stopCallback = null
                pendingStopResult = if (callback == null) remaining else null
            }
            if (Thread.currentThread() === recordingThread) {
                recordingThread = null
            }
        }
        if (!aborted) {
            android.util.Log.i(
                "AudioRecordHelper",
                """{"event":"voice.record_stop_complete","remaining_samples":${remaining?.size ?: 0}}"""
            )
        }
        if (callback != null) {
            mainScope.launch { callback.invoke(remaining) }
        }
    }

    private fun requestCaptureHalt() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
        } catch (e: Throwable) {
            android.util.Log.w("AudioRecordHelper", """{"event":"voice.record_stop_error","error":"${e.message}"}""")
        }
    }

    /**
     * Signals a normal stop. Does not block.
     *
     * Handoff is single-consumer: [onStopped] runs on the main thread only if this call
     * wins the slot (or capture already closed with a stored remainder).
     * `captureClosed` means the capture thread has finished extract/discard; a later
     * [stopRecording] then delivers any leftover or null immediately and must not wait
     * for another callback. [abortRecording] after a registered stop swallows that
     * callback; the abort caller is responsible for session teardown.
     */
    fun stopRecording(onStopped: ((FloatArray?) -> Unit)? = null) {
        var deliverNow: FloatArray? = null
        var alreadyClosed = false
        synchronized(lifecycleLock) {
            if (captureClosed) {
                alreadyClosed = true
                deliverNow = pendingStopResult
                pendingStopResult = null
            } else {
                stopCallback = onStopped
            }
        }
        if (alreadyClosed) {
            mainScope.launch { onStopped?.invoke(deliverNow) }
            return
        }
        requestCaptureHalt()
    }

    /**
     * Aborts capture, discards buffers, and does not invoke a previously registered
     * [stopRecording] callback. Does not block.
     */
    fun abortRecording() {
        synchronized(lifecycleLock) {
            isAborted.set(true)
            pendingStopResult = null
            stopCallback = null
        }
        requestCaptureHalt()
    }

    fun isRecording(): Boolean = isRecording.get()
}
