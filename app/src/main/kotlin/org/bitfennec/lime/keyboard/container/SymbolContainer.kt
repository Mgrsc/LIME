package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.SymbolPagerAdapter
import org.bitfennec.lime.data.emojicon.EmojiconData
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bitfennec.lime.inputmethod.ImeDispatchers
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.database.entity.UsedSymbol
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.prefs.behavior.SymbolMode
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.keyboard.DeleteKeyGestureController
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.manager.InputModeSwitcher
import androidx.constraintlayout.widget.ConstraintLayout
import org.bitfennec.lime.utils.dp
import kotlin.math.max

/**
 * Symbols and emoji keyboard container.
 * 1. Independent symbols page; Emoji / Emoticon share a top switcher
 * 2. Bottom navigation bar (Category tabs / Lock symbol / Delete)
 * 3. Body ViewPager2 (Emoji grid / Symbols grid)
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SymbolContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private var isConstructed = false
    private var mShowType: SymbolMode = SymbolMode.Symbol
    private val pagerPositions = mutableMapOf<SymbolMode, Int>()
    private val mVPSymbolsView: ViewPager2
    private var tabLayout: TabLayout
    private val ivDelete: ImageView
    private var deleteGestureController: DeleteKeyGestureController? = null
    private val ivLock: ImageView
    var isLockSymbol = false
    private var isSymbolLockedByUser = false
    private var tabLayoutMediator: TabLayoutMediator? = null
    private var lastEditorSessionId: Long = -1L
    private var lastSelectedTabPosition: Int = -1
    private var pageWakePending = false
    private val pageWakeTask = object : Runnable {
        override fun run() {
            pageWakePending = false
            if (!isShown || !isAttachedToWindow || windowVisibility != VISIBLE) return
            val pager = mVPSymbolsView
            if (pager.adapter !is SymbolPagerAdapter) return
            val inner = (pager.getChildAt(0) as? RecyclerView)
                ?.findViewHolderForAdapterPosition(pager.currentItem)
                ?.itemView
                ?.findViewById<RecyclerView>(R.id.emojiGroupRv)
                ?: return
            inner.requestLayout()
        }
    }

    private val mLLTopSegment: LinearLayout
    private val tvSegmentEmoji: TextView
    private val tvSegmentEmoticon: TextView

    private fun createBottomBackground() = RippleDrawable(
        ColorStateList.valueOf(activeTheme.keyTextColor).withAlpha(28),
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), InsetDrawable(
                GradientDrawable().apply {
                    setColor(ColorStateList.valueOf(activeTheme.accentKeyBackgroundColor).withAlpha(40))
                    cornerRadius = dp(18).toFloat()
                }, dp(2), dp(6), dp(2), dp(6)
            ))
        },
        InsetDrawable(GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(18).toFloat()
        }, dp(2), dp(6), dp(2), dp(6))
    )

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isConstructed) return
        if (!isShown) {
            cancelPageWake()
            if (changedView === this) {
                rememberPosition()
            }
        } else {
            wakeCurrentPage()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wakeCurrentPage()
    }

    override fun onDetachedFromWindow() {
        cancelPageWake()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isConstructed) return
        if (visibility == VISIBLE) wakeCurrentPage() else cancelPageWake()
    }

    override fun updateSkbLayout() {
        super.updateSkbLayout()
        if (mShowType == SymbolMode.Symbol) {
            refreshSymbolState()
        } else {
            refreshEmojiState()
        }
    }

    private fun updateLockUi() {
        ivLock.contentDescription = context.getString(if (isLockSymbol) R.string.symbol_unlock else R.string.symbol_lock)
        ivLock.stateDescription = context.getString(if (isLockSymbol) R.string.symbol_locked else R.string.symbol_unlocked)
        ivLock.isSelected = isLockSymbol
        if (isLockSymbol) {
            ivLock.drawable?.setTint(activeTheme.accentKeyBackgroundColor)
        } else {
            ivLock.drawable?.setTint(
                Color.argb(
                    128,
                    Color.red(activeTheme.keyTextColor),
                    Color.green(activeTheme.keyTextColor),
                    Color.blue(activeTheme.keyTextColor)
                )
            )
        }
    }

    init {
        // 1. Initialize top category bar
        mLLTopSegment = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(2))
        }

        val segmentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val radius = DevicesUtils.dip2px(16).toFloat()
            val bg = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                cornerRadius = radius
            }
            background = bg
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }

        tvSegmentEmoji = createSegmentTab(context.getString(R.string.symbol_tab_emoji)) {
            setEmojisView(SymbolMode.Emojicon)
        }

        tvSegmentEmoticon = createSegmentTab(context.getString(R.string.symbol_tab_emoticon)) {
            setEmojisView(SymbolMode.Emoticon)
        }

        segmentContainer.addView(tvSegmentEmoji)
        segmentContainer.addView(tvSegmentEmoticon)
        mLLTopSegment.addView(segmentContainer)

        // 2. Initialize bottom navigation and controls
        val mLLSymbolType = LayoutInflater.from(getContext()).inflate(R.layout.view_symbols_emoji_type, this, false) as LinearLayout
        mLLSymbolType.id = View.generateViewId()
        tabLayout = mLLSymbolType.findViewById<TabLayout>(R.id.tab_symbols_emoji_type).apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val position = tab?.position ?: return
                    if (mShowType == SymbolMode.Symbol) {
                        lastSelectedTabPosition = position
                        (tab.customView as? TextView)?.apply {
                            alpha = 1.0f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    } else {
                        (tab.customView as? ImageView)?.alpha = 1.0f
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {
                    if (mShowType == SymbolMode.Symbol) {
                        (tab?.customView as? TextView)?.apply {
                            alpha = 0.65f
                            typeface = Typeface.DEFAULT
                        }
                    } else {
                        (tab?.customView as? ImageView)?.alpha = 0.65f
                    }
                }
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }

        val ivReturn: ImageView = mLLSymbolType.findViewById(R.id.iv_symbols_emoji_type_return)
        ivReturn.drawable?.setTint(activeTheme.keyTextColor)
        ivLock = mLLSymbolType.findViewById(R.id.iv_symbols_emoji_type_lock)
        ivDelete = mLLSymbolType.findViewById(R.id.iv_symbols_emoji_type_delete)
        ivDelete.drawable?.setTint(activeTheme.keyTextColor)

        listOf(ivReturn, ivLock, ivDelete).forEach {
            it.background = createBottomBackground()
            it.isFocusable = true
        }
        mLLSymbolType.background = LayerDrawable(arrayOf(GradientDrawable().apply {
            setColor(ColorStateList.valueOf(activeTheme.keyTextColor).withAlpha(24))
        })).apply {
            setLayerHeight(0, dp(1))
            setLayerGravity(0, Gravity.TOP)
            setLayerInset(0, dp(8), 0, dp(8), 0)
        }

        ivReturn.setOnClickListener {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(this)
            KeyboardManager.instance.switchKeyboard()
        }

        ivLock.setOnClickListener {
            isSymbolLockedByUser = !isSymbolLockedByUser
            isLockSymbol = isSymbolLockedByUser
            updateLockUi()
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(this)
        }

        deleteGestureController = DeleteKeyGestureController(inputView, ivDelete)
        ivDelete.setOnTouchListener(deleteGestureController)

        // 3. Initialize ViewPager2
        mVPSymbolsView = ViewPager2(context).apply {
            id = View.generateViewId()
        }

        // 4. Assemble layout
        val lpTop = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        }
        addView(mLLTopSegment, lpTop)

        val lpBottom = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
        addView(mLLSymbolType, lpBottom)

        val lpPager = ConstraintLayout.LayoutParams(0, 0).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = mLLTopSegment.id
            bottomToTop = mLLSymbolType.id
        }
        addView(mVPSymbolsView, lpPager)
        mLLTopSegment.visibility = View.GONE
        isConstructed = true
    }

    private fun createSegmentTab(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener {
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(this@SymbolContainer)
                onClick()
            }
        }
    }

    private fun rememberPosition() {
        if (mVPSymbolsView.adapter == null) return
        if (mShowType == SymbolMode.Symbol) {
            lastSelectedTabPosition = mVPSymbolsView.currentItem
        } else {
            pagerPositions[mShowType] = mVPSymbolsView.currentItem
        }
    }

    private fun detachMediator() {
        if (tabLayoutMediator?.isAttached == true) {
            tabLayoutMediator?.detach()
        }
        tabLayoutMediator = null
    }

    private fun wakeCurrentPage() {
        val active = isShown && isAttachedToWindow && windowVisibility == VISIBLE
        val adapter = mVPSymbolsView.adapter as? SymbolPagerAdapter ?: return
        adapter.setQueriesActive(active)
        if (pageWakePending || !active) return
        pageWakePending = true
        if (!mVPSymbolsView.post(pageWakeTask)) pageWakePending = false
    }

    private fun cancelPageWake() {
        deleteGestureController?.cancel()
        (mVPSymbolsView.adapter as? SymbolPagerAdapter)?.setQueriesActive(false)
        mVPSymbolsView.removeCallbacks(pageWakeTask)
        pageWakePending = false
    }

    private fun updateTopSegmentUI() {
        mLLTopSegment.visibility = if (mShowType == SymbolMode.Symbol) View.GONE else View.VISIBLE
        val radius = DevicesUtils.dip2px(14).toFloat()

        fun applyTabState(tv: TextView, isSelected: Boolean) {
            tv.isSelected = isSelected
            if (isSelected) {
                val bg = GradientDrawable().apply {
                    setColor(activeTheme.accentKeyBackgroundColor)
                    cornerRadius = radius
                }
                tv.background = bg
                tv.setTextColor(activeTheme.accentKeyTextColor)
                tv.alpha = 1.0f
                tv.typeface = Typeface.DEFAULT_BOLD
            } else {
                tv.background = null
                tv.setTextColor(activeTheme.keyTextColor)
                tv.alpha = 0.65f
                tv.typeface = Typeface.DEFAULT
            }
        }

        applyTabState(tvSegmentEmoji, mShowType == SymbolMode.Emojicon)
        applyTabState(tvSegmentEmoticon, mShowType == SymbolMode.Emoticon)
    }

    private fun onItemClickOperate(value: String) {
        val result = if (value.isNotEmpty()) value else return
        val softKey = SoftKey(label = result)
        DevicesUtils.tryPlayKeyDown()
        DevicesUtils.tryVibrate(this)
        inputView.responseKeyEvent(softKey)
        if (mShowType == SymbolMode.Symbol && !isLockSymbol) {
            KeyboardManager.instance.switchKeyboard()
        }
        val currentType = mShowType
        val symbolToSave = if (currentType == SymbolMode.Symbol) InputModeSwitcher.normalizeEditorLiteral(result) else result
        CoroutineScope(ImeDispatchers.idleDispatcher).launch {
            try {
                val dao = AppDatabase.instance.usedSymbolDao()
                if (currentType == SymbolMode.Symbol) {
                    dao.insert(UsedSymbol(symbol = symbolToSave))
                    val num = max(dao.getCount("symbol") - 50, 0)
                    if (num > 0) dao.deleteOldest("symbol", num)
                } else {
                    dao.insert(UsedSymbol(symbol = result, type = "emoji"))
                    val num = max(dao.getCount("emoji") - 50, 0)
                    if (num > 0) dao.deleteOldest("emoji", num)
                }
                withContext(Dispatchers.Main.immediate) {
                    val adapter = mVPSymbolsView.adapter as? SymbolPagerAdapter
                    if (isAttachedToWindow && isShown && windowVisibility == VISIBLE && adapter != null &&
                        (adapter.viewType == SymbolMode.Symbol) == (currentType == SymbolMode.Symbol)
                    ) {
                        adapter.refreshRecents()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Switches to symbol view.
     */
    fun setSymbolsView() {
        val existingAdapter = mVPSymbolsView.adapter as? SymbolPagerAdapter
        val alreadySymbol = mShowType == SymbolMode.Symbol && existingAdapter?.viewType == SymbolMode.Symbol
        if (!alreadySymbol) {
            rememberPosition()
            detachMediator()
        }
        mShowType = SymbolMode.Symbol
        isLockSymbol = isSymbolLockedByUser
        ivLock.visibility = View.VISIBLE
        updateLockUi()
        ivDelete.drawable?.setTint(activeTheme.keyTextColor)

        updateTopSegmentUI()

        val categories = EmojiconData.SymbolCategory.values().toList()
        val isChinese = InputModeSwitcher.isChinese
        val currentSessionId = InputModeSwitcher.currentEditorSessionId
        val targetPosition = if (currentSessionId == lastEditorSessionId && lastSelectedTabPosition in categories.indices) {
            lastSelectedTabPosition
        } else {
            lastEditorSessionId = currentSessionId
            val defaultPos = if (InputModeSwitcher.isEmailOrUri) {
                EmojiconData.SymbolCategory.ENGLISH.ordinal
            } else {
                EmojiconData.SymbolCategory.COMMON.ordinal
            }
            lastSelectedTabPosition = defaultPos
            defaultPos
        }

        if (alreadySymbol) {
            existingAdapter.updateChineseMode(isChinese)
            existingAdapter.refreshRecents()
            if (mVPSymbolsView.currentItem != targetPosition) {
                mVPSymbolsView.setCurrentItem(targetPosition, false)
            }
            wakeCurrentPage()
            return
        }

        mVPSymbolsView.adapter = SymbolPagerAdapter(
            context = context,
            viewType = SymbolMode.Symbol,
            isChineseMode = isChinese,
            symbolCategories = categories,
            emojiDataMap = emptyMap()
        ) { symbol, _ ->
            onItemClickOperate(symbol)
        }

        // Categories stay fixed for this adapter; content updates must not rebuild tabs.
        tabLayoutMediator = TabLayoutMediator(tabLayout, mVPSymbolsView, false) { tab, position ->
            tab.view.background = createBottomBackground()
            val category = categories.getOrNull(position) ?: return@TabLayoutMediator
            val label = context.getString(category.titleRes)
            tab.contentDescription = label
            val isCurrent = position == targetPosition
            tab.setCustomView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setTextColor(activeTheme.keyTextColor)
                alpha = if (isCurrent) 1.0f else 0.65f
                typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
            tab.view.setPadding(0)
        }
        tabLayoutMediator?.attach()
        mVPSymbolsView.setCurrentItem(targetPosition, false)
        wakeCurrentPage()
    }

    /**
     * Switches to Emoji or Emoticon view.
     */
    fun setEmojisView(showType: SymbolMode) {
        require(showType != SymbolMode.Symbol)
        val existingAdapter = mVPSymbolsView.adapter as? SymbolPagerAdapter
        val alreadySameEmoji = mShowType == showType && existingAdapter?.viewType == showType
        if (!alreadySameEmoji) {
            rememberPosition()
            detachMediator()
        }
        mShowType = showType
        isLockSymbol = true
        ivLock.visibility = View.GONE
        ivDelete.drawable?.setTint(activeTheme.keyTextColor)

        updateTopSegmentUI()

        if (alreadySameEmoji) {
            existingAdapter.refreshRecents()
            wakeCurrentPage()
            return
        }

        val mSymbolsEmoji = when (showType) {
            SymbolMode.Emoticon -> EmojiconData.emoticonData
            else -> EmojiconData.emojiconData
        }
        mVPSymbolsView.adapter = SymbolPagerAdapter(
            context = context,
            viewType = showType,
            isChineseMode = true,
            symbolCategories = emptyList(),
            emojiDataMap = mSymbolsEmoji
        ) { symbol, _ ->
            onItemClickOperate(symbol)
        }
        val data = mSymbolsEmoji.keys.toList()
        val lastIndex = (data.size - 1).coerceAtLeast(0)
        val targetPosition = (pagerPositions[showType] ?: 0).coerceIn(0, lastIndex)
        // Categories stay fixed for this adapter; content updates must not rebuild tabs.
        tabLayoutMediator = TabLayoutMediator(tabLayout, mVPSymbolsView, false) { tab, position ->
            val icon = data.getOrNull(position) ?: return@TabLayoutMediator
            tab.contentDescription = context.getString(when (icon) {
                R.drawable.icon_emojibar_recents -> R.string.emoji_category_recents
                R.drawable.icon_emojibar_smileys -> R.string.emoji_category_smileys
                R.drawable.icon_emojibar_people -> R.string.emoji_category_people
                R.drawable.icon_emojibar_nature -> R.string.emoji_category_nature
                R.drawable.icon_emojibar_food -> R.string.emoji_category_food
                R.drawable.icon_emojibar_car -> R.string.emoji_category_car
                R.drawable.icon_emojibar_activity -> R.string.emoji_category_activity
                R.drawable.icon_emojibar_objects -> R.string.emoji_category_objects
                R.drawable.icon_emojibar_symbols -> R.string.emoji_category_symbols
                R.drawable.icon_emojibar_flags -> R.string.emoji_category_flags
                else -> R.string.emoji_category_symbols
            })
            tab.view.background = createBottomBackground()
            val isCurrent = (position == targetPosition)
            tab.setCustomView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(ContextCompat.getDrawable(context, icon)?.apply {
                    setTint(activeTheme.keyTextColor)
                })
                alpha = if (isCurrent) 1.0f else 0.65f
            })
            tab.view.setPadding(0)
        }
        tabLayoutMediator?.attach()
        mVPSymbolsView.setCurrentItem(targetPosition, false)
        wakeCurrentPage()
    }

    private fun refreshSymbolState() {
        val adapter = mVPSymbolsView.adapter as? SymbolPagerAdapter ?: return
        if (adapter.viewType != SymbolMode.Symbol) return
        adapter.updateChineseMode(InputModeSwitcher.isChinese)
        adapter.refreshRecents()
        adapter.refreshHalfWidthIndicators()
        val currentSessionId = InputModeSwitcher.currentEditorSessionId
        if (currentSessionId != lastEditorSessionId) {
            lastEditorSessionId = currentSessionId
            val defaultPos = if (InputModeSwitcher.isEmailOrUri) {
                EmojiconData.SymbolCategory.ENGLISH.ordinal
            } else {
                EmojiconData.SymbolCategory.COMMON.ordinal
            }
            lastSelectedTabPosition = defaultPos
            if (mVPSymbolsView.currentItem != defaultPos) {
                mVPSymbolsView.setCurrentItem(defaultPos, false)
            }
        }
        updateLockUi()
        wakeCurrentPage()
    }

    private fun refreshEmojiState() {
        val adapter = mVPSymbolsView.adapter as? SymbolPagerAdapter ?: return
        if (adapter.viewType != SymbolMode.Emojicon && adapter.viewType != SymbolMode.Emoticon) return
        adapter.refreshRecents()
        updateLockUi()
        wakeCurrentPage()
    }

    fun getMenuMode(): SymbolMode {
        return mShowType
    }
}
