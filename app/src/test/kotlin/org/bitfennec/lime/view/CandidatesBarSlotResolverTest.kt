package org.bitfennec.lime.view

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidatesBarSlotResolverTest {
    @Test
    fun containerSlotsWinBeforeInputState() {
        assertEquals(
            CandidatesBarSlot.IDLE_TOOLBAR,
            resolve(isClipboardContainer = true, isComposing = true, hasCandidates = true,
                hasClipboardSuggestion = true, hasInlineSuggestions = true)
        )
        assertEquals(
            CandidatesBarSlot.CANDIDATE_ROW,
            resolve(isExpandedContainer = true, isComposing = true, hasCandidates = true)
        )
    }

    @Test
    fun editingKeepsToolbarAndExpandedKeepsCollapseWithoutCandidates() {
        assertEquals(CandidatesBarSlot.IDLE_TOOLBAR, resolve(
            isTextEditContainer = true, hasClipboardSuggestion = true, hasInlineSuggestions = true,
        ))
        assertEquals(CandidatesBarSlot.CANDIDATE_ROW, resolve(isExpandedContainer = true))
    }

    @Test
    fun composingAndCandidatesWinBeforeSuggestions() {
        assertEquals(
            CandidatesBarSlot.CANDIDATE_ROW,
            resolve(isComposing = true, hasClipboardSuggestion = true)
        )
        assertEquals(
            CandidatesBarSlot.CANDIDATE_ROW,
            resolve(hasCandidates = true, hasInlineSuggestions = true)
        )
    }

    @Test
    fun passwordHidesCandidateRow() {
        assertEquals(
            CandidatesBarSlot.IDLE_TOOLBAR,
            resolve(isComposing = true, hasCandidates = true, isPassword = true)
        )
    }

    @Test
    fun privacyHidesPassiveSuggestionSlots() {
        assertEquals(
            CandidatesBarSlot.IDLE_TOOLBAR,
            resolve(
                isPrivateOrSensitive = true,
                hasClipboardSuggestion = true,
                hasInlineSuggestions = true
            )
        )
    }

    @Test
    fun clipboardSuggestionWinsBeforeInlineWhenIdle() {
        assertEquals(
            CandidatesBarSlot.SUGGESTION_PILL,
            resolve(hasClipboardSuggestion = true, hasInlineSuggestions = true)
        )
        assertEquals(
            CandidatesBarSlot.INLINE_AUTOFILL,
            resolve(hasInlineSuggestions = true)
        )
    }

    private fun resolve(
        isClipboardContainer: Boolean = false,
        isExpandedContainer: Boolean = false,
        isTextEditContainer: Boolean = false,
        isComposing: Boolean = false,
        hasCandidates: Boolean = false,
        isPrivateOrSensitive: Boolean = false,
        hasClipboardSuggestion: Boolean = false,
        hasInlineSuggestions: Boolean = false,
        isPassword: Boolean = false,
    ): CandidatesBarSlot =
        CandidatesBarSlotResolver.resolve(
            isClipboardContainer = isClipboardContainer,
            isExpandedContainer = isExpandedContainer,
            isTextEditContainer = isTextEditContainer,
            isComposing = isComposing,
            hasCandidates = hasCandidates,
            isPrivateOrSensitive = isPrivateOrSensitive,
            hasClipboardSuggestion = hasClipboardSuggestion,
            hasInlineSuggestions = hasInlineSuggestions,
            isPassword = isPassword,
        )
}
