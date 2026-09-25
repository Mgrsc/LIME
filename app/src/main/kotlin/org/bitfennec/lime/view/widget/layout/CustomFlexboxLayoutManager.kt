package org.bitfennec.lime.view.widget.layout

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager

class CustomFlexboxLayoutManager(context:Context): FlexboxLayoutManager(context) {
    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        try {
            return super.scrollVerticallyBy(dy, recycler, state)
        } catch (_:IndexOutOfBoundsException ) {   // Guard against IndexOutOfBoundsException when clearing candidates during rapid fling
        }
        return  0
    }
}