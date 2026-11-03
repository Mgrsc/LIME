package org.bitfennec.lime.inputmethod.voice.ui

import kotlin.math.min

internal data class VoiceOverlayLayout(
    val targetTop: Float,
    val previewBaseline: Float,
    val hintBaseline: Float,
    val modeBaseline: Float,
    val anchorTop: Float,
    val anchorHeight: Float,
    val lockedStatusBaseline: Float,
    val lockedPreviewBaseline: Float,
    val lockedModeBaseline: Float,
    val finishTop: Float,
    val waveformTop: Float
) {
    companion object {
        const val TARGET_HEIGHT_DP = 38f
        const val FINISH_HEIGHT_DP = 44f
        const val MODE_HALF_HEIGHT_DP = 14f

        fun calculate(height: Float): VoiceOverlayLayout {
            val anchorHeight = min(44f, height * 0.16f)
            val anchorTop = min(182f, height - 8f - anchorHeight)
            val modeBaseline = min(162f, anchorTop - 18f)
            val hintBaseline = min(142f, modeBaseline - 20f)
            val previewBaseline = hintBaseline - 16f
            val targetTop = min(76f, previewBaseline - 16f - TARGET_HEIGHT_DP)
            val finishTop = min(165f, height - 8f - FINISH_HEIGHT_DP)
            val lockedModeBaseline = min(138f, finishTop - 22f)
            val lockedPreviewBaseline = min(116f, lockedModeBaseline - 24f)
            val lockedStatusBaseline = min(76f, lockedPreviewBaseline - 24f)
            val waveformTop = min(28f, min(targetTop - 28f, lockedStatusBaseline - 48f)).coerceAtLeast(4f)
            return VoiceOverlayLayout(
                targetTop, previewBaseline, hintBaseline, modeBaseline, anchorTop, anchorHeight,
                lockedStatusBaseline, lockedPreviewBaseline, lockedModeBaseline, finishTop, waveformTop
            )
        }
    }
}
