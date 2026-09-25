package org.bitfennec.lime.service

import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.inputmethod.EngineState
import org.bitfennec.lime.inputmethod.predict.CandidateStripMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodingInfoStateTest {

    @Test
    fun decodingStateKeepsRimeCandidatesWithoutOptimisticRaw() {
        val candidates = listOf(CandidateListItem("ni hao", "你好"))

        val visible = candidatesFromEngineState(
            EngineState(
                candidates = candidates,
                stripMode = CandidateStripMode.DECODING,
                showComposition = "",
            )
        )

        assertEquals(candidates, visible)
    }

    @Test
    fun predictStateKeepsPredictionCandidates() {
        val candidates = listOf(CandidateListItem("", "世界"))

        val visible = candidatesFromEngineState(
            EngineState(
                candidates = candidates,
                stripMode = CandidateStripMode.PREDICT,
            )
        )

        assertEquals(candidates, visible)
    }

    @Test
    fun unavailableStateHidesCandidates() {
        val visible = candidatesFromEngineState(
            EngineState(
                candidates = listOf(CandidateListItem("", "残留")),
                stripMode = CandidateStripMode.UNAVAILABLE,
                engineReady = false,
            )
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun emptyStateClearsCandidates() {
        val visible = candidatesFromEngineState(
            EngineState(
                candidates = listOf(CandidateListItem("", "残留")),
                stripMode = CandidateStripMode.EMPTY,
            )
        )

        assertTrue(visible.isEmpty())
    }
}
