package org.bitfennec.lime.inputmethod

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Coroutine dispatchers for IME subsystems.
 * 1. rimeDispatcher: Serial dispatch view on shared thread pool (Dispatchers.Default.limitedParallelism(1))
 *    for Rime state machine and expression evaluation.
 * 2. hwDispatcher: Dedicated single-thread (NORM_PRIORITY) for ONNX handwriting recognition (isolated from ASR)
 * 3. asrDispatcher: Dedicated single-thread (NORM_PRIORITY) for Sherpa voice streaming and final recognition
 * 4. idleDispatcher: Dedicated single-thread (MIN_PRIORITY) for predictions, OpenCC warmup, and dictionary IO
 *
 * NOTE: The underlying worker executors (hw/asr/idle) are process-level resources that persist across ImeService recreations
 * so that singletons holding scope references do not encounter RejectedExecutionException on restart.
 */
object ImeDispatchers {
    // Serial dispatch view on shared Dispatchers.Default pool for Rime core (limitedParallelism(1) ensures sequential execution between suspension points; action ordering is provided by EnginePipeline's channel)
    val rimeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    @Volatile
    private var hwExecutor: ExecutorService? = null
    @Volatile
    private var _hwDispatcher: CoroutineDispatcher? = null

    val hwDispatcher: CoroutineDispatcher
        @Synchronized
        get() {
            var executor = hwExecutor
            if (executor == null || executor.isShutdown || executor.isTerminated) {
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "ime-hw-worker").apply { priority = Thread.NORM_PRIORITY }
                }
                hwExecutor = executor
                _hwDispatcher = executor.asCoroutineDispatcher()
            }
            return _hwDispatcher!!
        }

    @Volatile
    private var asrExecutor: ExecutorService? = null
    @Volatile
    private var _asrDispatcher: CoroutineDispatcher? = null

    val asrDispatcher: CoroutineDispatcher
        @Synchronized
        get() {
            var executor = asrExecutor
            if (executor == null || executor.isShutdown || executor.isTerminated) {
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "ime-asr-worker").apply { priority = Thread.NORM_PRIORITY }
                }
                asrExecutor = executor
                _asrDispatcher = executor.asCoroutineDispatcher()
            }
            return _asrDispatcher!!
        }

    @Volatile
    private var idleExecutor: ExecutorService? = null
    @Volatile
    private var _idleDispatcher: CoroutineDispatcher? = null

    val idleDispatcher: CoroutineDispatcher
        @Synchronized
        get() {
            var executor = idleExecutor
            if (executor == null || executor.isShutdown || executor.isTerminated) {
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "ime-idle-worker").apply { priority = Thread.MIN_PRIORITY }
                }
                idleExecutor = executor
                _idleDispatcher = executor.asCoroutineDispatcher()
            }
            return _idleDispatcher!!
        }

    /**
     * Releases executor resources for test isolation or process exit.
     * Must NOT be called during regular ImeService.onDestroy() to avoid invalidating singletons' scopes.
     * Dispatchers will automatically recreate on subsequent access.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    @Synchronized
    fun shutdown() {
        try {
            hwExecutor?.shutdownNow()
            asrExecutor?.shutdownNow()
            idleExecutor?.shutdownNow()
            hwExecutor?.awaitTermination(100, TimeUnit.MILLISECONDS)
            asrExecutor?.awaitTermination(100, TimeUnit.MILLISECONDS)
            idleExecutor?.awaitTermination(100, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        } finally {
            hwExecutor = null
            asrExecutor = null
            idleExecutor = null
            _hwDispatcher = null
            _asrDispatcher = null
            _idleDispatcher = null
        }
    }
}
