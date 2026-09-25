package org.bitfennec.lime.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.utils.StringUtils.sbc2dbcCase
import org.bitfennec.lime.view.popup.AutoScaleTextView

/**
 * Adapter for pinyin syllables and sidebar symbols.
 */
class PrefixAdapter(
    context: Context?,
    private val mDatas: Array<String>,
    private val footerView: View? = null,
    private val itemHeight: Int = 0,
    var onItemClickListener: ((position: Int) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_FOOTER = 1
    }

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val textColor: Int = activeTheme.keyTextColor

    fun matchesContent(data: Array<String>, footer: View?, height: Int = 0): Boolean =
        mDatas.contentEquals(data) && footerView === footer && itemHeight == height &&
            textColor == activeTheme.keyTextColor

    override fun getItemViewType(position: Int): Int {
        return if (footerView != null && position == mDatas.size) TYPE_FOOTER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_FOOTER) {
            FooterViewHolder(footerView!!)
        } else {
            val view = inflater.inflate(R.layout.item_list_alpha_symbol_normal, parent, false)
            if (itemHeight > 0) {
                view.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    itemHeight
                )
                view.findViewById<AutoScaleTextView>(android.R.id.text1)?.setPadding(0, 0, 0, 0)
            } else {
                view.background = null
            }
            SymbolTypeHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SymbolTypeHolder) {
            holder.tvSymbolType.text = sbc2dbcCase(mDatas[position]) ?: ""
            val isLast = (position == mDatas.size - 1) && (footerView == null)
            val showDivider = itemHeight > 0 && !isLast
            holder.vDivider?.visibility = if (showDivider) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in mDatas.indices) {
                    onItemClickListener?.invoke(pos)
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return mDatas.size + if (footerView != null) 1 else 0
    }

    inner class SymbolTypeHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSymbolType: AutoScaleTextView = view.findViewById(android.R.id.text1)
        val vDivider: View? = view.findViewById(R.id.symbol_divider)
        init {
            tvSymbolType.scaleMode = AutoScaleTextView.Mode.Proportional
            tvSymbolType.setTextColor(textColor)
            vDivider?.setBackgroundColor(Color.argb(45, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
        }
    }

    inner class FooterViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
