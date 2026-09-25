package org.bitfennec.lime.core

import android.graphics.PointF

/**
 * Contract for handwriting recognition engines.
 *
 * Current backend: PP-OCRv6 line-level CTC recognition (input [1, 3, 48, 320] NCHW, Paddle normalized).
 * Future target: Lightweight online single-character recognition (e.g., [1, 48, 48, 1]).
 */
fun interface HandwritingRecognizer {
    fun recognize(strokes: List<List<PointF>>, callback: (Array<CandidateListItem>) -> Unit)
}
