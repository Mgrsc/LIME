package org.bitfennec.lime.inputmethod.voice

import android.content.Context
import android.os.Looper
import androidx.annotation.WorkerThread
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import org.bitfennec.lime.inputmethod.ImeDispatchers
import org.bitfennec.lime.core.runtime.AiModuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SenseVoice-Small offline ASR engine singleton.
 * Features: State machine (UNINITIALIZED / INITIALIZING / READY / ERROR), queuing callbacks, hot reload.
 * Bound to dedicated single-thread ImeDispatchers.asrDispatcher, main dispatch via Dispatchers.Main.immediate.
 */
object VoiceRecognitionEngine {

    enum class EngineState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        ERROR
    }

    @Volatile
    private var engineState = EngineState.UNINITIALIZED

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var initGeneration = 0

    private val supervisorJob = SupervisorJob()
    private val engineScope: CoroutineScope
        get() = CoroutineScope(supervisorJob + ImeDispatchers.asrDispatcher)
    private val pendingInitCallbacks = ArrayList<(Boolean) -> Unit>()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val initLock: java.lang.Object = java.lang.Object()
    private val decodeLock = Any()
    private val activeLeases = java.util.concurrent.atomic.AtomicInteger(0)

    fun acquireLease() {
        activeLeases.incrementAndGet()
        cancelIdleUnload()
    }

    fun releaseLease() {
        val remaining = activeLeases.decrementAndGet()
        if (remaining <= 0) {
            activeLeases.set(0)
            scheduleIdleUnload()
        }
    }

    fun getEngineState(): EngineState = engineState
    fun isReady(): Boolean = engineState == EngineState.READY && recognizer != null

    /** Force reload model (for hot updates after re-downloading). */
    fun reload(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        release()
        init(context, forceReload = true, onComplete = onComplete)
    }

    private fun safeDispatchMain(action: () -> Unit) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
                return
            }
        } catch (_: Throwable) {
            // JVM unit test environment without Android Looper stub
        }
        try {
            engineScope.launch {
                try {
                    withContext(Dispatchers.Main.immediate) { action() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    action()
                }
            }
        } catch (_: Throwable) {
            action()
        }
    }

    /** Asynchronously initialize or reload recognizer. */
    fun init(context: Context, forceReload: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        cancelIdleUnload()
        var generation = 0
        var immediateSuccess = false
        synchronized(this) {
            if (!forceReload) {
                when (engineState) {
                    EngineState.READY -> {
                        immediateSuccess = true
                    }
                    EngineState.INITIALIZING -> {
                        onComplete?.let { pendingInitCallbacks.add(it) }
                        return
                    }
                    EngineState.UNINITIALIZED, EngineState.ERROR -> {
                        generation = ++initGeneration
                        engineState = EngineState.INITIALIZING
                        onComplete?.let { pendingInitCallbacks.add(it) }
                    }
                }
            } else {
                generation = ++initGeneration
                engineState = EngineState.INITIALIZING
                onComplete?.let { pendingInitCallbacks.add(it) }
            }
        }

        if (immediateSuccess) {
            safeDispatchMain { onComplete?.invoke(true) }
            return
        }

        val appContext = context.applicationContext
        try {
            engineScope.launch {
                val startTime = android.os.SystemClock.elapsedRealtime()
                var createdRecognizer: OfflineRecognizer? = null
                var success = false
                try {
                    if (generation != initGeneration) {
                        return@launch
                    }

                    if (!AiModuleManager.prepareVoiceRuntime(appContext)) {
                        val durationMs = android.os.SystemClock.elapsedRealtime() - startTime
                        android.util.Log.e("VoiceRecognitionEngine", """{"event":"voice.engine_init","result":"failed","reason":"runtime_missing","duration_ms":$durationMs}""")
                        synchronized(this@VoiceRecognitionEngine) {
                            if (generation == initGeneration) {
                                engineState = EngineState.ERROR
                                recognizer = null
                            }
                        }
                        notifyInitComplete(generation, false)
                        return@launch
                    }

                    if (!VoiceModelManager.isModelReady(appContext)) {
                        val durationMs = android.os.SystemClock.elapsedRealtime() - startTime
                        android.util.Log.e("VoiceRecognitionEngine", """{"event":"voice.engine_init","result":"failed","reason":"model_not_ready","duration_ms":$durationMs}""")
                        synchronized(this@VoiceRecognitionEngine) {
                            if (generation == initGeneration) {
                                engineState = EngineState.ERROR
                                recognizer = null
                            }
                        }
                        notifyInitComplete(generation, false)
                        return@launch
                    }

                    if (generation != initGeneration) {
                        return@launch
                    }

                    val modelFile = VoiceModelManager.getModelFile(appContext)
                    val tokensFile = VoiceModelManager.getTokensFile(appContext)

                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = modelFile.absolutePath,
                                language = "", // Auto language identification (zh/en/yue/ja/ko)
                                useInverseTextNormalization = true // Inverse text normalization (punctuation, numbers, quantifiers)
                            ),
                            tokens = tokensFile.absolutePath,
                            // Sherpa C++ intra-op thread count (2 threads for acoustic model; Kotlin dispatch remains sequential on asrDispatcher)
                            numThreads = 2,
                            debug = false,
                            provider = "cpu"
                        )
                    )

                    // Create recognizer outside synchronized block — constructor parses 200MB ONNX model and takes 1-2s.
                    // Holding the lock here blocks the Main thread if the user presses space while pre-warm is in flight!
                    val newRecognizer = OfflineRecognizer(config = config)
                    createdRecognizer = newRecognizer

                    var oldRecognizerToRelease: OfflineRecognizer? = null
                    synchronized(this@VoiceRecognitionEngine) {
                        if (generation == initGeneration) {
                            oldRecognizerToRelease = recognizer
                            recognizer = newRecognizer
                            engineState = EngineState.READY
                            success = true
                        } else {
                            // Generation became obsolete while parsing ONNX model
                            success = false
                        }
                    }

                    if (success) {
                        val durationMs = android.os.SystemClock.elapsedRealtime() - startTime
                        android.util.Log.i("VoiceRecognitionEngine", """{"event":"voice.engine_init","result":"success","duration_ms":$durationMs}""")
                        oldRecognizerToRelease?.let { oldRec ->
                            synchronized(decodeLock) {
                                try {
                                    oldRec.release()
                                } catch (e: Throwable) {
                                    android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.engine_release_error","context":"init_replace","error":"${escapeJson(e.message ?: "unknown")}"}""")
                                }
                            }
                        }
                        notifyInitComplete(generation, true)
                    } else {
                        synchronized(decodeLock) {
                            try {
                                createdRecognizer.release()
                            } catch (e: Throwable) {
                                android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.engine_release_error","context":"stale_init_discard","error":"${escapeJson(e.message ?: "unknown")}"}""")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    val durationMs = android.os.SystemClock.elapsedRealtime() - startTime
                    android.util.Log.e("VoiceRecognitionEngine", """{"event":"voice.engine_init","result":"failed","reason":"exception","duration_ms":$durationMs,"error":"${escapeJson(e.message ?: "unknown")}"}""", e)
                    synchronized(this@VoiceRecognitionEngine) {
                        if (generation == initGeneration) {
                            engineState = EngineState.ERROR
                            recognizer = null
                        }
                    }
                    createdRecognizer?.let { rec ->
                        synchronized(decodeLock) {
                            try {
                                rec.release()
                            } catch (_: Throwable) {}
                        }
                    }
                    notifyInitComplete(generation, false)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("VoiceRecognitionEngine", """{"event":"voice.engine_init","result":"failed","reason":"launch_rejected","error":"${escapeJson(e.message ?: "unknown")}"}""", e)
            synchronized(this) {
                if (generation == initGeneration) {
                    engineState = EngineState.ERROR
                    recognizer = null
                }
            }
            notifyInitComplete(generation, false)
        }
    }

    private fun notifyInitComplete(generation: Int, success: Boolean) {
        val callbacks = synchronized(this) {
            if (generation != initGeneration) {
                return
            }
            val list = ArrayList(pendingInitCallbacks)
            pendingInitCallbacks.clear()
            list
        }
        synchronized(initLock) {
            initLock.notifyAll()
        }
        if (callbacks.isNotEmpty()) {
            safeDispatchMain {
                for (cb in callbacks) {
                    cb.invoke(success)
                }
            }
        }
    }

    /**
     * Synchronously recognizes 16kHz PCM audio samples on worker/asrDispatcher thread.
     *
     * Note: In production pipeline (AsrWorker), initialization runs FIFO-sequenced on asrDispatcher,
     * so the engine is guaranteed to be initialized when transcription starts. The initLock wait serves
     * as defensive cross-thread synchronization (e.g. unit tests or background tasks).
     */
    @WorkerThread
    fun transcribeSync(
        samples: FloatArray,
        sampleRate: Int = 16000,
        timeoutMs: Long = 6000L
    ): String {
        val currentLooper = Looper.myLooper()
        if (currentLooper != null && currentLooper == Looper.getMainLooper()) {
            error("transcribeSync must not be called from main thread")
        }
        if (samples.isEmpty()) return ""

        var rec = recognizer
        if (rec == null && engineState == EngineState.INITIALIZING) {
            val startTime = android.os.SystemClock.elapsedRealtime()
            synchronized(initLock) {
                while (recognizer == null && engineState == EngineState.INITIALIZING) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                    val remaining = timeoutMs - elapsed
                    if (remaining <= 0) break
                    try {
                        initLock.wait(remaining)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            rec = recognizer
        }

        if (rec == null) {
            android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.transcribe","result":"rejected","reason":"engine_not_ready"}""")
            return ""
        }

        val startTime = android.os.SystemClock.elapsedRealtime()
        var stream: OfflineStream? = null
        return synchronized(decodeLock) {
            if (rec != recognizer) {
                android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.transcribe","result":"rejected","reason":"recognizer_stale_or_released"}""")
                return@synchronized ""
            }
            try {
                stream = rec.createStream()
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                val result = rec.getResult(stream)
                val rawText = result.text
                val durationMs = android.os.SystemClock.elapsedRealtime() - startTime
                val audioDurationMs = (samples.size * 1000L) / sampleRate
                android.util.Log.i(
                    "VoiceRecognitionEngine",
                    """{"event":"voice.transcribe","result":"success","duration_ms":$durationMs,"audio_duration_ms":$audioDurationMs,"sample_count":${samples.size},"raw_text":"${escapeJson(rawText)}"}"""
                )
                rawText
            } catch (e: Throwable) {
                val durationMs = android.os.SystemClock.elapsedRealtime() - startTime
                android.util.Log.e(
                    "VoiceRecognitionEngine",
                    """{"event":"voice.transcribe","result":"error","duration_ms":$durationMs,"error":"${escapeJson(e.message ?: "unknown")}"}""",
                    e
                )
                ""
            } finally {
                stream?.release()
            }
        }
    }

    internal fun escapeJson(str: String): String {
        return org.bitfennec.lime.utils.StringUtils.escapeJson(str)
    }

    private var idleUnloadJob: kotlinx.coroutines.Job? = null

    /** Schedules idle unload (default 300s / 5min) to release ~240MB model memory. Lease pins prevent unload. */
    fun scheduleIdleUnload(timeoutMs: Long? = null) {
        val effectiveTimeout = timeoutMs ?: runCatching {
            org.bitfennec.lime.prefs.AppPrefs.getInstance().voice.idleUnloadTimeoutSeconds.getValue() * 1000L
        }.getOrDefault(300_000L)

        synchronized(this) {
            idleUnloadJob?.cancel()
            idleUnloadJob = engineScope.launch {
                kotlinx.coroutines.delay(effectiveTimeout)
                synchronized(this@VoiceRecognitionEngine) {
                    if (activeLeases.get() > 0) {
                        return@synchronized
                    }
                    if (engineState == EngineState.READY) {
                        android.util.Log.i("VoiceRecognitionEngine", """{"event":"voice.engine_idle_timeout","action":"released"}""")
                        release()
                    }
                }
            }
        }
    }

    fun cancelIdleUnload() {
        synchronized(this) {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
        }
    }

    /** Releases native resources. */
    fun release() {
        val oldRecognizer: OfflineRecognizer?
        val callbacksToNotify: List<(Boolean) -> Unit>
        synchronized(this) {
            initGeneration++
            cancelIdleUnload()
            oldRecognizer = recognizer
            recognizer = null
            engineState = EngineState.UNINITIALIZED
            callbacksToNotify = ArrayList(pendingInitCallbacks)
            pendingInitCallbacks.clear()
        }
        synchronized(initLock) {
            initLock.notifyAll()
        }
        if (callbacksToNotify.isNotEmpty()) {
            safeDispatchMain {
                for (cb in callbacksToNotify) {
                    cb.invoke(false)
                }
            }
        }
        if (oldRecognizer != null) {
            try {
                engineScope.launch {
                    synchronized(decodeLock) {
                        try {
                            oldRecognizer.release()
                        } catch (e: Throwable) {
                            android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.engine_release_error","context":"release","error":"${escapeJson(e.message ?: "unknown")}"}""")
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.engine_release_error","context":"launch_release","error":"${escapeJson(e.message ?: "unknown")}"}""")
                synchronized(decodeLock) {
                    try {
                        oldRecognizer.release()
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
