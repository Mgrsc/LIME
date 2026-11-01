package org.bitfennec.lime.inputmethod.voice

import android.content.Context
import java.util.concurrent.TimeUnit
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
import org.bitfennec.lime.core.runtime.AiBlobStore
import org.bitfennec.lime.core.runtime.AiPackageSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    @Synchronized
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
    fun hasActiveLease(): Boolean = activeLeases.get() > 0
    fun isReady(): Boolean = engineState == EngineState.READY && recognizer != null

    internal fun createRecognizerConfig(blobsDir: File): OfflineRecognizerConfig {
        fun blob(artifact: AiPackageSpec.Artifact): String =
            AiBlobStore.getBlobFile(blobsDir, artifact.sha256, artifact.fileName).absolutePath

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioRecordHelper.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = blob(AiPackageSpec.voiceModelArtifact),
                    language = "",
                    useInverseTextNormalization = true,
                ),
                tokens = blob(AiPackageSpec.voiceTokensArtifact),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
    }

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
        val initQueuedAtNanos = System.nanoTime()
        try {
            engineScope.launch {
                val startTime = android.os.SystemClock.elapsedRealtime()
                val initStartedAtNanos = System.nanoTime()
                VoiceLatencyTrace.record(
                    VoiceLatencyTrace.Event.ENGINE_INIT_STARTED,
                    queueMs = TimeUnit.NANOSECONDS.toMillis(initStartedAtNanos - initQueuedAtNanos),
                )
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

                    val blobsDir = AiBlobStore.getBlobsDir(appContext)
                    val config = createRecognizerConfig(blobsDir)

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
                        android.util.Log.i("VoiceRecognitionEngine", """{"event":"voice.engine_init","result":"success","model_id":"${AiPackageSpec.voicePackage.id}","duration_ms":$durationMs}""")
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
                } finally {
                    VoiceLatencyTrace.record(
                        VoiceLatencyTrace.Event.ENGINE_INIT_COMPLETED,
                        status = when {
                            generation != initGeneration -> VoiceLatencyTrace.Status.DROPPED
                            success && engineState == EngineState.READY -> VoiceLatencyTrace.Status.SUCCESS
                            else -> VoiceLatencyTrace.Status.FAILED
                        },
                        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initStartedAtNanos),
                    )
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
        timeoutMs: Long = 6000L,
        diagnosticTraceId: Long = 0L,
        diagnosticTaskId: Long = 0L,
    ): String {
        val currentLooper = Looper.myLooper()
        if (currentLooper != null && currentLooper == Looper.getMainLooper()) {
            error("transcribeSync must not be called from main thread")
        }
        if (samples.isEmpty()) return ""

        val transcribeStartedAtNanos = System.nanoTime()
        VoiceLatencyTrace.record(
            VoiceLatencyTrace.Event.TRANSCRIBE_STARTED, diagnosticTraceId, diagnosticTaskId,
            sampleCount = samples.size,
        )

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
            VoiceLatencyTrace.record(
                VoiceLatencyTrace.Event.TRANSCRIBE_COMPLETED, diagnosticTraceId, diagnosticTaskId,
                status = VoiceLatencyTrace.Status.NOT_READY, sampleCount = samples.size,
                durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - transcribeStartedAtNanos),
            )
            android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.transcribe","result":"rejected","reason":"engine_not_ready"}""")
            return ""
        }

        val startTime = android.os.SystemClock.elapsedRealtime()
        var stream: OfflineStream? = null
        return synchronized(decodeLock) {
            if (rec != recognizer) {
                VoiceLatencyTrace.record(
                    VoiceLatencyTrace.Event.TRANSCRIBE_COMPLETED, diagnosticTraceId, diagnosticTaskId,
                    status = VoiceLatencyTrace.Status.DROPPED, sampleCount = samples.size,
                    durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - transcribeStartedAtNanos),
                )
                android.util.Log.w("VoiceRecognitionEngine", """{"event":"voice.transcribe","result":"rejected","reason":"recognizer_stale_or_released"}""")
                return@synchronized ""
            }
            var status = VoiceLatencyTrace.Status.FAILED
            try {
                stream = rec.createStream()
                stream.acceptWaveform(samples, sampleRate)
                VoiceLatencyTrace.record(
                    VoiceLatencyTrace.Event.DECODE_STARTED, diagnosticTraceId, diagnosticTaskId,
                    sampleCount = samples.size,
                )
                val decodeStartedAtNanos = System.nanoTime()
                var decoded = false
                try {
                    rec.decode(stream)
                    decoded = true
                } finally {
                    VoiceLatencyTrace.record(
                        VoiceLatencyTrace.Event.DECODE_COMPLETED, diagnosticTraceId, diagnosticTaskId,
                        status = if (decoded) VoiceLatencyTrace.Status.SUCCESS else VoiceLatencyTrace.Status.FAILED,
                        sampleCount = samples.size,
                        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - decodeStartedAtNanos),
                    )
                }
                val result = rec.getResult(stream)
                val rawText = result.text
                val durationMs = android.os.SystemClock.elapsedRealtime() - startTime
                val audioDurationMs = (samples.size * 1000L) / sampleRate
                val strippedLength = VoicePostProcessor.stripTags(rawText).trim().length
                android.util.Log.d(
                    "VoiceRecognitionEngine",
                    """{"event":"voice.transcribe","result":"success","duration_ms":$durationMs,"audio_duration_ms":$audioDurationMs,"sample_count":${samples.size},"raw_length":${rawText.length},"stripped_length":$strippedLength}"""
                )
                status = if (rawText.isEmpty()) VoiceLatencyTrace.Status.EMPTY else VoiceLatencyTrace.Status.SUCCESS
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
                try {
                    stream?.release()
                } finally {
                    VoiceLatencyTrace.record(
                        VoiceLatencyTrace.Event.TRANSCRIBE_COMPLETED, diagnosticTraceId, diagnosticTaskId,
                        status = status, sampleCount = samples.size,
                        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - transcribeStartedAtNanos),
                    )
                }
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
