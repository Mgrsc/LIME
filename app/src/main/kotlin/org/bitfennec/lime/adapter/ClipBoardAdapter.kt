package org.bitfennec.lime.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.database.entity.Clipboard
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode
import org.bitfennec.lime.utils.DevicesUtils.dip2px
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clipboard adapter using ListAdapter and DiffUtil.
 */
class ClipBoardAdapter(
    private val context: Context
) : ListAdapter<Clipboard, ClipBoardAdapter.ClipboardViewHolder>(ClipboardDiffCallback) {

    private var textColor: Int = activeTheme.keyTextColor
    private var accentColor: Int = activeTheme.accentKeyBackgroundColor
    private var clipboardLayoutCompact: ClipboardLayoutMode = AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()

    var onItemClickListener: ((Clipboard, Int) -> Unit)? = null
    var onItemLongClickListener: ((Clipboard, Int, View) -> Unit)? = null

    // View type changes for every row. notifyItemRangeChanged rebinds the old holder.
    @SuppressLint("NotifyDataSetChanged")
    fun setLayoutMode(mode: ClipboardLayoutMode) {
        if (clipboardLayoutCompact != mode) {
            clipboardLayoutCompact = mode
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int = clipboardLayoutCompact.ordinal

    fun updateData(newDatas: List<Clipboard>, onCommit: (() -> Unit)? = null) {
        submitList(newDatas.toList(), onCommit)
    }

    fun getDataList(): List<Clipboard> = currentList

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipboardViewHolder {
        val mode = ClipboardLayoutMode.entries.getOrNull(viewType) ?: clipboardLayoutCompact
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val marginValue = dip2px(3)
        when (mode) {
            ClipboardLayoutMode.ListView -> {
                rootLayout.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(marginValue * 2, marginValue, marginValue * 2, marginValue)
                }
            }
            ClipboardLayoutMode.FlexboxView -> {
                rootLayout.layoutParams = StaggeredGridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(marginValue, marginValue, marginValue, marginValue)
                }
            }
            ClipboardLayoutMode.GridView -> {
                rootLayout.layoutParams = GridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(marginValue, marginValue, marginValue, marginValue)
                }
            }
        }

        // Card rounded background
        rootLayout.background = GradientDrawable().apply {
            setColor(activeTheme.keyBackgroundColor)
            setShape(GradientDrawable.RECTANGLE)
            setCornerRadius(ThemeManager.prefs.keyRadius.getValue().toFloat())
        }
        rootLayout.setPadding(dip2px(8), dip2px(6), dip2px(8), dip2px(6))

        // Top row (content + pinned icon)
        val topContentLayout = RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val contentView = EmojiTextView(context).apply {
            id = R.id.clipboard_adapter_content
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.TOP
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dip2px(2).toFloat(), 1.0f)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val pinIconView = ImageView(context).apply {
            id = R.id.clipboard_adapter_top_tips
            setImageResource(R.drawable.ic_menu_pin)
            val iconSize = dip2px(14)
            layoutParams = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
                addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE)
            }
            setColorFilter(accentColor)
            visibility = View.GONE
        }

        topContentLayout.addView(contentView)
        topContentLayout.addView(pinIconView)
        rootLayout.addView(topContentLayout)

        // Bottom metadata row (timestamp + char count)
        val bottomMetaLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dip2px(4)
            }
        }

        val timeTextView = TextView(context).apply {
            id = View.generateViewId()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(Color.argb(130, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val countTextView = TextView(context).apply {
            id = View.generateViewId()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(Color.argb(130, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dip2px(6)
            }
        }

        bottomMetaLayout.addView(timeTextView)
        bottomMetaLayout.addView(countTextView)
        rootLayout.addView(bottomMetaLayout)

        return ClipboardViewHolder(rootLayout, contentView, pinIconView, timeTextView, countTextView)
    }

    override fun onBindViewHolder(holder: ClipboardViewHolder, position: Int) {
        val data = getItem(position)
        holder.contentView.text = data.content
        val isPinned = data.isKeep == 1
        holder.pinIconView.visibility = if (isPinned) View.VISIBLE else View.GONE
        holder.contentView.setPaddingRelative(0, 0, if (isPinned) dip2px(16) else 0, 0)
        val context = holder.itemView.context
        holder.timeTextView.text = formatTime(data.time, context)
        holder.countTextView.text = context.resources.getQuantityString(R.plurals.clipboard_char_count, data.content.length, data.content.length)

        holder.itemView.setOnClickListener {
            val curPos = holder.bindingAdapterPosition
            if (curPos != RecyclerView.NO_POSITION && curPos < currentList.size) {
                onItemClickListener?.invoke(getItem(curPos), curPos)
            }
        }

        holder.itemView.setOnLongClickListener {
            val curPos = holder.bindingAdapterPosition
            if (curPos != RecyclerView.NO_POSITION && curPos < currentList.size) {
                onItemLongClickListener?.invoke(getItem(curPos), curPos, holder.itemView)
            }
            true
        }
    }

    private fun formatTime(time: Long, context: Context): String {
        if (time <= 0) return ""
        val diff = System.currentTimeMillis() - time
        return when {
            diff < 60_000 -> context.getString(R.string.clipboard_time_just_now)
            diff < 3600_000 -> context.getString(R.string.clipboard_time_minutes_ago, (diff / 60_000).toInt())
            diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
            diff < 86400_000 * 2 -> context.getString(R.string.clipboard_time_yesterday)
            diff < 86400_000 * 7 -> {
                val days = (diff / 86400_000).toInt()
                context.resources.getQuantityString(R.plurals.clipboard_time_days_ago, days, days)
            }
            else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(time))
        }
    }

    object ClipboardDiffCallback : DiffUtil.ItemCallback<Clipboard>() {
        override fun areItemsTheSame(oldItem: Clipboard, newItem: Clipboard): Boolean =
            oldItem.content == newItem.content

        override fun areContentsTheSame(oldItem: Clipboard, newItem: Clipboard): Boolean =
            oldItem == newItem
    }

    inner class ClipboardViewHolder(
        view: View,
        val contentView: TextView,
        val pinIconView: ImageView,
        val timeTextView: TextView,
        val countTextView: TextView
    ) : RecyclerView.ViewHolder(view)
}
