package org.bitfennec.lime.adapter

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import org.bitfennec.lime.utils.DevicesUtils.dip2px
import org.bitfennec.lime.R
import org.bitfennec.lime.candidate.CandidateText
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.container.HandwritingContainer
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.inputmethod.calc.CalcResult.Companion.COMMENT_ERROR

/**
 * Adapter for candidate bar items (supports DiffUtil incremental updates).
 */
class CandidatesBarAdapter(context: Context?) :
    RecyclerView.Adapter<CandidatesBarAdapter.SymbolHolder>() {
    private val selectionPayload = Any()
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val candidateText = CandidateText(inflater.context.resources)
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var mOnItemLongClickListener: OnRecyclerItemClickListener? = null
    private var mActiveCandNo: Int = 0
    private var mCandidatesList: List<CandidateListItem> = emptyList()
    var previewTheme: Theme? = null
    private val theme: Theme get() = previewTheme ?: activeTheme
    var previewCandidates: List<CandidateListItem>? = null
        set(value) {
            val oldCount = itemCount
            field = value?.toList()
            if (oldCount > 0) notifyItemRangeRemoved(0, oldCount)
            if (itemCount > 0) notifyItemRangeInserted(0, itemCount)
        }

    fun setOnItemClickListener(onItemClickListener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = onItemClickListener
    }

    fun setOnItemLongClickListener(listener: OnRecyclerItemClickListener?) {
        mOnItemLongClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.item_recyclerview_candidates_bar, parent, false)
        val holder = SymbolHolder(view)
        view.setOnClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val item = (previewCandidates ?: mCandidatesList).getOrNull(pos)
                if (InputModeSwitcher.isNumberSkb && item?.comment == COMMENT_ERROR) {
                    return@setOnClickListener
                }
                mOnItemClickListener?.onItemClick(this@CandidatesBarAdapter, v, pos)
            }
        }
        view.setOnLongClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || mOnItemLongClickListener == null) {
                false
            } else {
                val item = (previewCandidates ?: mCandidatesList).getOrNull(pos)
                if (InputModeSwitcher.isNumberSkb && item?.comment == COMMENT_ERROR) {
                    return@setOnLongClickListener false
                }
                mOnItemLongClickListener?.onItemClick(this@CandidatesBarAdapter, v, pos)
                true
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val list = previewCandidates ?: mCandidatesList
        if (list.isEmpty() || position >= list.size) return
        val cand = list[position]
        val candidateTextSize = ImeEnvironment.candidateTextSizeSp
        bindSelection(holder, position)

        // Display pinyin ruby text in handwriting mode
        val isHandwriting = InputModeSwitcher.isChineseHandWriting || KeyboardManager.instance.currentContainer is HandwritingContainer
        if (isHandwriting && cand.comment.isNotEmpty() && cand.comment != cand.text) {
            val handwritingTextSize = candidateTextSize.coerceAtMost(18f)
            holder.pinyinView.visibility = View.VISIBLE
            holder.pinyinView.text = cand.comment
            holder.pinyinView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, handwritingTextSize)
        } else {
            holder.pinyinView.visibility = View.GONE
            holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, candidateTextSize)
        }

        holder.textView.text = candidateText.format(cand.text, holder.textView.paint)
        holder.itemView.contentDescription = holder.itemView.context.getString(
            R.string.accessibility_candidate_item,
            position + 1,
            cand.text
        )
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int, payloads: MutableList<Any>) {
        val cand = (previewCandidates ?: mCandidatesList).getOrNull(position)
        // Supplementary glyph fallback depends on the selected typeface.
        if (payloads.isNotEmpty() && payloads.all { it === selectionPayload } &&
            cand != null && cand.text.none { it in '\uD800'..'\uDBFF' }
        ) {
            bindSelection(holder, position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun bindSelection(holder: SymbolHolder, position: Int) {
        val cand = (previewCandidates ?: mCandidatesList).getOrNull(position) ?: return
        val isError = InputModeSwitcher.isNumberSkb && cand.comment == COMMENT_ERROR
        val isSelected = !isError && ((mActiveCandNo - 1 == position) || (previewCandidates != null && position == 0) || (mActiveCandNo <= 0 && position == 0))
        val mainColor = when {
            isError -> ColorUtils.setAlphaComponent(theme.keyTextColor, 110)
            isSelected -> theme.accentKeyBackgroundColor
            else -> theme.keyTextColor
        }
        holder.textView.setTextColor(mainColor)
        holder.itemView.minimumWidth = dip2px(if (isSelected) 52f else 44f)
        holder.textView.typeface = if (isSelected) {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        } else {
            Typeface.DEFAULT
        }

        holder.updateBackground(isSelected)

        holder.pinyinView.setTextColor(mainColor)
        holder.pinyinView.alpha = if (isSelected) 1.0f else 0.75f
        holder.itemView.isSelected = isSelected
        holder.itemView.stateDescription = if (isSelected) {
            holder.itemView.context.getString(R.string.accessibility_candidate_selected)
        } else {
            null
        }
    }

    override fun getItemCount(): Int {
        return previewCandidates?.size ?: mCandidatesList.size
    }

    /**
     * Updates highlighted candidate.
     */
    fun activeCandidates(activeNo: Int) {
        val oldPosition = (mActiveCandNo - 1).coerceAtLeast(0)
        val newPosition = (activeNo - 1).coerceAtLeast(0)
        mActiveCandNo = activeNo
        if (oldPosition == newPosition) return
        if (oldPosition in 0 until itemCount) notifyItemChanged(oldPosition, selectionPayload)
        if (newPosition in 0 until itemCount) notifyItemChanged(newPosition, selectionPayload)
    }

    /**
     * Incrementally updates candidates; returns true when the caller should reset scrolling.
     */
    fun setCandidates(newList: List<CandidateListItem>, activeNo: Int = 0): Boolean {
        val oldList = mCandidatesList
        val resetScroll = oldList.isEmpty() || newList.size < oldList.size || oldList.indices.any {
            oldList[it].text != newList[it].text || oldList[it].comment != newList[it].comment
        }
        val oldActive = mActiveCandNo
        mActiveCandNo = activeNo

        if (previewCandidates != null) {
            mCandidatesList = ArrayList(newList)
            notifyChanged()
            return resetScroll
        }

        if (oldList == newList) {
            mActiveCandNo = oldActive
            activeCandidates(activeNo)
            return resetScroll
        }
        if (oldActive == activeNo && newList.size > oldList.size &&
            oldList.indices.all { oldList[it] == newList[it] }
        ) {
            mCandidatesList = ArrayList(newList)
            notifyItemRangeInserted(oldList.size, newList.size - oldList.size)
            return resetScroll
        }

        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldItem = oldList[oldPos]
                val newItem = newList[newPos]
                return oldItem.text == newItem.text && oldItem.comment == newItem.comment
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldItem = oldList[oldPos]
                val newItem = newList[newPos]
                val wasSelected = (oldActive - 1 == oldPos) || (previewCandidates != null && oldPos == 0) || (oldActive <= 0 && oldPos == 0)
                val isSelected = (activeNo - 1 == newPos) || (previewCandidates != null && newPos == 0) || (activeNo <= 0 && newPos == 0)
                return oldItem == newItem && wasSelected == isSelected
            }
        }, false)
        mCandidatesList = ArrayList(newList)
        diffResult.dispatchUpdatesTo(this)
        return resetScroll
    }

    fun notifyChanged() {
        notifyItemRangeChanged(0, itemCount)
    }

    inner class SymbolHolder(private val itemRoot: View) : RecyclerView.ViewHolder(itemRoot) {
        val pinyinView: TextView = itemRoot.findViewById(R.id.tv_candidate_bar_pinyin)
        val textView: EmojiTextView = itemRoot.findViewById(R.id.gv_candidates_bar_item)
        private val baseBg = GradientDrawable().apply {
            cornerRadius = dip2px(6f).toFloat()
        }
        private val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dip2px(6f).toFloat()
        }

        private val ripple = RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), baseBg, mask)

        init {
            itemRoot.background = InsetDrawable(ripple, 0, dip2px(2f), 0, dip2px(2f))
            textView.includeFontPadding = true
            pinyinView.includeFontPadding = false
            textView.setTextColor(theme.keyTextColor)
            updateBackground(isSelected = false)
        }

        fun updateBackground(isSelected: Boolean) {
            if (isSelected) {
                baseBg.setColor(ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, 36))
            } else {
                baseBg.setColor(Color.TRANSPARENT)
            }
            ripple.setColor(ColorStateList.valueOf(theme.keyPressHighlightColor))
        }
    }
}
