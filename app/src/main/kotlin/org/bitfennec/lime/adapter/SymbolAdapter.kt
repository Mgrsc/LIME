package org.bitfennec.lime.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.HalfWidthSymbolsMode
import org.bitfennec.lime.prefs.behavior.SymbolMode
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.StringUtils

/**
 * Adapter for symbols and emoji.
 */
class SymbolAdapter(
    context: Context?,
    val viewType: SymbolMode,
    var isRecent: Boolean = false,
    private val onClickSymbol: (String, Int) -> Unit
) : RecyclerView.Adapter<SymbolAdapter.SymbolHolder>() {

    private var attachedRecyclerView: RecyclerView? = null
    private var symbolDividers: RecyclerView.ItemDecoration? = null
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        attachedRecyclerView?.invalidate()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        if (viewType == SymbolMode.Symbol) {
            ThemeManager.addOnChangedListener(onThemeChangeListener)
            val paint = Paint().apply {
                strokeWidth = recyclerView.resources.displayMetrics.density
            }
            val decoration = object : RecyclerView.ItemDecoration() {
                override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                    paint.color = activeTheme.keyTextColor
                    paint.alpha = 24
                    val layout = parent.layoutManager as? GridLayoutManager ?: return
                    val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    for (index in 0 until parent.childCount) {
                        val child = parent.getChildAt(index)
                        val position = parent.getChildAdapterPosition(child)
                        if (position == RecyclerView.NO_POSITION) continue
                        val params = child.layoutParams as GridLayoutManager.LayoutParams
                        if (params.spanIndex > 0) {
                            val x = (if (rtl) child.right else child.left).toFloat()
                            canvas.drawLine(x, child.top.toFloat(), x, child.bottom.toFloat(), paint)
                        }
                        if (layout.spanSizeLookup.getSpanGroupIndex(position, layout.spanCount) > 0) {
                            canvas.drawLine(child.left.toFloat(), child.top.toFloat(),
                                child.right.toFloat(), child.top.toFloat(), paint)
                        }
                    }
                }
            }
            symbolDividers = decoration
            recyclerView.addItemDecoration(decoration)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        symbolDividers?.let { recyclerView.removeItemDecoration(it) }
        symbolDividers = null
        if (attachedRecyclerView === recyclerView) {
            ThemeManager.removeOnChangedListener(onThemeChangeListener)
            attachedRecyclerView = null
            dataUpdateVersion++
        }
    }

    internal var mDatas: List<String> = emptyList()
        private set
    private var dataUpdateVersion = 0L

    private val halfWidthSymbolsMode: HalfWidthSymbolsMode
        get() = AppPrefs.getInstance().keyboardSetting.halfWidthSymbolsMode.getValue()

    fun refreshContent() {
        if (mDatas.isNotEmpty()) {
            notifyItemRangeChanged(0, mDatas.size)
        }
    }

    fun updateData(newItems: List<String>, isRecent: Boolean = this.isRecent) {
        val targetItems = newItems.toList()
        val version = ++dataUpdateVersion
        safelyDispatchUpdates(version) {
            val oldItems = mDatas
            val isRecentChanged = (this.isRecent != isRecent)
            val needsContentRefresh = isRecentChanged

            if (oldItems == targetItems) {
                this.isRecent = isRecent
                if (needsContentRefresh && targetItems.isNotEmpty()) {
                    notifyItemRangeChanged(0, targetItems.size)
                }
            } else if (oldItems.isEmpty()) {
                mDatas = targetItems
                this.isRecent = isRecent
                notifyItemRangeInserted(0, targetItems.size)
            } else {
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = targetItems.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldItems[oldPos] == targetItems[newPos]
                    override fun areContentsTheSame(oldPos: Int, newPos: Int) = !needsContentRefresh
                })
                mDatas = targetItems
                this.isRecent = isRecent
                diff.dispatchUpdatesTo(this)
            }
        }
    }

    private fun safelyDispatchUpdates(version: Long, action: () -> Unit) {
        if (version != dataUpdateVersion) return
        val rv = attachedRecyclerView
        if (rv != null && rv.isComputingLayout) {
            rv.post {
                if (attachedRecyclerView === rv && rv.adapter === this) {
                    safelyDispatchUpdates(version, action)
                }
            }
        } else {
            action()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recyclerview_symbols_emoji, parent, false)
        return SymbolHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val data = mDatas[position]
        holder.textView.text = data
        holder.itemView.contentDescription = data

        when (viewType) {
            SymbolMode.Emojicon -> {
                holder.tVSdb.visibility = View.GONE
                holder.container.background = null
                holder.container.setPadding(0, DevicesUtils.dip2px(4), 0, DevicesUtils.dip2px(4))
                holder.container.minimumHeight = DevicesUtils.dip2px(48)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                val params = holder.container.layoutParams as? ViewGroup.MarginLayoutParams
                params?.setMargins(0, 0, 0, 0)
            }
            SymbolMode.Emoticon -> {
                holder.tVSdb.visibility = View.GONE
                val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat().coerceAtLeast(DevicesUtils.dip2px(6).toFloat())
                val bg = GradientDrawable().apply {
                    setColor(activeTheme.keyBackgroundColor)
                    cornerRadius = keyRadius
                }
                holder.container.background = bg
                holder.container.setPadding(
                    DevicesUtils.dip2px(6),
                    DevicesUtils.dip2px(8),
                    DevicesUtils.dip2px(6),
                    DevicesUtils.dip2px(8)
                )
                holder.container.minimumHeight = DevicesUtils.dip2px(48)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val params = holder.container.layoutParams as? ViewGroup.MarginLayoutParams
                val margin = DevicesUtils.dip2px(3)
                params?.setMargins(margin, margin, margin, margin)
            }
            SymbolMode.Symbol -> {
                holder.container.background = null
                holder.container.setPadding(0, DevicesUtils.dip2px(6), 0, DevicesUtils.dip2px(6))
                holder.container.minimumHeight = DevicesUtils.dip2px(48)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                val params = holder.container.layoutParams as? ViewGroup.MarginLayoutParams
                params?.setMargins(0, 0, 0, 0)
                holder.tVSdb.visibility = when (halfWidthSymbolsMode) {
                    HalfWidthSymbolsMode.All -> if (StringUtils.isDBCSymbol(data)) View.VISIBLE else View.GONE
                    HalfWidthSymbolsMode.OnlyUsed -> if (isRecent && StringUtils.isDBCSymbol(data)) View.VISIBLE else View.GONE
                    HalfWidthSymbolsMode.None -> View.GONE
                }
            }
        }

        holder.itemView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                onClickSymbol(mDatas[currentPosition], currentPosition)
            }
        }
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: FrameLayout = view.findViewById(R.id.fl_symbols_item_container)
        val textView: EmojiTextView = view.findViewById(R.id.gv_symbols_item)
        val tVSdb: TextView = view.findViewById(R.id.tv_sdb_symbols_item)

        init {
            if (viewType == SymbolMode.Symbol) {
                container.foreground = RippleDrawable(
                    ColorStateList.valueOf(activeTheme.keyTextColor).withAlpha(28),
                    null, Color.WHITE.toDrawable()
                )
                container.isFocusable = true
            }
            textView.setTextColor(activeTheme.keyTextColor)
            tVSdb.setTextColor(activeTheme.keyTextColor)
        }
    }
}
