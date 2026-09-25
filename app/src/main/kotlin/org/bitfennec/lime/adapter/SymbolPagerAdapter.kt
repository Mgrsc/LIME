package org.bitfennec.lime.adapter

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isEmpty
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitfennec.lime.R
import org.bitfennec.lime.data.emojicon.EmojiconData
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.HalfWidthSymbolsMode
import org.bitfennec.lime.prefs.behavior.SymbolMode
import org.bitfennec.lime.utils.DevicesUtils

/**
 * Pager adapter for symbols and emoji.
 */
class SymbolPagerAdapter(
    private val context: Context,
    val viewType: SymbolMode,
    var isChineseMode: Boolean = true,
    private val symbolCategories: List<EmojiconData.SymbolCategory> = EmojiconData.SymbolCategory.values().toList(),
    private val emojiDataMap: Map<Int, List<String>> = emptyMap(),
    private val onClickSymbol: (String, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_NORMAL = 0
        private const val VIEW_TYPE_MORE = 1
    }

    private var attachedRecyclerView: RecyclerView? = null
    private val pendingPageRefreshes = mutableSetOf<Int>()
    private var queriesActive = false
    private val queryJob = SupervisorJob()
    private val queryScope = CoroutineScope(Dispatchers.Main.immediate + queryJob)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        queriesActive = recyclerView.isAttachedToWindow && recyclerView.isShown &&
            recyclerView.windowVisibility == View.VISIBLE
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
            queriesActive = false
            pendingPageRefreshes.clear()
            queryJob.cancelChildren()
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun refreshPage(position: Int) {
        if (position !in 0 until itemCount || position in pendingPageRefreshes) return
        val recyclerView = attachedRecyclerView
        if (recyclerView != null && recyclerView.isComputingLayout) {
            recyclerView.post {
                if (attachedRecyclerView === recyclerView && recyclerView.adapter === this) {
                    refreshPage(position)
                }
            }
        } else {
            // A pending bind reads the latest mode or recents; one notification is enough.
            pendingPageRefreshes.add(position)
            notifyItemChanged(position)
        }
    }

    fun updateChineseMode(isChinese: Boolean) {
        if (this.isChineseMode != isChinese) {
            this.isChineseMode = isChinese
            val commonIndex = symbolCategories.indexOf(EmojiconData.SymbolCategory.COMMON)
            if (commonIndex >= 0) {
                refreshPage(commonIndex)
            }
        }
    }

    fun setQueriesActive(active: Boolean) {
        if (queriesActive == active) return
        queriesActive = active
        if (active) refreshRecents() else queryJob.cancelChildren()
    }

    fun refreshRecents() {
        if (viewType == SymbolMode.Symbol) {
            val recentsIndex = symbolCategories.indexOf(EmojiconData.SymbolCategory.RECENTS)
            if (recentsIndex >= 0) {
                refreshPage(recentsIndex)
            }
        } else {
            val recentsKeyIndex = emojiDataMap.keys.indexOf(R.drawable.icon_emojibar_recents)
            if (recentsKeyIndex >= 0) {
                refreshPage(recentsKeyIndex)
            }
        }
    }

    private var lastHalfWidthMode: HalfWidthSymbolsMode = AppPrefs.getInstance().keyboardSetting.halfWidthSymbolsMode.getValue()

    fun refreshHalfWidthIndicators() {
        if (viewType != SymbolMode.Symbol) return
        val currentMode = AppPrefs.getInstance().keyboardSetting.halfWidthSymbolsMode.getValue()
        if (lastHalfWidthMode != currentMode) {
            lastHalfWidthMode = currentMode
            val rv = attachedRecyclerView
            if (rv != null) {
                for (i in 0 until rv.childCount) {
                    val child = rv.getChildAt(i)
                    val holder = rv.getChildViewHolder(child)
                    if (holder is StandardViewHolder) {
                        (holder.emojiGroupRv.adapter as? SymbolAdapter)?.refreshContent()
                    } else if (holder is MoreViewHolder) {
                        (holder.emojiGroupRv.adapter as? SymbolAdapter)?.refreshContent()
                    }
                }
            } else {
                for (i in 0 until itemCount) {
                    refreshPage(i)
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (viewType == SymbolMode.Symbol && symbolCategories.getOrNull(position) == EmojiconData.SymbolCategory.MORE) {
            return VIEW_TYPE_MORE
        }
        return VIEW_TYPE_NORMAL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewTypeInt: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return if (viewTypeInt == VIEW_TYPE_MORE) {
            MoreViewHolder(inflater.inflate(R.layout.item_pager_symbol_more, parent, false))
        } else {
            StandardViewHolder(inflater.inflate(R.layout.item_pager_symbols_emoji, parent, false))
        }
    }

    override fun getItemCount(): Int {
        return if (viewType == SymbolMode.Symbol) symbolCategories.size else emojiDataMap.size
    }

    private fun bindStandardPage(
        stdHolder: StandardViewHolder,
        spanCount: Int,
        data: List<String>,
        isRecent: Boolean = false,
        showEmptyIfEmpty: Boolean = false,
        categoryKey: Any? = null
    ) {
        val lm = stdHolder.emojiGroupRv.layoutManager as? GridLayoutManager
        if (lm == null || lm.spanCount != spanCount) {
            stdHolder.emojiGroupRv.layoutManager = GridLayoutManager(context, spanCount)
            stdHolder.emojiGroupRv.itemAnimator = null
        }
        if (categoryKey != null && stdHolder.lastCategory != categoryKey) {
            stdHolder.lastCategory = categoryKey
            stdHolder.emojiGroupRv.scrollToPosition(0)
        }
        val existingAdapter = stdHolder.emojiGroupRv.adapter as? SymbolAdapter
        if (existingAdapter != null) {
            existingAdapter.updateData(data, isRecent)
        } else {
            val adapter = SymbolAdapter(context, viewType, isRecent, onClickSymbol)
            stdHolder.emojiGroupRv.adapter = adapter
            adapter.updateData(data, isRecent)
        }
        if (data.isNotEmpty() && stdHolder.emojiGroupRv.isEmpty()) {
            stdHolder.emojiGroupRv.requestLayout()
        }
        stdHolder.tvEmptyState?.visibility = if (showEmptyIfEmpty && data.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        pendingPageRefreshes.remove(position)
        (holder as? StandardViewHolder)?.apply {
            recentJob?.cancel()
            recentJob = null
        }
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (viewType == SymbolMode.Symbol) {
            val category = symbolCategories.getOrNull(position) ?: return
            when (category) {
                EmojiconData.SymbolCategory.MORE -> {
                    val moreHolder = holder as? MoreViewHolder ?: return
                    val spanCount = if (isLandscape) 14 else 8
                    val lm = moreHolder.emojiGroupRv.layoutManager as? GridLayoutManager
                    if (lm == null || lm.spanCount != spanCount) {
                        moreHolder.emojiGroupRv.layoutManager = GridLayoutManager(context, spanCount)
                        moreHolder.emojiGroupRv.itemAnimator = null
                    }
                    val subCats = EmojiconData.SymbolMoreSubCategory.values().toList()
                    val existingAdapter = moreHolder.emojiGroupRv.adapter as? SymbolAdapter
                    val subCatAdapter = moreHolder.rvSubCategoryTabs.adapter as? SubCategoryChipAdapter
                    if (existingAdapter == null || subCatAdapter == null) {
                        val symbolAdapter = SymbolAdapter(context, viewType, isRecent = false, onClickSymbol)
                        symbolAdapter.updateData(EmojiconData.moreSubCategorySymbols[subCats[0]] ?: emptyList(), false)
                        moreHolder.emojiGroupRv.adapter = symbolAdapter

                        moreHolder.rvSubCategoryTabs.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                        moreHolder.rvSubCategoryTabs.adapter = SubCategoryChipAdapter(context, subCats, 0) { selectedSubCat ->
                            symbolAdapter.updateData(EmojiconData.moreSubCategorySymbols[selectedSubCat] ?: emptyList(), false)
                            moreHolder.emojiGroupRv.scrollToPosition(0)
                            moreHolder.rvSubCategoryTabs.smoothScrollToPosition(subCats.indexOf(selectedSubCat))
                        }
                    } else {
                        val selectedIdx = subCatAdapter.getSelectedIndex().coerceIn(0, subCats.lastIndex)
                        val currentSubCat = subCats[selectedIdx]
                        existingAdapter.updateData(EmojiconData.moreSubCategorySymbols[currentSubCat] ?: emptyList(), false)
                    }
                }
                EmojiconData.SymbolCategory.RECENTS -> {
                    val stdHolder = holder as? StandardViewHolder ?: return
                    val spanCount = if (isLandscape) 14 else 8
                    bindRecentPage(stdHolder, position, spanCount, category)
                }
                EmojiconData.SymbolCategory.COMMON -> {
                    val stdHolder = holder as? StandardViewHolder ?: return
                    val spanCount = if (isLandscape) 12 else 6
                    val symbols = if (isChineseMode) EmojiconData.commonChineseSymbols else EmojiconData.commonEnglishSymbols
                    bindStandardPage(stdHolder, spanCount, symbols, isRecent = false, showEmptyIfEmpty = false, categoryKey = category)
                }
                else -> {
                    val stdHolder = holder as? StandardViewHolder ?: return
                    val spanCount = if (isLandscape) 14 else 8
                    val symbols = when (category) {
                        EmojiconData.SymbolCategory.CHINESE -> EmojiconData.chineseSymbols
                        EmojiconData.SymbolCategory.ENGLISH -> EmojiconData.englishSymbols
                        EmojiconData.SymbolCategory.MATH -> EmojiconData.mathSymbols
                        EmojiconData.SymbolCategory.SEQUENCE -> EmojiconData.sequenceSymbols
                        EmojiconData.SymbolCategory.ARROW -> EmojiconData.arrowSymbols
                        EmojiconData.SymbolCategory.MORE,
                        EmojiconData.SymbolCategory.RECENTS,
                        EmojiconData.SymbolCategory.COMMON -> emptyList()
                    }
                    bindStandardPage(stdHolder, spanCount, symbols, isRecent = false, showEmptyIfEmpty = false, categoryKey = category)
                }
            }
        } else {
            val stdHolder = holder as? StandardViewHolder ?: return
            val key = emojiDataMap.keys.toList().getOrNull(position) ?: return
            val spanCount = if (viewType == SymbolMode.Emoticon) {
                if (isLandscape) 5 else 3
            } else {
                if (isLandscape) 14 else 8
            }
            val isRecent = key == R.drawable.icon_emojibar_recents
            if (isRecent) {
                bindRecentPage(stdHolder, position, spanCount, key)
            } else {
                val data = emojiDataMap[key] ?: emptyList()
                bindStandardPage(stdHolder, spanCount, data, isRecent = false, showEmptyIfEmpty = false, categoryKey = key)
            }
        }
    }

    private fun bindRecentPage(holder: StandardViewHolder, position: Int, spanCount: Int, category: Any) {
        if (holder.lastCategory != category) {
            bindStandardPage(holder, spanCount, emptyList(), isRecent = true, categoryKey = category)
        }
        if (!queriesActive) return
        val recyclerView = attachedRecyclerView ?: return
        holder.recentJob = queryScope.launch {
            try {
                val dao = AppDatabase.instance.usedSymbolDao()
                val recents = if (viewType == SymbolMode.Symbol) {
                    dao.getAllUsedSymbol().map { EmojiconData.sanitizeObsoleteSymbol(it.symbol) }.distinct()
                } else {
                    dao.getAllSymbolEmoji().map { it.symbol }
                }
                coroutineContext.ensureActive()
                if (queriesActive && recyclerView.isAttachedToWindow && recyclerView.isShown &&
                    recyclerView.windowVisibility == View.VISIBLE &&
                    attachedRecyclerView === recyclerView && recyclerView.adapter === this@SymbolPagerAdapter &&
                    holder.bindingAdapterPosition == position && holder.lastCategory == category
                ) {
                    bindStandardPage(
                        holder, spanCount, recents, isRecent = true,
                        showEmptyIfEmpty = true, categoryKey = category
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve the last successfully displayed data when a refresh fails.
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? StandardViewHolder)?.apply {
            recentJob?.cancel()
            recentJob = null
        }
        super.onViewRecycled(holder)
    }

    inner class StandardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiGroupRv: RecyclerView = view.findViewById(R.id.emojiGroupRv)
        val tvEmptyState: TextView? = view.findViewById(R.id.tvEmptyState)
        var lastCategory: Any? = null
        var recentJob: Job? = null
    }

    inner class MoreViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rvSubCategoryTabs: RecyclerView = view.findViewById(R.id.rvSubCategoryTabs)
        val emojiGroupRv: RecyclerView = view.findViewById(R.id.emojiGroupRv)
    }

    private class SubCategoryChipAdapter(
        private val context: Context,
        private val subCategories: List<EmojiconData.SymbolMoreSubCategory>,
        private var selectedIndex: Int,
        private val onSelect: (EmojiconData.SymbolMoreSubCategory) -> Unit
    ) : RecyclerView.Adapter<SubCategoryChipAdapter.ChipViewHolder>() {

        fun getSelectedIndex(): Int = selectedIndex

        inner class ChipViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
            val tv = LayoutInflater.from(context).inflate(R.layout.item_chip_symbol_more, parent, false) as TextView
            return ChipViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
            val item = subCategories[position]
            val isSelected = position == selectedIndex
            holder.textView.text = context.getString(item.titleRes)
            val radius = DevicesUtils.dip2px(14).toFloat()
            if (isSelected) {
                holder.textView.background = GradientDrawable().apply {
                    setColor(activeTheme.accentKeyBackgroundColor)
                    cornerRadius = radius
                }
                holder.textView.setTextColor(activeTheme.accentKeyTextColor)
                holder.textView.alpha = 1.0f
                holder.textView.typeface = Typeface.DEFAULT_BOLD
            } else {
                holder.textView.background = GradientDrawable().apply {
                    setColor(activeTheme.keyBackgroundColor)
                    cornerRadius = radius
                }
                holder.textView.setTextColor(activeTheme.keyTextColor)
                holder.textView.alpha = 0.75f
                holder.textView.typeface = Typeface.DEFAULT
            }
            holder.textView.setOnClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos != selectedIndex) {
                    val oldPos = selectedIndex
                    selectedIndex = currentPos
                    notifyItemChanged(oldPos)
                    notifyItemChanged(currentPos)
                    onSelect(subCategories[currentPos])
                }
            }
        }

        override fun getItemCount(): Int = subCategories.size
    }
}
