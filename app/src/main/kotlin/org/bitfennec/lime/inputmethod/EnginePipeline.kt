package org.bitfennec.lime.inputmethod

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.core.Rime
import org.bitfennec.lime.inputmethod.predict.CandidateStripMode
import org.bitfennec.lime.inputmethod.predict.PredictDispatcher
import org.bitfennec.lime.inputmethod.predict.PredictEngine
import org.bitfennec.lime.inputmethod.predict.PredictRequestValidator
import org.bitfennec.lime.inputmethod.predict.PredictionSource
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.container.SymbolContainer
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs

/**
 * State snapshot emitted by the background engine.
 */
data class EngineState(
    val candidates: List<CandidateListItem> = emptyList(),
    val predictionSources: List<PredictionSource?> = emptyList(),
    val showComposition: String = "",
    val compositionCursorPos: Int = 0,
    val prefixs: List<String> = emptyList(),
    val t9LockedCount: Int = 0,
    val isFinish: Boolean = true,
    val isAssociate: Boolean = false,
    val stripMode: CandidateStripMode = CandidateStripMode.EMPTY,
    val currentSchema: String = "",
    val candidatePageNo: Int = 0,
    val hasNextCandidatePage: Boolean = false,
    val charCase: Int = 0,
    val gen: Long = 0L,
    val sessionId: Long = 0L,
    val traceSequence: Long = 0L,
    val engineReady: Boolean = true,
)

data class EditorSessionKey(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val privateImeOptions: String,
) {
    companion object {
        fun from(editorInfo: EditorInfo?): EditorSessionKey = EditorSessionKey(
            packageName = editorInfo?.packageName.orEmpty(),
            fieldId = editorInfo?.fieldId ?: View.NO_ID,
            inputType = editorInfo?.inputType ?: 0,
            privateImeOptions = editorInfo?.privateImeOptions.orEmpty(),
        )
    }
}

/**
 * Single event delivered to UI / InputConnection.
 */
sealed interface EngineEvent {
    val sessionId: Long
    val traceSequence: Long

    data class CommitText(
        override val sessionId: Long,
        val text: String,
        override val traceSequence: Long = 0L,
    ) : EngineEvent

    data class ApplyRimeSnapshot(
        override val sessionId: Long,
        val committedText: String?,
        val composingText: String,
        val sequence: Long,
        override val traceSequence: Long = 0L,
    ) : EngineEvent

    data class CompositionCleared(
        override val sessionId: Long,
        val modalRequestId: Long,
        override val traceSequence: Long = 0L,
    ) : EngineEvent

    data class SendKeyEvent(
        override val sessionId: Long,
        val keyCode: Int,
        val delayMs: Long = 0L,
        override val traceSequence: Long = 0L,
    ) : EngineEvent
}

sealed interface SessionUpdate {
    val sessionId: Long
    val traceSequence: Long

    data class State(val state: EngineState) : SessionUpdate {
        override val sessionId: Long = state.sessionId
        override val traceSequence: Long = state.traceSequence
    }

    data class Event(val event: EngineEvent) : SessionUpdate {
        override val sessionId: Long = event.sessionId
        override val traceSequence: Long = event.traceSequence
    }
}

/**
 * Input/control actions sent to the background EngineActor.
 */
sealed interface EngineAction {
    data class NormalKey(val event: KeyEvent, val gen: Long = 0L) : EngineAction
    data class DeleteKey(val gen: Long = 0L) : EngineAction
    data class SpaceKey(val gen: Long = 0L) : EngineAction
    data class EnterKey(val gen: Long = 0L) : EngineAction
    data class MoveCompositionCursor(val targetIndex: Int, val gen: Long = 0L) : EngineAction
    data class StepCompositionCursor(val direction: Int, val gen: Long = 0L) : EngineAction
    data class CommitThenKey(val keyCode: Int, val gen: Long = 0L) : EngineAction
    data class CommitThenText(val text: String, val moveCursorLeft: Boolean = false, val gen: Long = 0L) : EngineAction
    data class SelectCandidate(val index: Int, val pageNo: Int? = null, val gen: Long = 0L) : EngineAction
    data class SelectPrefix(val index: Int, val gen: Long = 0L) : EngineAction
    data class SelectAssociation(val index: Int, val gen: Long = 0L) : EngineAction
    data class ApplyAssociation(
        val sessionId: Long,
        val seq: Long = 0L,
        val candidates: List<CandidateListItem>,
        val predictionSources: List<PredictionSource> = emptyList(),
        val gen: Long = 0L,
    ) : EngineAction
    data class RemovePredictionCandidate(val index: Int) : EngineAction
    data class DeleteCandidate(val index: Int, val gen: Long = 0L) : EngineAction
    data class SelectSchema(val schema: String, val gen: Long = 0L) : EngineAction
    data class SetImeOption(val option: String, val value: Boolean, val gen: Long = 0L) : EngineAction
    data class SetCharCase(val charCase: Int, val gen: Long = 0L) : EngineAction
    data class LoadMoreExpandedCandidates(val pageNo: Int, val gen: Long = 0L) : EngineAction
    data class ClearComposition(
        val gen: Long = 0L,
        val modalRequestId: Long? = null,
    ) : EngineAction
    data class ApplyEditorPolicy(
        val schema: String,
        val charCase: Int,
        val clearComposition: Boolean = true,
        val gen: Long = 0L,
    ) : EngineAction
    data class DismissPredictions(val gen: Long = 0L) : EngineAction
    data class Reset(val gen: Long = 0L) : EngineAction
    object Destroy : EngineAction
    class Maintenance<T>(val block: () -> T) : EngineAction
}

/**
 * High-performance asynchronous pipeline for IME processing.
 * Dispatches key processing and JNI context parsing strictly in FIFO order.
 * Action processing order is guaranteed by a single-consumer Channel actor loop.
 * Rime operations run on [ImeDispatchers.rimeDispatcher] (serial view on the shared thread pool).
 */
object EnginePipeline {
    private val scope = CoroutineScope(SupervisorJob() + ImeDispatchers.rimeDispatcher)
    private var associationJob: Job? = null
    @Volatile
    private var predictSequence = 0L

    private data class QueuedAction(
        val sessionId: Long,
        val action: EngineAction,
        val completion: CompletableDeferred<Any?>? = null,
        val sequence: Long,
        val traceSequence: Long = 0L,
        val enqueuedAtNanos: Long = 0L,
    )

    private val actionChannel = Channel<QueuedAction>(INPUT_QUEUE_CAPACITY)
    private val enqueueLock = Any()
    private val sessionCounter = AtomicLong(0L)
    private val actionSequenceCounter = AtomicLong(0L)
    private val traceSequenceCounter = AtomicLong(0L)
    private val pendingActions = AtomicInteger(0)
    private val deferredActions = ArrayDeque<QueuedAction>(DEFERRED_QUEUE_CAPACITY)
    private var deferredDrainJob: Job? = null

    // Confined to the single actor coroutine that processes and publishes actions.
    private var activeTraceSequence = 0L
    private var activeActionSequence = 0L

    // Accessed only while enqueueLock is held.
    private var activeSessionKey: EditorSessionKey? = null

    private data class SessionPolicy(
        val sessionId: Long = 0L,
        val flags: InputModeSwitcher.EditorFieldFlags = InputModeSwitcher.EditorFieldFlags(),
        val chinesePredictionEnabled: Boolean = true,
    )

    @Volatile
    private var activePolicy = SessionPolicy()

    private val _stateFlow = MutableStateFlow(EngineState(engineReady = false))
    val stateFlow: StateFlow<EngineState> = _stateFlow.asStateFlow()

    private val _updates = MutableSharedFlow<SessionUpdate>(extraBufferCapacity = 64)
    val updates: SharedFlow<SessionUpdate> = _updates.asSharedFlow()

    init {
        scope.launch {
            var bufferedAction: QueuedAction? = null
            while (true) {
                val queued = bufferedAction ?: actionChannel.receiveCatching().getOrNull() ?: break
                bufferedAction = null
                activeTraceSequence = queued.traceSequence
                activeActionSequence = queued.sequence
                try {
                    InputPipelineTrace.actionDequeued(
                        sessionId = queued.sessionId,
                        traceSequence = queued.traceSequence,
                        generation = queued.action.generation(),
                        action = queued.action.traceName(),
                        pendingInputActions = pendingActions.get(),
                        enqueueStartedAtNanos = queued.enqueuedAtNanos,
                    )
                    val currentSession = sessionCounter.get()
                    if (queued.sessionId == currentSession || queued.action is EngineAction.Maintenance<*>) {
                        if (queued.action is EngineAction.NormalKey) {
                            val batch = mutableListOf(queued)
                            while (true) {
                                val peek = actionChannel.tryReceive().getOrNull() ?: break
                                if (peek.sessionId == currentSession && peek.action is EngineAction.NormalKey) {
                                    InputPipelineTrace.actionDequeued(
                                        sessionId = peek.sessionId,
                                        traceSequence = peek.traceSequence,
                                        generation = peek.action.generation(),
                                        action = peek.action.traceName(),
                                        pendingInputActions = pendingActions.get(),
                                        enqueueStartedAtNanos = peek.enqueuedAtNanos,
                                    )
                                    batch.add(peek)
                                    if (tracksInput(peek.action)) pendingActions.decrementAndGet()
                                } else {
                                    bufferedAction = peek
                                    break
                                }
                            }
                            processNormalKeysBatch(queued.sessionId, batch)
                        } else if (queued.action is EngineAction.DeleteKey) {
                            val batch = mutableListOf(queued)
                            while (true) {
                                val peek = actionChannel.tryReceive().getOrNull() ?: break
                                if (peek.sessionId == currentSession && peek.action is EngineAction.DeleteKey) {
                                    InputPipelineTrace.actionDequeued(
                                        sessionId = peek.sessionId,
                                        traceSequence = peek.traceSequence,
                                        generation = peek.action.generation(),
                                        action = peek.action.traceName(),
                                        pendingInputActions = pendingActions.get(),
                                        enqueueStartedAtNanos = peek.enqueuedAtNanos,
                                    )
                                    batch.add(peek)
                                    if (tracksInput(peek.action)) pendingActions.decrementAndGet()
                                } else {
                                    bufferedAction = peek
                                    break
                                }
                            }
                            processDeleteKeysBatch(queued.sessionId, batch)
                        } else {
                            processAction(queued)
                        }
                    } else {
                        InputPipelineTrace.actionDropped(
                            sessionId = queued.sessionId,
                            traceSequence = queued.traceSequence,
                            generation = queued.action.generation(),
                            action = queued.action.traceName(),
                            pendingInputActions = pendingActions.get(),
                            enqueueStartedAtNanos = queued.enqueuedAtNanos,
                        )
                        queued.completion?.complete(null)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    queued.completion?.completeExceptionally(t)
                    publishState(EngineState(
                        sessionId = queued.sessionId,
                        gen = queued.action.generation(),
                        stripMode = CandidateStripMode.UNAVAILABLE,
                        engineReady = false,
                    ))
                } finally {
                    if (tracksInput(queued.action)) pendingActions.decrementAndGet()
                    activeTraceSequence = 0L
                    activeActionSequence = 0L
                }
            }
        }
    }

    private suspend fun processNormalKeysBatch(sessionId: Long, batch: List<QueuedAction>) {
        if (batch.isEmpty()) return
        cancelPendingTypeAPredict()
        for (i in 0 until batch.size - 1) {
            if (sessionId != sessionCounter.get()) return
            val queued = batch[i]
            activeActionSequence = queued.sequence
            val action = queued.action as EngineAction.NormalKey
            val startedAtNanos = InputPipelineTrace.mark()
            val commit = RimeEngine.onNormalKey(action.event)
            InputPipelineTrace.rimeProcessed(sessionId, queued.traceSequence, action.gen, action.traceName(), startedAtNanos)
            emitRimeSnapshot(sessionId, commit, traceSequence = queued.traceSequence)
            if (!commit.isNullOrEmpty()) {
                recordDecodedCommit(sessionId, commit)
            }
        }
        if (sessionId != sessionCounter.get()) return
        val lastQueued = batch.last()
        activeActionSequence = lastQueued.sequence
        val lastKey = lastQueued.action as EngineAction.NormalKey
        val startedAtNanos = InputPipelineTrace.mark()
        val finalCommit = RimeEngine.onNormalKey(lastKey.event)
        InputPipelineTrace.rimeProcessed(sessionId, lastQueued.traceSequence, lastKey.gen, lastKey.traceName(), startedAtNanos)
        if (sessionId != sessionCounter.get()) return
        emitRimeSnapshot(sessionId, finalCommit, traceSequence = lastQueued.traceSequence)
        publishCurrentState(sessionId, gen = lastKey.gen, traceSequence = lastQueued.traceSequence)
        if (!finalCommit.isNullOrEmpty()) {
            launchTypeAForCommit(sessionId, finalCommit, lastKey.gen)
        }
    }

    private suspend fun processDeleteKeysBatch(sessionId: Long, batch: List<QueuedAction>) {
        if (batch.isEmpty()) return
        cancelPendingPredictTasks()
        if (RimeEngine.isFinish()) {
            for (queued in batch) {
                if (sessionId != sessionCounter.get()) return
                emitEvent(EngineEvent.SendKeyEvent(sessionId, KeyEvent.KEYCODE_DEL, traceSequence = queued.traceSequence))
            }
            return
        }
        for (i in 0 until batch.size - 1) {
            if (sessionId != sessionCounter.get()) return
            val queued = batch[i]
            activeActionSequence = queued.sequence
            val action = queued.action as EngineAction.DeleteKey
            val startedAtNanos = InputPipelineTrace.mark()
            val commit = RimeEngine.onDeleteKey()
            InputPipelineTrace.rimeProcessed(sessionId, queued.traceSequence, action.gen, action.traceName(), startedAtNanos)
            emitRimeSnapshot(sessionId, commit, traceSequence = queued.traceSequence)
            if (!commit.isNullOrEmpty()) {
                recordDecodedCommit(sessionId, commit)
            }
            if (RimeEngine.isFinish()) {
                publishCurrentState(sessionId, gen = action.gen, traceSequence = queued.traceSequence)
                for (j in i + 1 until batch.size) {
                    if (sessionId != sessionCounter.get()) return
                    emitEvent(EngineEvent.SendKeyEvent(sessionId, KeyEvent.KEYCODE_DEL, traceSequence = batch[j].traceSequence))
                }
                return
            }
        }
        if (sessionId != sessionCounter.get()) return
        val lastQueued = batch.last()
        activeActionSequence = lastQueued.sequence
        val lastKey = lastQueued.action as EngineAction.DeleteKey
        val startedAtNanos = InputPipelineTrace.mark()
        val finalCommit = RimeEngine.onDeleteKey()
        InputPipelineTrace.rimeProcessed(sessionId, lastQueued.traceSequence, lastKey.gen, lastKey.traceName(), startedAtNanos)
        if (sessionId != sessionCounter.get()) return
        emitRimeSnapshot(sessionId, finalCommit, traceSequence = lastQueued.traceSequence)
        publishCurrentState(sessionId, gen = lastKey.gen, traceSequence = lastQueued.traceSequence)
        if (!finalCommit.isNullOrEmpty()) {
            launchTypeAForCommit(sessionId, finalCommit, lastKey.gen)
        }
    }

    fun beginSession(
        sessionKey: EditorSessionKey = EditorSessionKey.from(null),
        restarting: Boolean = false,
    ): Long = bindEditor(
        sessionKey = sessionKey,
        flags = InputModeSwitcher.currentEditorFieldFlags,
        restarting = restarting,
    )

    fun bindEditor(
        sessionKey: EditorSessionKey,
        flags: InputModeSwitcher.EditorFieldFlags,
        restarting: Boolean = false,
    ): Long = synchronized(enqueueLock) {
        val reused = restarting && sessionKey == activeSessionKey
        if (!reused) {
            cancelPendingPredictTasks()
            val newSession = sessionCounter.incrementAndGet()
            _stateFlow.value = EngineState(sessionId = newSession, isFinish = true, engineReady = _stateFlow.value.engineReady)
        }
        activeSessionKey = sessionKey
        val chinesePredictionEnabled = try {
            AppPrefs.getInstance().input.chinesePrediction.getValue()
        } catch (_: Throwable) {
            activePolicy.chinesePredictionEnabled
        }
        activePolicy = SessionPolicy(
            sessionId = sessionCounter.get(),
            flags = flags,
            chinesePredictionEnabled = chinesePredictionEnabled,
        )
        sessionCounter.get()
    }

    fun invalidateSession(): Long = synchronized(enqueueLock) {
        cancelPendingPredictTasks()
        activeSessionKey = null
        val newSession = sessionCounter.incrementAndGet()
        activePolicy = SessionPolicy(sessionId = newSession, chinesePredictionEnabled = activePolicy.chinesePredictionEnabled)
        _stateFlow.value = EngineState(sessionId = newSession, isFinish = true, engineReady = _stateFlow.value.engineReady)
        newSession
    }

    fun setChinesePredictionEnabled(enabled: Boolean) {
        synchronized(enqueueLock) {
            activePolicy = activePolicy.copy(chinesePredictionEnabled = enabled)
        }
        if (!enabled) {
            cancelPendingTypeAPredict()
            send(EngineAction.DismissPredictions())
        }
    }

    val currentSessionId: Long
        get() = sessionCounter.get()

    fun send(action: EngineAction): Boolean {
        synchronized(enqueueLock) {
            val tracksInput = tracksInput(action)
            if (tracksInput) {
                if (action is EngineAction.NormalKey) cancelPendingTypeAPredict() else cancelPendingPredictTasks()
                pendingActions.incrementAndGet()
            }
            if (action is EngineAction.ClearComposition) {
                _stateFlow.value = _stateFlow.value.copy(
                    showComposition = "",
                    compositionCursorPos = 0,
                    candidates = emptyList(),
                    predictionSources = emptyList(),
                    isFinish = true,
                    stripMode = if (_stateFlow.value.engineReady) CandidateStripMode.EMPTY else CandidateStripMode.UNAVAILABLE,
                )
            }
            val sessionId = sessionCounter.get()
            val enqueuedAtNanos = InputPipelineTrace.mark()
            val queued = QueuedAction(
                sessionId = sessionId,
                action = action,
                sequence = actionSequenceCounter.incrementAndGet(),
                // Avoid trace-only atomic work when the debug trace is disabled.
                traceSequence = if (enqueuedAtNanos == 0L) 0L else traceSequenceCounter.incrementAndGet(),
                enqueuedAtNanos = enqueuedAtNanos,
            )
            val accepted = enqueueQueuedAction(queued)
            if (!accepted && tracksInput) pendingActions.decrementAndGet()
            return accepted
        }
    }

    fun syncImeOptions() {
        val chineseFanTi = AppPrefs.getInstance().input.chineseFanTi.getValue()
        send(EngineAction.SetImeOption("traditionalization", chineseFanTi))
        val emojiInput = AppPrefs.getInstance().input.emojiInput.getValue()
        send(EngineAction.SetImeOption("emoji", emojiInput))
    }

    suspend fun <T> executeExclusive(block: () -> T): T {
        val completion = CompletableDeferred<Any?>()
        synchronized(enqueueLock) {
            invalidateSession()
            val sessionId = sessionCounter.get()
            val enqueuedAtNanos = InputPipelineTrace.mark()
            val queued = QueuedAction(
                sessionId = sessionId,
                action = EngineAction.Maintenance(block),
                completion = completion,
                sequence = actionSequenceCounter.incrementAndGet(),
                // Avoid trace-only atomic work when the debug trace is disabled.
                traceSequence = if (enqueuedAtNanos == 0L) 0L else traceSequenceCounter.incrementAndGet(),
                enqueuedAtNanos = enqueuedAtNanos,
            )
            check(enqueueQueuedAction(queued)) { "Engine pipeline queue is full" }
        }
        @Suppress("UNCHECKED_CAST")
        return completion.await() as T
    }

    private fun cancelPendingPredictTasks() = cancelPendingTypeAPredict()

    private fun enqueueQueuedAction(queued: QueuedAction): Boolean {
        if (deferredDrainJob?.isActive != true && deferredActions.isEmpty() && actionChannel.trySend(queued).isSuccess) {
            traceActionEnqueued(queued)
            return true
        }
        if (deferredActions.size == DEFERRED_QUEUE_CAPACITY) {
            InputPipelineTrace.actionRejected(
                sessionId = queued.sessionId,
                traceSequence = queued.traceSequence,
                generation = queued.action.generation(),
                action = queued.action.traceName(),
                pendingInputActions = pendingActions.get(),
                enqueueStartedAtNanos = queued.enqueuedAtNanos,
            )
            return false
        }
        deferredActions.addLast(queued)
        InputPipelineTrace.actionDeferred(
            sessionId = queued.sessionId,
            traceSequence = queued.traceSequence,
            generation = queued.action.generation(),
            action = queued.action.traceName(),
            pendingInputActions = pendingActions.get(),
            enqueueStartedAtNanos = queued.enqueuedAtNanos,
        )
        scheduleDeferredDrain()
        return true
    }

    private fun scheduleDeferredDrain() {
        if (deferredDrainJob?.isActive == true) return
        deferredDrainJob = scope.launch {
            while (true) {
                val queued = synchronized(enqueueLock) {
                    deferredActions.pollFirst().also {
                        if (it == null) deferredDrainJob = null
                    }
                } ?: return@launch
                actionChannel.send(queued)
                traceActionEnqueued(queued)
            }
        }
    }

    private fun traceActionEnqueued(queued: QueuedAction) {
        InputPipelineTrace.actionEnqueued(
            sessionId = queued.sessionId,
            traceSequence = queued.traceSequence,
            generation = queued.action.generation(),
            action = queued.action.traceName(),
            pendingInputActions = pendingActions.get(),
            enqueueStartedAtNanos = queued.enqueuedAtNanos,
        )
    }

    private fun cancelPendingTypeAPredict() {
        predictSequence++
        associationJob?.cancel()
        associationJob = null
    }

    private fun tracksInput(action: EngineAction): Boolean = when (action) {
        is EngineAction.NormalKey,
        is EngineAction.DeleteKey,
        is EngineAction.SpaceKey,
        is EngineAction.EnterKey,
        is EngineAction.MoveCompositionCursor,
        is EngineAction.StepCompositionCursor,
        is EngineAction.CommitThenKey,
        is EngineAction.CommitThenText,
        is EngineAction.SelectCandidate,
        is EngineAction.DeleteCandidate,
        is EngineAction.SelectPrefix,
        is EngineAction.SelectAssociation,
        is EngineAction.LoadMoreExpandedCandidates -> true
        else -> false
    }

    private suspend fun processAction(queued: QueuedAction) {
        val sessionId = queued.sessionId
        val action = queued.action
        val completion = queued.completion
        val startedAtNanos = InputPipelineTrace.mark()
        try {
            when (action) {
                is EngineAction.NormalKey -> {
                    cancelPendingTypeAPredict()
                    val commit = RimeEngine.onNormalKey(action.event)
                    emitRimeSnapshot(sessionId, commit)
                    publishCurrentState(sessionId, gen = action.gen)
                    if (!commit.isNullOrEmpty()) {
                        launchTypeAForCommit(sessionId, commit, action.gen)
                    }
                }
                is EngineAction.DeleteKey -> {
                    cancelPendingPredictTasks()
                    if (RimeEngine.isFinish()) {
                        emitEvent(EngineEvent.SendKeyEvent(sessionId, KeyEvent.KEYCODE_DEL))
                    } else {
                        val commit = RimeEngine.onDeleteKey()
                        emitRimeSnapshot(sessionId, commit)
                        publishCurrentState(sessionId, gen = action.gen)
                        if (!commit.isNullOrEmpty()) {
                            launchTypeAForCommit(sessionId, commit, action.gen)
                        }
                    }
                }
                is EngineAction.SpaceKey -> {
                    cancelPendingPredictTasks()
                    processSpaceKey(sessionId, action.gen)
                }
                is EngineAction.EnterKey -> {
                    cancelPendingPredictTasks()
                    processEnterKey(sessionId, action.gen)
                }
                is EngineAction.MoveCompositionCursor -> {
                    if (!RimeEngine.isFinish()) {
                        RimeEngine.moveCompositionCursor(action.targetIndex)
                        emitRimeSnapshot(sessionId, null)
                        publishCurrentState(sessionId, gen = action.gen)
                    }
                }
                is EngineAction.StepCompositionCursor -> {
                    if (!RimeEngine.isFinish()) {
                        RimeEngine.stepCompositionCursor(action.direction)
                        emitRimeSnapshot(sessionId, null)
                        publishCurrentState(sessionId, gen = action.gen)
                    }
                }
                is EngineAction.CommitThenKey -> processCommitOrForward(sessionId, action.keyCode, action.gen)
                is EngineAction.CommitThenText -> processCommitThenText(sessionId, action.text, action.moveCursorLeft, action.gen)
                is EngineAction.SelectCandidate -> {
                    cancelPendingPredictTasks()
                    if (!RimeEngine.isFinish() || _stateFlow.value.isAssociate) {
                        val state = _stateFlow.value
                        val predictionSource = state.predictionSources.getOrNull(action.index)
                        if (predictionSource != null) {
                            val commit = state.candidates.getOrNull(action.index)?.text.orEmpty()
                            RimeEngine.reset()
                            publishCurrentState(sessionId, gen = action.gen)
                            if (commit.isNotEmpty()) {
                                recordDecodedCommit(sessionId, commit)
                                emitEvent(EngineEvent.CommitText(sessionId, commit))
                            }
                            return
                        }
                        val commit = RimeEngine.selectCandidate(action.index, action.pageNo)
                        emitRimeSnapshot(sessionId, commit)
                        publishCurrentState(sessionId, gen = action.gen)
                        if (!commit.isNullOrEmpty()) {
                            val canPredict = canPredict()
                            if (canPredict) {
                                recordDecodedCommit(sessionId, commit)
                            }
                            if (canPredict && RimeEngine.isFinish()) {
                                launchPredict(sessionId, commit, action.gen)
                            }
                        }
                    }
                }
                is EngineAction.SelectPrefix -> {
                    cancelPendingPredictTasks()
                    RimeEngine.selectPinyin(action.index)
                    emitRimeSnapshot(sessionId, null)
                    publishCurrentState(sessionId, gen = action.gen)
                }
                is EngineAction.SelectAssociation -> {
                    cancelPendingPredictTasks()
                    val commit = RimeEngine.selectAssociation(action.index)
                    RimeEngine.reset()
                    emitRimeSnapshot(sessionId, null)
                    recordDecodedCommit(sessionId, commit)
                    publishCurrentState(sessionId, gen = action.gen)
                    if (commit.isNotEmpty()) {
                        emitEvent(EngineEvent.CommitText(sessionId, commit))
                    }
                }
                is EngineAction.ApplyAssociation -> {
                    val matches = PredictRequestValidator.acceptsTypeA(
                        requestSessionId = action.sessionId,
                        currentSessionId = sessionCounter.get(),
                        requestSequence = action.seq,
                        currentSequence = predictSequence,
                        raw = RimeEngine.rawCompositionText(),
                        isSymbolPanel = isSymbolPanel(),
                    ) && canPredict()
                    if (matches && action.candidates.isNotEmpty()) {
                        RimeEngine.setAssociationCandidates(action.candidates)
                        publishState(EngineState(
                            candidates = action.candidates,
                            predictionSources = action.predictionSources.map { it },
                            showComposition = "",
                            prefixs = emptyList(),
                            isFinish = true,
                            isAssociate = true,
                            stripMode = CandidateStripMode.PREDICT,
                            currentSchema = RimeEngine.getCurrentRimeSchema(),
                            gen = action.gen,
                            sessionId = action.sessionId,
                            engineReady = _stateFlow.value.engineReady,
                        ))
                    }
                }
                is EngineAction.RemovePredictionCandidate -> {
                    val current = _stateFlow.value
                    val source = current.predictionSources.getOrNull(action.index)
                    val candidate = current.candidates.getOrNull(action.index)
                    if (source != null && candidate != null && PredictEngine.removeUserPrediction(source, candidate.text)) {
                        val candidates = current.candidates.toMutableList().apply { removeAt(action.index) }
                        val sources = current.predictionSources.toMutableList().apply { removeAt(action.index) }
                        if (current.isAssociate) RimeEngine.setAssociationCandidates(candidates)
                        publishState(current.copy(candidates = candidates, predictionSources = sources))
                    }
                }
                is EngineAction.DeleteCandidate -> {
                    cancelPendingPredictTasks()
                    RimeEngine.deleteCandidate(action.index)
                    emitRimeSnapshot(sessionId, null)
                    publishCurrentState(sessionId, gen = action.gen)
                }
                is EngineAction.SelectSchema -> {
                    cancelPendingPredictTasks()
                    RimeEngine.selectSchema(action.schema)
                    val inputPrefs = AppPrefs.getInstance().input
                    applyImeOption("traditionalization", inputPrefs.chineseFanTi.getValue())
                    applyImeOption("emoji", inputPrefs.emojiInput.getValue())
                    emitRimeSnapshot(sessionId, null)
                    publishCurrentState(sessionId, gen = action.gen)
                }
                is EngineAction.SetImeOption -> {
                    applyImeOption(action.option, action.value)
                    emitRimeSnapshot(sessionId, null)
                    publishCurrentState(sessionId, gen = action.gen)
                }
                is EngineAction.SetCharCase -> {
                    RimeEngine.setCharCase(action.charCase)
                    publishState(_stateFlow.value.copy(
                        candidates = snapshotCandidates(),
                        showComposition = RimeEngine.showComposition,
                        charCase = action.charCase,
                        gen = action.gen,
                        sessionId = sessionId,
                    ))
                }
                is EngineAction.LoadMoreExpandedCandidates -> {
                    if (action.pageNo != RimeEngine.candidatePageNo) return
                    val expandedCandidates = RimeEngine.getNextExpandedCandidates()
                    if (expandedCandidates.isNotEmpty()) {
                        emitRimeSnapshot(sessionId, null)
                        publishState(_stateFlow.value.copy(
                            candidates = expandedCandidates,
                            predictionSources = List(expandedCandidates.size) { null },
                            candidatePageNo = RimeEngine.candidatePageNo,
                            hasNextCandidatePage = RimeEngine.hasNextCandidatePage,
                            gen = action.gen,
                            sessionId = sessionId,
                        ))
                    }
                }
                is EngineAction.ClearComposition -> {
                    cancelPendingPredictTasks()
                    try {
                        RimeEngine.reset()
                        emitRimeSnapshot(sessionId, null)
                        publishCurrentState(sessionId, gen = action.gen)
                    } finally {
                        action.modalRequestId?.let { requestId ->
                            emitEvent(EngineEvent.CompositionCleared(sessionId, requestId))
                        }
                    }
                }
                is EngineAction.ApplyEditorPolicy -> {
                    cancelPendingPredictTasks()
                    RimeEngine.setUserDictWritesEnabled(!activePolicy.flags.isPrivateOrSensitive)
                    if (action.clearComposition) {
                        PredictEngine.resetContext()
                    }
                    val ready = RimeEngine.ensureStarted()
                    if (!ready) {
                        publishState(EngineState(
                            isFinish = true,
                            stripMode = CandidateStripMode.UNAVAILABLE,
                            sessionId = sessionId,
                            gen = action.gen,
                            engineReady = false,
                        ))
                        return
                    }
                    if (action.clearComposition) {
                        RimeEngine.reset()
                    }
                    if (action.schema.isNotEmpty()) {
                        if (!RimeEngine.selectSchema(action.schema)) {
                            publishCurrentState(sessionId, gen = action.gen, engineReady = false)
                            return
                        }
                    }
                    val inputPrefs = runCatching { AppPrefs.getInstance().input }.getOrNull()
                    applyImeOption("traditionalization", inputPrefs?.chineseFanTi?.getValue() == true)
                    applyImeOption("emoji", inputPrefs?.emojiInput?.getValue() == true)
                    RimeEngine.setCharCase(action.charCase)
                    emitRimeSnapshot(sessionId, null)
                    publishCurrentState(sessionId, gen = action.gen, engineReady = true)
                }
                is EngineAction.DismissPredictions -> {
                    cancelPendingTypeAPredict()
                    val current = _stateFlow.value
                    if (current.stripMode == CandidateStripMode.PREDICT || current.isAssociate) {
                        RimeEngine.setAssociationCandidates(emptyList())
                        publishState(EngineState(
                            isFinish = true,
                            stripMode = CandidateStripMode.EMPTY,
                            currentSchema = RimeEngine.getCurrentRimeSchema(),
                            charCase = current.charCase,
                            sessionId = sessionId,
                            gen = action.gen,
                            engineReady = current.engineReady,
                        ))
                    }
                }
                is EngineAction.Reset -> {
                    cancelPendingPredictTasks()
                    RimeEngine.reset()
                    PredictEngine.resetContext()
                    emitRimeSnapshot(sessionId, null)
                    publishState(EngineState(
                        currentSchema = RimeEngine.getCurrentRimeSchema(),
                        gen = action.gen,
                        sessionId = sessionId,
                        engineReady = _stateFlow.value.engineReady,
                    ))
                }
                is EngineAction.Destroy -> {
                    cancelPendingPredictTasks()
                    RimeEngine.destroy()
                    emitRimeSnapshot(sessionId, null, composingText = "")
                    publishState(EngineState(sessionId = sessionId, engineReady = false))
                }
                is EngineAction.Maintenance<*> -> {
                    completion?.complete(action.block())
                }
            }
        } finally {
            InputPipelineTrace.rimeProcessed(
                sessionId = sessionId,
                traceSequence = queued.traceSequence,
                generation = action.generation(),
                action = action.traceName(),
                startedAtNanos = startedAtNanos,
            )
        }
    }

    private suspend fun processCommitOrForward(sessionId: Long, keyCode: Int, gen: Long) {
        val state = _stateFlow.value
        if (!RimeEngine.isFinish() && !state.isAssociate) {
            val decodedCommit = if (!RimeEngine.isEnglishSchema() && RimeEngine.showCandidates.isNotEmpty()) {
                RimeEngine.selectCandidate(0).orEmpty()
            } else {
                ""
            }
            val commit = decodedCommit + RimeEngine.rawCompositionText()
            RimeEngine.reset()
            emitRimeSnapshot(sessionId, commit)
            publishCurrentState(sessionId, gen = gen)
            recordDecodedCommit(sessionId, decodedCommit)
        } else {
            emitEvent(EngineEvent.SendKeyEvent(sessionId, keyCode))
        }
    }

    private suspend fun processEnterKey(sessionId: Long, gen: Long) {
        val state = _stateFlow.value
        if (!RimeEngine.isFinish() && !state.isAssociate) {
            val composition = RimeEngine.rawCompositionText()
            RimeEngine.reset()
            emitRimeSnapshot(sessionId, composition)
            publishCurrentState(sessionId, gen = gen)
        } else {
            emitEvent(EngineEvent.SendKeyEvent(sessionId, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun launchPredict(sessionId: Long, text: String, gen: Long) {
        cancelPendingTypeAPredict()
        val currentSession = sessionId
        val currentSeq = ++predictSequence
        val previousWord = PredictEngine.getSecondLastWord()
        associationJob = scope.launch(PredictDispatcher.dispatcher) {
            if (currentSession != sessionCounter.get() || currentSeq != predictSequence) {
                return@launch
            }
            val cands = if (text.isNotEmpty()) {
                PredictEngine.predictTypeACandidates(text, previousWord)
            } else {
                emptyList()
            }
            if (currentSession != sessionCounter.get() || currentSeq != predictSequence) {
                return@launch
            }
            send(EngineAction.ApplyAssociation(
                sessionId = currentSession,
                seq = currentSeq,
                candidates = cands.map { CandidateListItem("", it.text) },
                predictionSources = cands.map { it.source },
                gen = gen,
            ))
        }
    }

    private fun launchTypeAForCommit(sessionId: Long, text: String, gen: Long) {
        if (text.isEmpty() || !belongsToActiveSession(sessionId) || !canPredict()) return
        PredictEngine.recordCommit(text)
        if (!RimeEngine.isFinish() || isSymbolPanel()) return
        launchPredict(sessionId, text, gen)
    }

    private fun recordDecodedCommit(sessionId: Long, text: String) {
        if (text.isEmpty() || !belongsToActiveSession(sessionId) || !canPredict()) return
        PredictEngine.recordCommit(text)
    }

    private fun belongsToActiveSession(sessionId: Long): Boolean =
        sessionId == sessionCounter.get() && sessionId == activePolicy.sessionId

    private fun canPredict(): Boolean =
        activePolicy.chinesePredictionEnabled &&
            activePolicy.flags.allowsChinesePostCommitPrediction &&
            !RimeEngine.isEnglishSchema()

    private fun isSymbolPanel(): Boolean = KeyboardManager.instance.currentContainer is SymbolContainer

    private suspend fun processSpaceKey(sessionId: Long, gen: Long) {
        val state = _stateFlow.value
        if (!RimeEngine.isFinish() && !state.isAssociate) {
            val hasCandidates = RimeEngine.showCandidates.isNotEmpty()
            val composition = if (RimeEngine.isEnglishSchema()) {
                RimeEngine.rawCompositionText()
            } else if (hasCandidates) {
                RimeEngine.selectCandidate(0).orEmpty()
            } else {
                RimeEngine.rawCompositionText()
            }
            // A selection may confirm only one segment; keep the remaining composition.
            if (RimeEngine.isEnglishSchema() || !hasCandidates || RimeEngine.isFinish()) {
                RimeEngine.reset()
            }
            emitRimeSnapshot(sessionId, composition)
            publishCurrentState(sessionId, gen = gen)
            if (composition.isNotEmpty()) {
                val shouldPredict = canPredict() && hasCandidates
                if (shouldPredict) {
                    recordDecodedCommit(sessionId, composition)
                }
                if (shouldPredict) {
                    launchPredict(sessionId, composition, gen)
                }
            }
        } else if (state.isAssociate && RimeEngine.showCandidates.isNotEmpty()) {
            val commit = RimeEngine.selectAssociation(0)
            RimeEngine.reset()
            recordDecodedCommit(sessionId, commit)
            publishCurrentState(sessionId, gen = gen)
            if (commit.isNotEmpty()) {
                emitEvent(EngineEvent.CommitText(sessionId, commit))
            }
        } else {
            emitEvent(EngineEvent.SendKeyEvent(sessionId, KeyEvent.KEYCODE_SPACE))
        }
    }

    private suspend fun processCommitThenText(sessionId: Long, text: String, moveCursorLeft: Boolean, gen: Long) {
        if (!RimeEngine.isFinish() && !_stateFlow.value.isAssociate) {
            val decodedCommit = if (!RimeEngine.isEnglishSchema() && RimeEngine.showCandidates.isNotEmpty()) {
                RimeEngine.selectCandidate(0).orEmpty()
            } else {
                ""
            }
            val composition = decodedCommit + RimeEngine.rawCompositionText()
            RimeEngine.reset()
            emitRimeSnapshot(sessionId, composition)
            publishCurrentState(sessionId, gen = gen)
            recordDecodedCommit(sessionId, decodedCommit)
        }
        emitEvent(EngineEvent.CommitText(sessionId, text))
        if (moveCursorLeft) {
            emitEvent(EngineEvent.SendKeyEvent(sessionId, KeyEvent.KEYCODE_DPAD_LEFT, 300L))
        }
    }

    private suspend fun publishCurrentState(
        sessionId: Long,
        gen: Long = 0L,
        traceSequence: Long = activeTraceSequence,
        engineReady: Boolean = _stateFlow.value.engineReady,
    ) {
        val passwordField = activePolicy.flags.isPassword && activePolicy.sessionId == sessionId
        val candidates = if (passwordField) emptyList() else snapshotCandidates()
        val composition = if (passwordField) "" else RimeEngine.showComposition
        val cursorPos = if (passwordField) 0 else RimeEngine.compositionCursorPos
        val prefixs = if (passwordField) emptyList() else RimeEngine.getPrefixs().toList()
        val isFinish = RimeEngine.isFinish()
        val currentSchema = RimeEngine.getCurrentRimeSchema()
        val stripMode = when {
            !engineReady -> CandidateStripMode.UNAVAILABLE
            passwordField -> CandidateStripMode.EMPTY
            !isFinish || composition.isNotEmpty() -> CandidateStripMode.DECODING
            else -> CandidateStripMode.EMPTY
        }

        val published = publishState(EngineState(
            candidates = candidates,
            predictionSources = List(candidates.size) { null },
            showComposition = composition,
            compositionCursorPos = cursorPos,
            prefixs = prefixs,
            t9LockedCount = if (passwordField) 0 else RimeEngine.t9LockedCount,
            isFinish = isFinish,
            isAssociate = false,
            stripMode = stripMode,
            currentSchema = currentSchema,
            candidatePageNo = if (passwordField) 0 else RimeEngine.candidatePageNo,
            hasNextCandidatePage = if (passwordField) false else RimeEngine.hasNextCandidatePage,
            gen = gen,
            sessionId = sessionId,
            traceSequence = traceSequence,
            engineReady = engineReady,
        ))
        if (published) {
            InputPipelineTrace.snapshotPublished(
                sessionId = sessionId,
                traceSequence = traceSequence,
                generation = gen,
                candidateCount = candidates.size,
                preeditLength = composition.length,
            )
        }
    }

    private fun applyImeOption(option: String, value: Boolean) {
        if (value) {
            when (option) {
                "traditionalization" -> Rime.ensureOpencc("s2t.json")
                "emoji" -> Rime.ensureOpencc("emoji.json")
            }
        }
        RimeEngine.setImeOption(option, value)
    }

    private fun snapshotCandidates(): List<CandidateListItem> =
        RimeEngine.showCandidates.map { CandidateListItem(it.comment, it.text) }

    private suspend fun publishState(state: EngineState): Boolean {
        val snapshot = if (state.engineReady) state else state.copy(stripMode = CandidateStripMode.UNAVAILABLE)
        val accepted = synchronized(enqueueLock) {
            if (state.sessionId != sessionCounter.get()) return@synchronized false
            _stateFlow.value = snapshot
            true
        }
        if (accepted) {
            _updates.emit(SessionUpdate.State(snapshot))
        }
        return accepted
    }

    private suspend fun emitEvent(event: EngineEvent) {
        _updates.emit(SessionUpdate.Event(event))
    }

    private suspend fun emitRimeSnapshot(
        sessionId: Long,
        committedText: String?,
        traceSequence: Long = activeTraceSequence,
        composingText: String = RimeEngine.editorCompositionText(),
    ) {
        val hideEditorComposition = activePolicy.flags.isPassword && activePolicy.sessionId == sessionId
        emitEvent(EngineEvent.ApplyRimeSnapshot(
            sessionId = sessionId,
            committedText = committedText,
            composingText = if (hideEditorComposition) "" else composingText,
            sequence = activeActionSequence,
            traceSequence = traceSequence,
        ))
    }

    private fun EngineAction.generation(): Long = when (this) {
        is EngineAction.NormalKey -> gen
        is EngineAction.DeleteKey -> gen
        is EngineAction.SpaceKey -> gen
        is EngineAction.EnterKey -> gen
        is EngineAction.MoveCompositionCursor -> gen
        is EngineAction.StepCompositionCursor -> gen
        is EngineAction.CommitThenKey -> gen
        is EngineAction.CommitThenText -> gen
        is EngineAction.SelectCandidate -> gen
        is EngineAction.DeleteCandidate -> gen
        is EngineAction.SelectPrefix -> gen
        is EngineAction.SelectAssociation -> gen
        is EngineAction.ApplyAssociation -> gen
        is EngineAction.SelectSchema -> gen
        is EngineAction.SetImeOption -> gen
        is EngineAction.SetCharCase -> gen
        is EngineAction.LoadMoreExpandedCandidates -> gen
        is EngineAction.ClearComposition -> gen
        is EngineAction.ApplyEditorPolicy -> gen
        is EngineAction.DismissPredictions -> gen
        is EngineAction.Reset -> gen
        is EngineAction.RemovePredictionCandidate,
        is EngineAction.Destroy,
        is EngineAction.Maintenance<*> -> 0L
    }

    private fun EngineAction.traceName(): String = when (this) {
        is EngineAction.NormalKey -> "normal_key"
        is EngineAction.DeleteKey -> "delete_key"
        is EngineAction.SpaceKey -> "space_key"
        is EngineAction.EnterKey -> "enter_key"
        is EngineAction.MoveCompositionCursor -> "move_composition_cursor"
        is EngineAction.StepCompositionCursor -> "step_composition_cursor"
        is EngineAction.CommitThenKey -> "commit_then_key"
        is EngineAction.CommitThenText -> "commit_then_text"
        is EngineAction.SelectCandidate -> "select_candidate"
        is EngineAction.DeleteCandidate -> "delete_candidate"
        is EngineAction.SelectPrefix -> "select_prefix"
        is EngineAction.SelectAssociation -> "select_association"
        is EngineAction.ApplyAssociation -> "apply_association"
        is EngineAction.RemovePredictionCandidate -> "remove_prediction_candidate"
        is EngineAction.SelectSchema -> "select_schema"
        is EngineAction.SetImeOption -> "set_ime_option"
        is EngineAction.SetCharCase -> "set_char_case"
        is EngineAction.LoadMoreExpandedCandidates -> "load_more_expanded_candidates"
        is EngineAction.ClearComposition -> "clear_composition"
        is EngineAction.ApplyEditorPolicy -> "apply_editor_policy"
        is EngineAction.DismissPredictions -> "dismiss_predictions"
        is EngineAction.Reset -> "reset"
        is EngineAction.Destroy -> "destroy"
        is EngineAction.Maintenance<*> -> "maintenance"
    }

    private const val INPUT_QUEUE_CAPACITY = 64
    private const val DEFERRED_QUEUE_CAPACITY = 64
}
