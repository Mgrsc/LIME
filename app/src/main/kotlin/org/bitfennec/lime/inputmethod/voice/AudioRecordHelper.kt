package org.bitfennec.lime.inputmethod.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bitfennec.lime.BuildConfig
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
 * 3. Sentence start keeps the session audio already recorded; the 12-frame ring is only a fallback;
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

    private class ShortSampleBuffer(initialCapacity: Int) {
        private var data = ShortArray(initialCapacity)
        var size: Int = 0
            private set

        fun isNotEmpty(): Boolean = size > 0

        fun clear() {
            size = 0
        }

        private fun ensureCapacity(required: Int) {
            if (required <= data.size) return
            var newCap = data.size.coerceAtLeast(1024)
            while (newCap < required) {
                newCap = newCap shl 1
            }
            data = data.copyOf(newCap)
        }

        fun add(value: Short) {
            ensureCapacity(size + 1)
            data[size++] = value
        }

        fun addAll(src: ShortArray, count: Int) {
            if (count <= 0) return
            ensureCapacity(size + count)
            System.arraycopy(src, 0, data, size, count)
            size += count
        }

        fun copyInto(dest: ShortSampleBuffer) {
            if (size <= 0) return
            dest.addAll(data, size)
        }

        fun toFloatArray(count: Int = size, startOffset: Int = 0): FloatArray {
            val safeStart = startOffset.coerceIn(0, size)
            val safeCount = count.coerceIn(0, size - safeStart)
            val out = FloatArray(safeCount)
            for (i in 0 until safeCount) {
                out[i] = data[safeStart + i].toFloat() / 32768.0f
            }
            return out
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 512 // 32ms @ 16kHz
        private const val FRAME_DURATION_MS = (FRAME_SIZE * 1000L) / SAMPLE_RATE
        private const val PRE_SPEECH_FRAMES = 12 // ~384ms; trial net lookback ~224ms after 5-frame onset
        private const val MAX_SESSION_SAMPLES = 15 * SAMPLE_RATE // 15s fallback buffer cap (~480KB primitive)
        private const val MAX_PREVIEW_SAMPLES = 12 * SAMPLE_RATE // 12s window cap covering full 10s max utterance + preroll/tail
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val DEFAULT_NOISE_FLOOR = VadDetector.INITIAL_NOISE_FLOOR
        private const val MIN_EFFECTIVE_NOISE = VadDetector.MIN_NOISE_FLOOR
        private const val MIN_AUDIBLE_RMS_FLOOR = 0.00020f
        private const val MAX_AUDIBLE_RMS_CEILING = 0.00045f
        private const val AUDIBLE_NOISE_MULTIPLIER = 1.3f
        private const val MIN_SESSION_AUDIBLE_FRAMES = 2
        private const val WHISPER_CEILING_MIN = 0.00085f
        private const val WHISPER_CEILING_MAX = 0.00100f
        private const val WHISPER_NOISE_MULTIPLIER = 1.25f
        private const val NORMAL_FLOOR_MIN = 0.00115f
        private const val NORMAL_FLOOR_MAX = 0.00135f
        private const val NORMAL_NOISE_MULTIPLIER = 1.5f
        private const val WHISPER_HYSTERESIS_GAP = 0.00010f
        private const val WHISPER_TRIGGER_FRAMES = 6
        private const val MIN_AUDIBLE_FOR_WHISPER = 6
        private const val STANDARD_TRIGGER_FRAMES = 3
        private const val SILENCE_HANGOVER_FRAMES = 12
        private const val WHISPER_TARGET_GAIN = 1.8f
    }

    var forceStandard: Boolean = false
    private var currentMode = VoiceCaptureMode.STANDARD
    private var speechRmsEma = 0.0f
    private var consecutiveWhisperFrames = 0
    private var consecutiveStandardFrames = 0
    private var whisperSilenceHangover = 0
    private var standardLocked = false
    private var currentGain = 1.0f
    private var sentenceGeneration = 0
    private var sessionAudibleFrames = 0

    private var statsTotalFrames = 0
    private var statsSpeechFrames = 0
    private var statsAudibleFrames = 0
    private var statsBelowWhisperFrames = 0
    private var statsDeadZoneFrames = 0
    private var statsAboveNormalFrames = 0
    private var statsRmsSum = 0.0
    private var statsRmsMax = 0.0f
    private var statsEmaMin = 0.0f
    private var statsEmaMax = 0.0f
    private var statsNoiseMin = 0.0f
    private var statsNoiseMax = 0.0f
    private var statsMaxWhisperStreak = 0
    private var statsMaxStandardStreak = 0

    private var captureListener: AudioCaptureListener? = null

    private fun computeAudibleThreshold(noise: Float): Float =
        (noise * AUDIBLE_NOISE_MULTIPLIER).coerceIn(MIN_AUDIBLE_RMS_FLOOR, MAX_AUDIBLE_RMS_CEILING)

    private fun computeWhisperCeiling(noise: Float): Float =
        (noise * WHISPER_NOISE_MULTIPLIER).coerceIn(WHISPER_CEILING_MIN, WHISPER_CEILING_MAX)

    private fun computeNormalFloor(noise: Float, whisperCeiling: Float): Float =
        (noise * NORMAL_NOISE_MULTIPLIER)
            .coerceIn(NORMAL_FLOOR_MIN, NORMAL_FLOOR_MAX)
            .coerceAtLeast(whisperCeiling + WHISPER_HYSTERESIS_GAP)

    private fun resetAcousticStats() {
        statsTotalFrames = 0
        statsSpeechFrames = 0
        statsAudibleFrames = 0
        statsBelowWhisperFrames = 0
        statsDeadZoneFrames = 0
        statsAboveNormalFrames = 0
        statsRmsSum = 0.0
        statsRmsMax = 0.0f
        statsEmaMin = 0.0f
        statsEmaMax = 0.0f
        statsNoiseMin = 0.0f
        statsNoiseMax = 0.0f
        statsMaxWhisperStreak = 0
        statsMaxStandardStreak = 0
    }

    private fun formatAcousticSummaryJson(): String {
        val rmsMean = if (statsTotalFrames > 0) (statsRmsSum / statsTotalFrames).toFloat() else 0.0f
        val currentNoise = max(MIN_EFFECTIVE_NOISE, vadDetector?.getNoiseFloor() ?: DEFAULT_NOISE_FLOOR)
        val whisperCeiling = computeWhisperCeiling(currentNoise)
        val normalFloor = computeNormalFloor(currentNoise, whisperCeiling)
        return """"mode":"${currentMode.name.lowercase()}","gain":$currentGain,"force_standard":$forceStandard,"standard_locked":$standardLocked,"frames_total":$statsTotalFrames,"frames_speech":$statsSpeechFrames,"frames_audible":$statsAudibleFrames,"frames_below_whisper":$statsBelowWhisperFrames,"frames_dead_zone":$statsDeadZoneFrames,"frames_above_normal":$statsAboveNormalFrames,"rms_mean":$rmsMean,"rms_max":$statsRmsMax,"ema_min":$statsEmaMin,"ema_max":$statsEmaMax,"ema_last":$speechRmsEma,"noise_min":$statsNoiseMin,"noise_max":$statsNoiseMax,"noise_last":$currentNoise,"whisper_ceiling":$whisperCeiling,"normal_floor":$normalFloor,"max_whisper_streak":$statsMaxWhisperStreak,"max_standard_streak":$statsMaxStandardStreak"""
    }

    fun toggleMode(): VoiceCaptureMode {
        val prev = currentMode
        val next = if (currentMode == VoiceCaptureMode.STANDARD) VoiceCaptureMode.WHISPER else VoiceCaptureMode.STANDARD
        currentMode = next
        android.util.Log.i(
            "AudioRecordHelper",
            """{"event":"voice.mode_switch","trigger":"manual","from_mode":"${prev.name.lowercase()}","mode":"${next.name.lowercase()}","speech_rms":$speechRmsEma,"current_gain":$currentGain}"""
        )
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
    private val sentenceSamples = ShortSampleBuffer(SAMPLE_RATE * 4)
    private val sessionSamples = ShortSampleBuffer(SAMPLE_RATE * 4)
    private val sentenceLock = Any()

    private val mainScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

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

        prepareCaptureSession(listener, notifyAsync = true)
        val vad = vadDetector ?: return false

        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var record: AudioRecord? = null

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
                    processCapturedFrame(frame, floatChunk, vad, listener, notifyAsync = true)
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

    private fun dispatchListenerEvent(notifyAsync: Boolean, action: () -> Unit) {
        if (notifyAsync) {
            mainScope.launch {
                if (isRecording.get() && !isAborted.get()) {
                    action()
                }
            }
        } else if (isRecording.get() && !isAborted.get()) {
            action()
        }
    }

    internal fun prepareCaptureSession(listener: AudioCaptureListener, notifyAsync: Boolean = false) {
        captureListener = listener
        currentMode = VoiceCaptureMode.STANDARD
        speechRmsEma = 0.0f
        consecutiveWhisperFrames = 0
        consecutiveStandardFrames = 0
        whisperSilenceHangover = 0
        standardLocked = false
        currentGain = 1.0f
        sentenceGeneration = 0
        sessionAudibleFrames = 0
        resetAcousticStats()
        synchronized(sentenceLock) {
            sentenceSamples.clear()
            sessionSamples.clear()
            preSpeechIndex = 0
            preSpeechCount = 0
        }
        if (!notifyAsync) {
            isAborted.set(false)
            isRecording.set(true)
        }
        vadDetector = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 10000L,
            silenceTimeoutMs = 12000L,
            minSpeechDurationMs = 150L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {
                    if (isAborted.get()) return
                    val seededSamples: Int
                    synchronized(sentenceLock) {
                        sentenceSamples.clear()
                        if (sessionSamples.isNotEmpty()) {
                            sessionSamples.copyInto(sentenceSamples)
                        } else {
                            val startIdx = (preSpeechIndex - preSpeechCount + PRE_SPEECH_FRAMES) % PRE_SPEECH_FRAMES
                            for (k in 0 until preSpeechCount) {
                                val slot = (startIdx + k) % PRE_SPEECH_FRAMES
                                sentenceSamples.addAll(preSpeechRingBuffer[slot], FRAME_SIZE)
                            }
                        }
                        seededSamples = sentenceSamples.size
                        preSpeechCount = 0
                        preSpeechIndex = 0
                    }
                    val noise = max(MIN_EFFECTIVE_NOISE, vadDetector?.getNoiseFloor() ?: DEFAULT_NOISE_FLOOR)
                    val whisperCeiling = computeWhisperCeiling(noise)
                    val normalFloor = computeNormalFloor(noise, whisperCeiling)
                    android.util.Log.i(
                        "AudioRecordHelper",
                        """{"event":"voice.speech_start","mode":"${currentMode.name.lowercase()}","gain":$currentGain,"ema":$speechRmsEma,"noise_floor":$noise,"whisper_ceiling":$whisperCeiling,"normal_floor":$normalFloor,"seeded_samples":$seededSamples}"""
                    )
                    dispatchListenerEvent(notifyAsync) { listener.onSpeechStart() }
                }

                override fun onSpeechEnd() {
                    if (isAborted.get()) return
                    val tailSilenceMs = vadDetector?.getLastSentenceTailSilenceMs() ?: 0L
                    val rawSentenceCount: Int
                    val rawSessionCount: Int
                    synchronized(sentenceLock) {
                        rawSentenceCount = sentenceSamples.size
                        rawSessionCount = sessionSamples.size
                    }
                    val finalSentence = extractSentenceSamples(tailSilenceMs)
                    val dropReason = if (finalSentence.isEmpty()) "below_min_400ms" else "none"
                    android.util.Log.i(
                        "AudioRecordHelper",
                        """{"event":"voice.speech_end","sample_count":${finalSentence.size},"raw_sentence_samples":$rawSentenceCount,"raw_session_samples":$rawSessionCount,"tail_silence_ms":$tailSilenceMs,"sentence_gen":$sentenceGeneration,"drop_reason":"$dropReason",${formatAcousticSummaryJson()}}"""
                    )
                    speechRmsEma = 0.0f
                    consecutiveWhisperFrames = 0
                    consecutiveStandardFrames = 0
                    whisperSilenceHangover = 0
                    resetAcousticStats()
                    if (finalSentence.isNotEmpty() && !isAborted.get()) {
                        listener.onSentenceFinal(finalSentence)
                    }
                }

                override fun onSilenceTimeout() {
                    if (isAborted.get()) return
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("AudioRecordHelper", """{"event":"voice.silence_timeout",${formatAcousticSummaryJson()}}""")
                    }
                    dispatchListenerEvent(notifyAsync) { listener.onSilenceTimeout() }
                }
            }
        )
    }

    internal fun getCaptureMode(): VoiceCaptureMode = currentMode

    internal fun feedFrameForTest(frame: ShortArray, floatChunk: FloatArray = FloatArray(FRAME_SIZE)) {
        val vad = vadDetector ?: return
        val listener = captureListener ?: return
        processCapturedFrame(frame, floatChunk, vad, listener, notifyAsync = false)
    }

    internal fun processCapturedFrame(
        frame: ShortArray,
        floatChunk: FloatArray,
        vad: VadDetector,
        listener: AudioCaptureListener,
        notifyAsync: Boolean = true,
    ) {
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

        val noise = max(MIN_EFFECTIVE_NOISE, vad.getNoiseFloor())

        statsTotalFrames++
        if (inSpeech) statsSpeechFrames++
        statsRmsSum += rms.toDouble()
        if (rms > statsRmsMax) statsRmsMax = rms
        if (statsTotalFrames == 1 || noise < statsNoiseMin) statsNoiseMin = noise
        if (noise > statsNoiseMax) statsNoiseMax = noise

        val hasAudibleSignal = vad.isVoiceActiveFrame() || (!inSpeech && rms >= computeAudibleThreshold(noise))
        if (hasAudibleSignal) {
            vad.resetIdleSilence()
        }
        if (!forceStandard) {
            if (hasAudibleSignal) {
                statsAudibleFrames++
                whisperSilenceHangover = 0
                speechRmsEma = if (speechRmsEma <= 0f) rms else (speechRmsEma * 0.80f + rms * 0.20f)
                if (statsAudibleFrames == 1 || speechRmsEma < statsEmaMin) statsEmaMin = speechRmsEma
                if (speechRmsEma > statsEmaMax) statsEmaMax = speechRmsEma
                val whisperCeiling = computeWhisperCeiling(noise)
                val normalFloor = computeNormalFloor(noise, whisperCeiling)

                if (speechRmsEma < whisperCeiling) {
                    statsBelowWhisperFrames++
                    consecutiveWhisperFrames++
                    consecutiveStandardFrames = 0
                    if (consecutiveWhisperFrames > statsMaxWhisperStreak) {
                        statsMaxWhisperStreak = consecutiveWhisperFrames
                    }
                    if (!standardLocked &&
                        consecutiveWhisperFrames >= WHISPER_TRIGGER_FRAMES &&
                        statsAudibleFrames >= MIN_AUDIBLE_FOR_WHISPER &&
                        currentMode != VoiceCaptureMode.WHISPER
                    ) {
                        currentMode = VoiceCaptureMode.WHISPER
                        android.util.Log.i(
                            "AudioRecordHelper",
                            """{"event":"voice.mode_switch","trigger":"auto","mode":"whisper","noise_floor":$noise,"speech_rms":$speechRmsEma,"frame_rms":$rms,"whisper_ceiling":$whisperCeiling,"normal_floor":$normalFloor,"streak":$consecutiveWhisperFrames,"in_speech":$inSpeech}"""
                        )
                        dispatchListenerEvent(notifyAsync) { listener.onCaptureModeChanged(VoiceCaptureMode.WHISPER) }
                    }
                } else if (speechRmsEma >= normalFloor) {
                    statsAboveNormalFrames++
                    consecutiveStandardFrames++
                    consecutiveWhisperFrames = 0
                    if (consecutiveStandardFrames > statsMaxStandardStreak) {
                        statsMaxStandardStreak = consecutiveStandardFrames
                    }
                    if (consecutiveStandardFrames >= STANDARD_TRIGGER_FRAMES) {
                        if (!standardLocked) {
                            standardLocked = true
                            android.util.Log.i(
                                "AudioRecordHelper",
                                """{"event":"voice.standard_lock","noise_floor":$noise,"speech_rms":$speechRmsEma,"frame_rms":$rms,"normal_floor":$normalFloor,"streak":$consecutiveStandardFrames,"in_speech":$inSpeech}"""
                            )
                        }
                        if (currentMode != VoiceCaptureMode.STANDARD) {
                            currentMode = VoiceCaptureMode.STANDARD
                            android.util.Log.i(
                                "AudioRecordHelper",
                                """{"event":"voice.mode_switch","trigger":"auto","mode":"standard","noise_floor":$noise,"speech_rms":$speechRmsEma,"frame_rms":$rms,"whisper_ceiling":$whisperCeiling,"normal_floor":$normalFloor,"streak":$consecutiveStandardFrames,"in_speech":$inSpeech}"""
                            )
                            dispatchListenerEvent(notifyAsync) { listener.onCaptureModeChanged(VoiceCaptureMode.STANDARD) }
                        }
                    }
                } else {
                    statsDeadZoneFrames++
                    consecutiveWhisperFrames = 0
                    consecutiveStandardFrames = 0
                }
            } else {
                whisperSilenceHangover++
                if (whisperSilenceHangover > SILENCE_HANGOVER_FRAMES) {
                    consecutiveWhisperFrames = 0
                    consecutiveStandardFrames = 0
                }
            }
        }

        val targetGain = if (currentMode == VoiceCaptureMode.WHISPER && hasAudibleSignal) WHISPER_TARGET_GAIN else 1.0f
        currentGain = currentGain * 0.88f + targetGain * 0.12f

        synchronized(sentenceLock) {
            if (hasAudibleSignal) {
                if (sessionAudibleFrames == 0 && !sessionSamples.isNotEmpty()) {
                    val startIdx = (preSpeechIndex - preSpeechCount + PRE_SPEECH_FRAMES) % PRE_SPEECH_FRAMES
                    for (k in 0 until preSpeechCount) {
                        val slot = (startIdx + k) % PRE_SPEECH_FRAMES
                        sessionSamples.addAll(preSpeechRingBuffer[slot], FRAME_SIZE)
                    }
                }
                sessionAudibleFrames++
            }
            if (sessionAudibleFrames > 0) {
                appendGainedSamples(sessionSamples, frame, FRAME_SIZE, respectSessionCap = true)
            }
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

        dispatchListenerEvent(notifyAsync) { listener.onAmplitudeUpdate(normalized) }
    }

    /** Retrieves accumulated audio snapshot for growing-window interim preview. */
    fun getCurrentSentenceSnapshot(): FloatArray {
        synchronized(sentenceLock) {
            if (sentenceSamples.isNotEmpty()) {
                val count = min(sentenceSamples.size, MAX_PREVIEW_SAMPLES)
                val offset = sentenceSamples.size - count
                return sentenceSamples.toFloatArray(count = count, startOffset = offset)
            }
            if (sessionAudibleFrames >= MIN_SESSION_AUDIBLE_FRAMES && sessionSamples.isNotEmpty()) {
                val count = min(sessionSamples.size, MAX_PREVIEW_SAMPLES)
                val offset = sessionSamples.size - count
                return sessionSamples.toFloatArray(count = count, startOffset = offset)
            }
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
                val extracted = sentenceSamples.toFloatArray(count = trimTailSilence(size, tailSilenceMs))
                sessionSamples.clear()
                sessionAudibleFrames = 0
                extracted
            } else {
                FloatArray(0)
            }
            sentenceSamples.clear()
            preSpeechIndex = 0
            preSpeechCount = 0
            return result
        }
    }

    internal fun extractRemainingForStop(tailSilenceMs: Long = vadDetector?.getCurrentTailSilenceMs() ?: 0L): FloatArray? {
        synchronized(sentenceLock) {
            preSpeechIndex = 0
            preSpeechCount = 0
            val size = sentenceSamples.size
            val minValidSamples = 400 * (SAMPLE_RATE / 1000)
            val result = if (size >= minValidSamples) {
                sentenceSamples.toFloatArray(count = trimTailSilence(size, tailSilenceMs))
            } else if (sentenceGeneration == 0 &&
                sessionAudibleFrames >= MIN_SESSION_AUDIBLE_FRAMES &&
                sessionSamples.size >= minValidSamples
            ) {
                val sessionSize = sessionSamples.size
                val targetSize = trimTailSilence(sessionSize, tailSilenceMs)
                android.util.Log.i("AudioRecordHelper", """{"event":"voice.fallback_session_audio","samples":$targetSize,"raw_session":$sessionSize,"audible_frames":$sessionAudibleFrames,"sentence_gen":$sentenceGeneration}""")
                sessionSamples.toFloatArray(count = targetSize)
            } else {
                null
            }
            sentenceSamples.clear()
            sessionSamples.clear()
            sentenceGeneration = 0
            sessionAudibleFrames = 0
            whisperSilenceHangover = 0
            return result
        }
    }

    private fun appendGainedSamples(
        dest: ShortSampleBuffer,
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
            dest.addAll(src, count)
        }
    }

    private fun appendRemainder(frame: ShortArray, count: Int) {
        if (count <= 0) return
        synchronized(sentenceLock) {
            if (sessionAudibleFrames > 0) {
                appendGainedSamples(sessionSamples, frame, count, respectSessionCap = true)
            }
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
            sessionAudibleFrames = 0
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

        val rawSentenceCount: Int
        val rawSessionCount: Int
        val lastSentenceGen: Int
        synchronized(sentenceLock) {
            rawSentenceCount = sentenceSamples.size
            rawSessionCount = sessionSamples.size
            lastSentenceGen = sentenceGeneration
        }
        val acousticJson = formatAcousticSummaryJson()
        val remaining = if (aborted) {
            discardBuffers()
            vadDetector?.reset()
            null
        } else {
            val tailSilenceMs = vadDetector?.getCurrentTailSilenceMs() ?: 0L
            vadDetector?.reset()
            extractRemainingForStop(tailSilenceMs)
        }
        resetAcousticStats()
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
                """{"event":"voice.record_stop_complete","remaining_samples":${remaining?.size ?: 0},"raw_sentence_samples":$rawSentenceCount,"raw_session_samples":$rawSessionCount,"sentence_gen":$lastSentenceGen,$acousticJson}"""
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
