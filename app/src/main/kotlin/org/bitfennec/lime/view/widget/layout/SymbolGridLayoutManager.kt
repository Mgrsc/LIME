package org.bitfennec.lime.view.widget.layout

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.utils.calculateGridSpanCount

internal class SymbolGridLayoutManager(context: Context) : GridLayoutManager(context, 1) {
    private var minimumItemWidth = 0f
    private var keepSymbolPairsTogether = false

    fun setMinimumItemWidth(width: Float, keepPairs: Boolean) {
        if (minimumItemWidth == width && keepSymbolPairsTogether == keepPairs) return
        minimumItemWidth = width
        keepSymbolPairsTogether = keepPairs
        requestLayout()
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        if (state?.isPreLayout != true && width > 0 && minimumItemWidth > 0f) {
            val availableColumns = calculateGridSpanCount(width - paddingLeft - paddingRight, minimumItemWidth)
            val columns = if (keepSymbolPairsTogether && availableColumns > 1) {
                availableColumns - availableColumns % 2
            } else {
                availableColumns
            }
            if (spanCount != columns) spanCount = columns
        }
        super.onLayoutChildren(recycler, state)
    }
}
