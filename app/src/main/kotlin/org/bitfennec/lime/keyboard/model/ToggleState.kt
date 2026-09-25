package org.bitfennec.lime.keyboard.model

/**
 * Toggleable key state.
 */
class ToggleState {
    var stateId = 0
    var label: String = ""

    constructor(stateId: Int) {
        this.stateId = stateId
    }

    constructor(keyLabel: String, stateId: Int) {
        this.stateId = stateId
        label = keyLabel
    }
}
