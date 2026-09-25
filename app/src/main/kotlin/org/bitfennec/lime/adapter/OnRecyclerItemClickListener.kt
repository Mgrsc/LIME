package org.bitfennec.lime.adapter

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView item click listener.
 */
fun interface OnRecyclerItemClickListener {
    fun onItemClick(parent: RecyclerView.Adapter<*>?, v: View?, position: Int)
}
