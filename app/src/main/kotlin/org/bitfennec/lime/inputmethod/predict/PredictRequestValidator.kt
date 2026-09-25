package org.bitfennec.lime.inputmethod.predict

internal object PredictRequestValidator {
    fun acceptsTypeA(
        requestSessionId: Long,
        currentSessionId: Long,
        requestSequence: Long,
        currentSequence: Long,
        raw: String,
        isSymbolPanel: Boolean,
    ): Boolean = requestSessionId == currentSessionId &&
        requestSequence == currentSequence &&
        raw.isEmpty() &&
        !isSymbolPanel

}
