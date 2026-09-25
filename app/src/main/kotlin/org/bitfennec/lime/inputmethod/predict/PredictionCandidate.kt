package org.bitfennec.lime.inputmethod.predict

enum class PredictionSource {
    SYSTEM,
    USER_1GRAM,
    USER_2GRAM,
}

data class PredictionCandidate(
    val text: String,
    val source: PredictionSource,
)
