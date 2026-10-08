package org.bitfennec.lime.keyboard.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test validating container scroll-to-top reset policy and state transitions.
 */
class ContainerScrollResetTest {

    @Test
    fun testCandidateExpansionResetScrollPolicy() {
        // 1. Initial / re-open state: freshly opened container must trigger reset even if composition text is unchanged
        val shouldResetOnOpen = CandidatesContainer.shouldResetCandidateScroll(
            pendingScrollToTopOnOpen = true,
            currentFirstText = "测试",
            lastFirstText = "测试"
        )
        assertTrue("Freshly opened candidate container must reset scroll to top", shouldResetOnOpen)

        // 2. Pagination scenario: container remains open, user scrolls, page 2 appended with same first candidate
        val shouldResetOnPagination = CandidatesContainer.shouldResetCandidateScroll(
            pendingScrollToTopOnOpen = false,
            currentFirstText = "测试",
            lastFirstText = "测试"
        )
        assertFalse("Pagination in an open container must NOT reset scroll to top", shouldResetOnPagination)

        // 3. New composition scenario: user types new character changing top candidate
        val shouldResetOnTextChange = CandidatesContainer.shouldResetCandidateScroll(
            pendingScrollToTopOnOpen = false,
            currentFirstText = "测定",
            lastFirstText = "测试"
        )
        assertTrue("New composition must reset scroll to top", shouldResetOnTextChange)

        // 4. First candidate remains null / empty list
        val shouldResetNull = CandidatesContainer.shouldResetCandidateScroll(
            pendingScrollToTopOnOpen = false,
            currentFirstText = null,
            lastFirstText = null
        )
        assertFalse("Unchanged null candidates must not reset scroll", shouldResetNull)
    }
}
