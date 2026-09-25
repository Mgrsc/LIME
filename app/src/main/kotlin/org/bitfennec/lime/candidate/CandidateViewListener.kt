package org.bitfennec.lime.candidate

import org.bitfennec.lime.prefs.behavior.SkbMenuMode

/**
 * Candidate view listener interface.
 * @ClassName CandidateViewListener
 */
interface CandidateViewListener {
    fun onClickChoice(choiceId: Int)  // Candidate selection handler
    fun onLongClickChoice(choiceId: Int) {}
    fun onClickMore(level: Int)  // Load more candidates
    fun onClickMenu(skbMenuMode: SkbMenuMode)  // Handle menu action
    fun onClickClearCandidate()  // Clear candidates
}
