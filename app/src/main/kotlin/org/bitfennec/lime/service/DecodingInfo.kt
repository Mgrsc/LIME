package org.bitfennec.lime.service

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bitfennec.lime.core.CandidateKind
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.inputmethod.EngineAction
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.inputmethod.EngineState
import org.bitfennec.lime.inputmethod.RimeEngine
import org.bitfennec.lime.inputmethod.SessionUpdate
import org.bitfennec.lime.inputmethod.predict.CandidateStripMode

internal fun candidatesFromEngineState(state: EngineState): List<CandidateListItem> =
    if (state.stripMode == CandidateStripMode.EMPTY ||
        state.stripMode == CandidateStripMode.UNAVAILABLE
    ) {
        emptyList()
    } else {
        state.candidates
    }

data class CandidateSelection(
    val text: String,
    val modalSessionId: Long? = null,
)

/**
 * Decoding state holder (reactive state holder).
 * Consumes snapshot states published by background Rime engine.
 */
object DecodingInfo {

    private val _candidatesFlow = MutableStateFlow<List<CandidateListItem>>(emptyList())
    val candidatesFlow: StateFlow<List<CandidateListItem>> = _candidatesFlow.asStateFlow()
    var isAssociate = false
    private var hasExternalCandidates = false
    private var externalModalSessionId: Long? = null
    private var actionGeneration = 0L
    private var requestedMoreState: EngineState? = null

    val hasExternalCandidateSource: Boolean
        get() = hasExternalCandidates

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        observerScope.launch {
            EnginePipeline.updates.collect { update ->
                val state = (update as? SessionUpdate.State)?.state ?: return@collect
                if (state.sessionId != EnginePipeline.currentSessionId) return@collect
                if (hasExternalCandidates) return@collect
                isAssociate = state.isAssociate
                emitCandidates(candidatesFromEngineState(state))
            }
        }
    }

    private fun emitCandidates(cands: List<CandidateListItem>) {
        _candidatesFlow.value = cands
    }

    fun reset(invalidateSession: Boolean = false, resetEngine: Boolean = false) {
        isAssociate = false
        hasExternalCandidates = false
        externalModalSessionId = null
        requestedMoreState = null
        emitCandidates(emptyList())
        if (invalidateSession) EnginePipeline.invalidateSession()
        if (resetEngine) EnginePipeline.send(EngineAction.Reset(gen = nextActionGeneration()))
    }

    val isCandidatesEmpty: Boolean
        get() = _candidatesFlow.value.isEmpty()

    val candidateSize: Int
        get() = _candidatesFlow.value.size

    val candidates: List<CandidateListItem>
        get() = _candidatesFlow.value

    fun inputAction(event: KeyEvent) {
        isAssociate = false
        hasExternalCandidates = false
        EnginePipeline.send(EngineAction.NormalKey(event, gen = nextActionGeneration()))
    }

    fun selectPrefix(position: Int) {
        hasExternalCandidates = false
        EnginePipeline.send(EngineAction.SelectPrefix(position, gen = nextActionGeneration()))
    }

    val prefixs: Array<String>
        get() = EnginePipeline.stateFlow.value.prefixs.toTypedArray()

    fun deleteAction() {
        if (hasExternalCandidates && !isAssociate) {
            reset()
        } else {
            EnginePipeline.send(EngineAction.DeleteKey(gen = nextActionGeneration()))
        }
    }

    fun clearComposition() {
        hasExternalCandidates = false
        isAssociate = false
        emitCandidates(emptyList())
        EnginePipeline.send(EngineAction.ClearComposition(gen = nextActionGeneration()))
    }

    val isEngineFinish: Boolean
        get() = EnginePipeline.stateFlow.value.let { state ->
            state.sessionId != EnginePipeline.currentSessionId || state.isFinish
        }

    val hasRimeComposition: Boolean
        get() = !isEngineFinish || composingStrForDisplay.isNotEmpty()

    val composingStrForDisplay: String
        get() {
            val state = EnginePipeline.stateFlow.value
            return if (state.sessionId == EnginePipeline.currentSessionId) state.showComposition else ""
        }

    fun requestMoreExpandedCandidates() {
        val state = EnginePipeline.stateFlow.value
        if (!hasExternalCandidates && !state.isAssociate && state.hasNextCandidatePage &&
            state !== requestedMoreState
        ) {
            if (EnginePipeline.send(
                    EngineAction.LoadMoreExpandedCandidates(
                        state.candidatePageNo,
                        state.candidateRevision,
                        nextActionGeneration(),
                    )
                )
            ) {
                requestedMoreState = state
            }
        }
    }

    fun chooseDecodingCandidate(
        candId: Int,
        displayedCandidate: CandidateListItem?,
        explicitSelection: Boolean,
    ): CandidateSelection {
        val candidate = displayedCandidate ?: return CandidateSelection("")
        if (hasExternalCandidates) {
            if (_candidatesFlow.value.getOrNull(candId) != candidate) return CandidateSelection("")
            val modalSessionId = externalModalSessionId
            clearExternalCandidates()
            return CandidateSelection(candidate.text, modalSessionId)
        }
        val target = candidate.identity ?: return CandidateSelection("")
        if (target.sessionId != EnginePipeline.currentSessionId) return CandidateSelection("")
        if (target.kind == CandidateKind.PREDICTION) {
            EnginePipeline.send(EngineAction.SelectAssociation(target, gen = nextActionGeneration()))
        } else {
            EnginePipeline.send(EngineAction.SelectCandidate(
                target, explicitSelection = explicitSelection, gen = nextActionGeneration(),
            ))
        }
        return CandidateSelection("")
    }

    fun canRemoveUserPrediction(candidate: CandidateListItem): Boolean {
        val target = candidate.identity ?: return false
        val state = EnginePipeline.stateFlow.value
        if (!state.acceptsCandidate(target)) return false
        return when (state.predictionSources.getOrNull(target.index)) {
            org.bitfennec.lime.inputmethod.predict.PredictionSource.USER_1GRAM,
            org.bitfennec.lime.inputmethod.predict.PredictionSource.USER_2GRAM -> true
            else -> false
        }
    }

    fun removeUserPrediction(candidate: CandidateListItem): Boolean {
        if (!canRemoveUserPrediction(candidate)) return false
        val target = candidate.identity ?: return false
        return EnginePipeline.send(EngineAction.RemovePredictionCandidate(target))
    }

    fun deleteCandidate(candidate: CandidateListItem): Boolean {
        val target = candidate.identity ?: return false
        return EnginePipeline.send(EngineAction.DeleteCandidate(target, gen = nextActionGeneration()))
    }

    fun getCandidateDeletableType(candidate: CandidateListItem): Int {
        val target = candidate.identity ?: return org.bitfennec.lime.core.Rime.DELETABLE_NONE
        if (!EnginePipeline.stateFlow.value.acceptsCandidate(target)) return org.bitfennec.lime.core.Rime.DELETABLE_NONE
        return RimeEngine.getCandidateDeletableType(target.index, target.revision)
    }

    fun nextActionGeneration(): Long = ++actionGeneration

    fun getCandidate(candId: Int): CandidateListItem? {
        return _candidatesFlow.value.getOrNull(candId)
    }

    fun cacheCandidates(
        words: Array<CandidateListItem>,
        associate: Boolean = false,
        modalSessionId: Long? = null,
    ) {
        isAssociate = associate
        hasExternalCandidates = true
        externalModalSessionId = modalSessionId
        val cands = words.asList()
        emitCandidates(cands)
    }

    fun clearModalCandidates(modalSessionId: Long? = null): Boolean {
        if (hasExternalCandidates && (modalSessionId == null || externalModalSessionId == null || externalModalSessionId == modalSessionId)) {
            clearExternalCandidates()
            return true
        }
        return false
    }

    private fun clearExternalCandidates() {
        isAssociate = false
        hasExternalCandidates = false
        externalModalSessionId = null
        emitCandidates(emptyList())
    }
}
