package org.bitfennec.lime.candidate

import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.prefs.behavior.SkbMenuMode

/**
 * Candidate view listener interface.
 * @ClassName CandidateViewListener
 */
interface CandidateViewListener {
    fun onClickChoice(choiceId: Int, candidate: CandidateListItem)  // Candidate selection handler
    fun onLongClickChoice(choiceId: Int, candidate: CandidateListItem) {}
    fun onClickMore(level: Int)  // Load more candidates
    fun onClickMenu(skbMenuMode: SkbMenuMode)  // Handle menu action
    fun onClickClearCandidate()  // Clear candidates
}
