package org.bitfennec.lime.keyboard.model

import android.graphics.drawable.Drawable
import org.bitfennec.lime.keyboard.keyIconRecords
import java.util.Objects

/**
 * Toggleable soft key. See [ToggleState].
 */
class SoftKeyToggle(code: Int) : SoftKey(code = code) {
    private var mToggleStates: List<ToggleState>? = null

    fun setToggleStates(toggleStates: List<ToggleState>?) {
        mToggleStates = toggleStates
    }

    /**
     * Switches to toggle state with specified stateId.
     * @return true if state changed, false otherwise.
     */
    fun enableToggleState(stateId: Int): Boolean {
        val oldStateId = super.stateId
        if (oldStateId == stateId) return false
        super.stateId = stateId
        return true
    }

    override val keyIcon: Drawable?
        get() {
            val state = toggleState
            return if (null != state) {
                keyIconRecords[Objects.hash(code, stateId)]
            } else super.keyIcon
        }

    override val keyLabel: String
        get() = toggleState?.label ?: super.keyLabel

    private val toggleState: ToggleState?
        get() {
            val states = mToggleStates ?: return null
            for (state in states) {
                if (state.stateId == stateId) {
                    return state
                }
            }
            return null
        }
}
