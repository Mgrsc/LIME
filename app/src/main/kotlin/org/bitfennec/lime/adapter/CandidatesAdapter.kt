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
import com.google.android.flexbox.FlexboxLayoutManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import org.bitfennec.lime.utils.DevicesUtils.dip2px
import org.bitfennec.lime.R
import org.bitfennec.lime.candidate.CandidateText
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.container.HandwritingContainer
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.inputmethod.calc.CalcResult.Companion.COMMENT_ERROR

/**
 * Adapter for expanded candidate flow panel (DiffUtil incremental updates).
 */
class CandidatesAdapter(context: Context?) :
    RecyclerView.Adapter<CandidatesAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val candidateText = CandidateText(inflater.context.resources)
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var mOnItemLongClickListener: OnRecyclerItemClickListener? = null
    private var mCandidatesList: MutableList<CandidateListItem> = ArrayList()

    fun setOnItemClickListener(onItemClickListener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = onItemClickListener
    }

    fun setOnItemLongClickListener(listener: OnRecyclerItemClickListener?) {
        mOnItemLongClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.item_recyclerview_candidates, parent, false)
        val holder = SymbolHolder(view)
        view.setOnClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val item = mCandidatesList.getOrNull(pos)
                if (InputModeSwitcher.isNumberSkb && item?.comment == COMMENT_ERROR) {
                    return@setOnClickListener
                }
                mOnItemClickListener?.onItemClick(this@CandidatesAdapter, v, pos)
            }
        }
        view.setOnLongClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || mOnItemLongClickListener == null) {
                false
            } else {
                val item = mCandidatesList.getOrNull(pos)
                if (InputModeSwitcher.isNumberSkb && item?.comment == COMMENT_ERROR) {
                    return@setOnLongClickListener false
                }
                mOnItemLongClickListener?.onItemClick(this@CandidatesAdapter, v, pos)
                true
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        if (position !in mCandidatesList.indices) return
        val cand = mCandidatesList[position]

        val currentTextColor = activeTheme.keyTextColor
        val isError = InputModeSwitcher.isNumberSkb && cand.comment == COMMENT_ERROR
        val isPrime = (position == 0) && !isError
        val mainColor = when {
            isError -> ColorUtils.setAlphaComponent(currentTextColor, 110)
            isPrime -> activeTheme.accentKeyBackgroundColor
            else -> currentTextColor
        }
        holder.textView.setTextColor(mainColor)
        holder.itemView.minimumWidth = dip2px(if (isPrime) 64f else 56f)
        holder.textView.typeface = if (isPrime) {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        } else {
            Typeface.DEFAULT
        }

        val isBorder = ThemeManager.prefs.keyBorder.getValue()
        holder.updateBackground(isPrime, isBorder)

        val lp = holder.itemView.layoutParams
        if (lp is FlexboxLayoutManager.LayoutParams) {
            lp.flexGrow = 1.0f
            lp.flexShrink = 0.0f
            lp.isWrapBefore = (cand.text.length >= 10)
        }

        val isHandwriting = InputModeSwitcher.isChineseHandWriting || KeyboardManager.instance.currentContainer is HandwritingContainer
        if (isHandwriting && cand.comment.isNotEmpty() && cand.comment != cand.text) {
            holder.pinyinView.visibility = View.VISIBLE
            holder.pinyinView.text = cand.comment
            holder.pinyinView.setTextColor(mainColor)
            holder.pinyinView.alpha = if (isPrime) 1.0f else 0.75f
            holder.pinyinView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        } else {
            holder.pinyinView.visibility = View.GONE
            if (cand.text.length >= 8) {
                // Allows long English words in expanded candidates to wrap without vertical truncation
                holder.textView.maxLines = if (InputModeSwitcher.isEnglish) Int.MAX_VALUE else 2
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (ImeEnvironment.candidateTextSizeSp * 0.85f).coerceAtLeast(13f))
            } else {
                holder.textView.maxLines = 1
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeEnvironment.candidateTextSizeSp)
            }
        }

        holder.textView.text = candidateText.format(cand.text, holder.textView.paint)
        holder.itemView.contentDescription = holder.itemView.context.getString(
            R.string.accessibility_candidate_item,
            position + 1,
            cand.text
        )
    }

    override fun getItemCount(): Int {
        return mCandidatesList.size
    }

    fun setCandidates(newList: List<CandidateListItem>) {
        val oldList = mCandidatesList
        if (oldList == newList) return
        if (newList.size > oldList.size && oldList.indices.all { oldList[it] == newList[it] }) {
            mCandidatesList = ArrayList(newList)
            notifyItemRangeInserted(oldList.size, newList.size - oldList.size)
            return
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
                val wasPrime = (oldPos == 0)
                val isPrime = (newPos == 0)
                return oldItem == newItem && wasPrime == isPrime
            }
        }, false)
        mCandidatesList = ArrayList(newList)
        diffResult.dispatchUpdatesTo(this)
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        val pinyinView: TextView = view.findViewById(R.id.tv_candidates_item_pinyin)
        val textView: EmojiTextView = view.findViewById(R.id.gv_candidates_item)
        private val cardBg = GradientDrawable().apply {
            cornerRadius = dip2px(8f).toFloat()
        }
        private val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dip2px(8f).toFloat()
        }

        init {
            textView.includeFontPadding = true
            pinyinView.includeFontPadding = false
            textView.setTextColor(activeTheme.keyTextColor)
            val rippleColor = ColorStateList.valueOf(activeTheme.keyPressHighlightColor)
            view.background = RippleDrawable(rippleColor, cardBg, mask)
        }

        fun updateBackground(isPrime: Boolean, isBorder: Boolean) {
            if (isPrime) {
                cardBg.setColor(ColorUtils.setAlphaComponent(activeTheme.accentKeyBackgroundColor, 36))
            } else if (isBorder) {
                cardBg.setColor(ColorUtils.setAlphaComponent(activeTheme.keyBackgroundColor, 50))
            } else {
                cardBg.setColor(Color.TRANSPARENT)
            }
            (itemView.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(activeTheme.keyPressHighlightColor))
        }
    }
}
