package org.bitfennec.lime.view

enum class CandidatesBarSlot {
    IDLE_TOOLBAR,
    CANDIDATE_ROW,
    INLINE_AUTOFILL,
    SUGGESTION_PILL,
}

object CandidatesBarSlotResolver {
    fun resolve(
        isClipboardContainer: Boolean,
        isExpandedContainer: Boolean,
        isTextEditContainer: Boolean,
        isComposing: Boolean,
        hasCandidates: Boolean,
        isPrivateOrSensitive: Boolean,
        hasClipboardSuggestion: Boolean,
        hasInlineSuggestions: Boolean,
        isPassword: Boolean = false,
    ): CandidatesBarSlot {
        if (isClipboardContainer || isTextEditContainer) return CandidatesBarSlot.IDLE_TOOLBAR
        if (isExpandedContainer) return CandidatesBarSlot.CANDIDATE_ROW
        if (isPassword) return CandidatesBarSlot.IDLE_TOOLBAR
        if (isComposing || hasCandidates) return CandidatesBarSlot.CANDIDATE_ROW
        if (!isPrivateOrSensitive && hasClipboardSuggestion) return CandidatesBarSlot.SUGGESTION_PILL
        if (!isPrivateOrSensitive && hasInlineSuggestions) return CandidatesBarSlot.INLINE_AUTOFILL
        return CandidatesBarSlot.IDLE_TOOLBAR
    }
}
