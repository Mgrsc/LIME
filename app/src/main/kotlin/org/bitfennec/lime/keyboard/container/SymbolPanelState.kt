package org.bitfennec.lime.keyboard.container

import org.bitfennec.lime.data.emojicon.EmojiconData
import org.bitfennec.lime.prefs.behavior.SymbolMode

internal data class SymbolPanelState(
    val editorSessionId: Long,
    val mode: SymbolMode,
    val symbolCategory: EmojiconData.SymbolCategory,
    val emojiCategory: Int?,
    val moreCategory: EmojiconData.SymbolMoreSubCategory,
    val lockedByUser: Boolean
) {
    fun canRestore(currentSessionId: Long, isRestricted: Boolean): Boolean =
        !isRestricted && editorSessionId == currentSessionId
}
