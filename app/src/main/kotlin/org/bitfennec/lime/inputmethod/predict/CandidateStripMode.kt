package org.bitfennec.lime.inputmethod.predict

/**
 * Operating mode of the candidate / suggestion bar.
 */
enum class CandidateStripMode {
    EMPTY,     // Idle state / no candidates
    DECODING,  // Composing (has raw pinyin, displays decoded candidates)
    PREDICT,   // Prediction mode (no raw pinyin, displays next-word suggestions)
    UNAVAILABLE, // Engine assets or Rime startup failed
}
