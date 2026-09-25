package org.bitfennec.lime.keyboard

data class ImeLayoutSnapshot(
    val windowWidth: Int,
    val windowHeight: Int,
    val inputLeft: Int,
    val inputTop: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val isFloating: Boolean,
    val orientation: Int,
    val density: Float,
    val snapshotId: Long = 0L,
) {
    companion object {
        val Empty = ImeLayoutSnapshot(0, 0, 0, 0, 0, 0, false, 0, 0f, 0L)
    }
}
