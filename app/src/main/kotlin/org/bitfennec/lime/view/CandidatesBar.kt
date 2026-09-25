package org.bitfennec.lime.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout
import androidx.core.graphics.ColorUtils
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.CandidatesBarAdapter
import org.bitfennec.lime.adapter.CandidatesMenuAdapter
import org.bitfennec.lime.candidate.CandidateViewListener
import org.bitfennec.lime.data.menuSkbFunsPreset
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.database.entity.SkbFun
import org.bitfennec.lime.entity.SkbFunItem
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.inputmethod.EngineState
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.keyboard.container.BaseContainer
import org.bitfennec.lime.inputmethod.InputPipelineTrace
import org.bitfennec.lime.prefs.behavior.KeyboardOneHandedMod
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.service.DecodingInfo
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.keyboard.container.TextEditContainer
import org.bitfennec.lime.keyboard.container.SettingsContainer
import org.bitfennec.lime.keyboard.container.CandidatesContainer
import org.bitfennec.lime.keyboard.container.ClipBoardContainer
import org.bitfennec.lime.keyboard.container.HandwritingContainer
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.view.widget.layout.CustomLinearLayoutManager
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.utils.DevicesUtils

/**
 * Candidate bar container coordinating candidate strips and menus.
 */
class CandidatesBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {

    private lateinit var mCvListener: CandidateViewListener // Candidate view listener
    private lateinit var mRightArrowBtn: ImageView // Right arrow button
    private lateinit var mCandidatesDataContainer: LinearLayout // Candidate container
    private lateinit var mPreeditView: CandidatesPreeditView
    private lateinit var mCandidatesMenuContainer: LinearLayout // Menu container
    private lateinit var mIvMenuSetting: ImageView
    private lateinit var mMenuRightArrowBtn: ImageView
    private lateinit var mRVCandidates: RecyclerView    // Candidate RecyclerView
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var mRVContainerMenu:RecyclerView   // Candidate menu RecyclerView
    private lateinit var mCandidatesMenuAdapter: CandidatesMenuAdapter
    private lateinit var candidatesData: LinearLayout // Candidate container
    private var activeCandNo:Int = 0
    private var cachedBarMenus: List<SkbFunItem>? = null
    private var barMenuLoadJob: Job? = null
    private var barMenuGeneration = 0L
    private var usesHandwritingCandidateLayout = false
    private var lastFlowState: EngineState? = null
    private var lastFlowCandidates: List<CandidateListItem>? = null
    private var lastFlowContainer: BaseContainer? = null
    private var lastFlowSessionId = -1L
    private var lastFlowAssociate = false
    private var lastFlowPassword = false
    private var lastFlowPrivate = false

    private lateinit var mCandidatesInlineContainer: HorizontalScrollView
    private lateinit var inlineRow: LinearLayout
    private val mInlineSuggestionViews = mutableListOf<View>()
    private lateinit var mCandidatesSuggestionContainer: LinearLayout
    private var mClipboardSuggestionContent: String? = null
    private var mOnClipboardSuggestionClick: (() -> Unit)? = null
    private var predictionDeletePopup: PopupWindow? = null
    private val loadMoreTask = Runnable { checkAndLoadMoreCandidates(mRVCandidates) }

    init {
        minimumHeight = ImeEnvironment.heightForCandidatesArea
    }

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        minimumHeight = ImeEnvironment.heightForCandidatesArea
        initMenuView()
        initCandidateView()
        initInlineContainer()
        initSuggestionContainer()
    }

    private fun initInlineContainer() {
        if (!::mCandidatesInlineContainer.isInitialized) {
            inlineRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
            }
            mCandidatesInlineContainer = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                visibility = GONE
                addView(inlineRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            }
            addView(mCandidatesInlineContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun setInlineSuggestionViews(views: List<View>) {
        initInlineContainer()
        mInlineSuggestionViews.clear()
        inlineRow.removeAllViews()
        if (views.isNotEmpty()) {
            mInlineSuggestionViews.addAll(views)
            for (view in views) {
                val lp = LinearLayout.LayoutParams(
                    view.layoutParams?.width?.takeIf { it > 0 } ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                    view.layoutParams?.height?.takeIf { it > 0 } ?: ImeEnvironment.candidateRowHeight
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                inlineRow.addView(view, lp)
            }
        }
        if (DecodingInfo.isCandidatesEmpty) {
            showCandidates()
        }
    }

    fun clearInlineSuggestions() {
        mInlineSuggestionViews.clear()
        if (::mCandidatesInlineContainer.isInitialized) {
            inlineRow.removeAllViews()
        }
        if (DecodingInfo.isCandidatesEmpty) {
            showCandidates()
        }
    }

    private fun initSuggestionContainer() {
        if (!::mCandidatesSuggestionContainer.isInitialized) {
            mCandidatesSuggestionContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = GONE
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            addView(mCandidatesSuggestionContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun showClipboardSuggestion(content: String, onAction: () -> Unit) {
        initSuggestionContainer()
        mClipboardSuggestionContent = content
        mOnClipboardSuggestionClick = onAction
        renderClipboardSuggestionView(content)
        if (DecodingInfo.isCandidatesEmpty) {
            showCandidates()
        }
    }

    fun clearClipboardSuggestion() {
        if (mClipboardSuggestionContent == null && mOnClipboardSuggestionClick == null) return
        clearClipboardSuggestionSilently()
        if (DecodingInfo.isCandidatesEmpty) {
            showCandidates()
        }
    }

    private fun clearClipboardSuggestionSilently() {
        mClipboardSuggestionContent = null
        mOnClipboardSuggestionClick = null
        if (::mCandidatesSuggestionContainer.isInitialized) {
            mCandidatesSuggestionContainer.removeAllViews()
        }
    }

    private fun renderClipboardSuggestionView(content: String) {
        if (!::mCandidatesSuggestionContainer.isInitialized) return
        mCandidatesSuggestionContainer.removeAllViews()
        val theme = activeTheme

        val pillCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val cardHeight = dp(36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                cardHeight
            )
            val padH = dp(10)
            val padV = dp(3)
            setPadding(padH, padV, padH, padV)

            val bg = GradientDrawable().apply {
                setColor(theme.keyBackgroundColor)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, 120))
            }
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            val rippleColor = ColorStateList.valueOf(theme.keyPressHighlightColor)
            background = RippleDrawable(rippleColor, bg, mask)

            setOnClickListener {
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(this)
                val action = mOnClipboardSuggestionClick
                clearClipboardSuggestion()
                action?.invoke()
            }
        }

        val iconIv = ImageView(context).apply {
            setImageResource(R.drawable.ic_menu_clipboard)
            drawable?.setTint(theme.accentKeyBackgroundColor)
            val iconSize = dp(16)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(6)
            }
        }

        val textTv = EmojiTextView(context).apply {
            val preview = if (content.length > 20) content.take(20) + "…" else content
            text = context.getString(R.string.clipboard_paste_preview, preview)
            setTextColor(theme.keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            isSingleLine = true
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxWidth = (ImeEnvironment.skbWidth * 0.65f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
        }

        val closeIv = ImageView(context).apply {
            setImageResource(R.drawable.ic_menu_close)
            drawable?.setTint(theme.keyTextColor)
            contentDescription = context.getString(R.string.clipboard_dismiss_suggestion)
            alpha = 0.6f
            val closeSize = dp(16)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(closeSize, closeSize)
            setOnClickListener {
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(this)
                clearClipboardSuggestion()
            }
        }

        pillCard.addView(iconIv)
        pillCard.addView(textTv)
        pillCard.addView(closeIv)
        mCandidatesSuggestionContainer.addView(pillCard)
    }

    // Initialize candidate views
    private fun initCandidateView() {
        lastFlowState = null
        if(!::mCandidatesDataContainer.isInitialized) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                visibility = GONE
            }
            mPreeditView = CandidatesPreeditView(context!!)
            candidatesData = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            mRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setImageResource(R.drawable.level_list_candidates_display)
                contentDescription = context.getString(R.string.candidate_header_expanded)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            mRVCandidates = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickListener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                if (::mCvListener.isInitialized) {
                    mCvListener.onClickChoice(position)
                }
            }
            mCandidatesAdapter.setOnItemLongClickListener { _: RecyclerView.Adapter<*>?, view: View?, position: Int ->
                if (view != null) showDeleteCandidateMenu(view, position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dx < 0) return
                    checkAndLoadMoreCandidates(recyclerView)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        checkAndLoadMoreCandidates(recyclerView)
                    }
                }
            })
            this.addView(mCandidatesDataContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        configureCandidateLayout(force = true)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updatePaginationCallback()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePaginationCallback()
    }

    private fun updatePaginationCallback() {
        if (!::mRVCandidates.isInitialized) return
        mRVCandidates.removeCallbacks(loadMoreTask)
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            mRVCandidates.post(loadMoreTask)
        }
    }

    override fun onDetachedFromWindow() {
        if (::mRVCandidates.isInitialized) mRVCandidates.removeCallbacks(loadMoreTask)
        lastFlowState = null
        predictionDeletePopup?.dismiss()
        predictionDeletePopup = null
        super.onDetachedFromWindow()
    }

    private fun checkAndLoadMoreCandidates(recyclerView: RecyclerView) {
        if (!recyclerView.isAttachedToWindow || !recyclerView.isShown ||
            recyclerView.windowVisibility != VISIBLE
        ) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val itemCount = recyclerView.adapter?.itemCount ?: return
        if (firstVisible < 0 || lastVisible < 0 || itemCount == 0) return
        val visibleCount = lastVisible - firstVisible + 1
        if (KeyboardManager.instance.currentContainer !is CandidatesContainer &&
            (itemCount - lastVisible - 1 <= visibleCount * 2 || !recyclerView.canScrollHorizontally(1))
        ) {
            DecodingInfo.requestMoreExpandedCandidates()
        }
    }

    private fun configureCandidateLayout(force: Boolean = false) {
        val useHandwritingLayout = KeyboardManager.instance.currentContainer is HandwritingContainer
        if (!force && useHandwritingLayout == usesHandwritingCandidateLayout) return

        mCandidatesDataContainer.removeAllViews()
        candidatesData.removeAllViews()
        val oneHandedOnLeft = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue() &&
            AppPrefs.getInstance().keyboardSetting.oneHandedMod.getValue() == KeyboardOneHandedMod.LEFT
        val preeditHeight = if (useHandwritingLayout) 0 else ImeEnvironment.heightForcomposingHeader

        mRightArrowBtn.layoutParams = LinearLayout.LayoutParams(dp(40), LayoutParams.MATCH_PARENT, 0f)
        mRightArrowBtn.setOnClickListener { view: View ->
            when (val level = (view as ImageView).drawable.level) {
                2 -> if (::mCvListener.isInitialized) mCvListener.onClickClearCandidate()
                else -> {
                    if (::mCvListener.isInitialized) {
                        mCvListener.onClickMore(level)
                        updateCandidateMoreButton(1 - level)
                    }
                }
            }
        }

        mCandidatesDataContainer.orientation = LinearLayout.VERTICAL
        mPreeditView.chip.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, preeditHeight)
        mPreeditView.updateTextSizeAndHeight(useHandwritingLayout)
        mPreeditView.updateVisibility(DecodingInfo.composingStrForDisplay.isNotEmpty(), useHandwritingLayout)
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        if (oneHandedOnLeft) {
            candidatesData.addView(mRightArrowBtn)
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        } else {
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            candidatesData.addView(mRightArrowBtn)
        }
        if (!useHandwritingLayout) {
            mCandidatesDataContainer.addView(mPreeditView.chip)
        }
        mCandidatesDataContainer.addView(candidatesData)
        usesHandwritingCandidateLayout = useHandwritingLayout
        mCandidatesAdapter.notifyChanged()
        mPreeditView.updateTheme(activeTheme)
    }

    private fun showDeleteCandidateMenu(anchor: View, position: Int) {
        PredictionDeletePopup.showIfDeletable(
            anchor = anchor,
            position = position,
            activePopupRef = { predictionDeletePopup },
            onPopupCreated = { predictionDeletePopup = it },
            onDelete = {
                predictionDeletePopup = null
                if (::mCvListener.isInitialized) {
                    mCvListener.onLongClickChoice(position)
                }
            },
        )
    }

    private suspend fun getBarMenuItems(): List<SkbFunItem> {
        val cached = cachedBarMenus
        if (cached != null) return cached
        val dao = AppDatabase.instance.skbFunDao()
        var barMenus = dao.getALlBarMenu()
        val defaultModes = listOf(
            SkbMenuMode.SwitchKeyboard,
            SkbMenuMode.JianFan,
            SkbMenuMode.Emojicon,
            SkbMenuMode.TextEdit,
            SkbMenuMode.ClipBoard
        )
        val temporaryDefaultModes = listOf(
            SkbMenuMode.SwitchKeyboard,
            SkbMenuMode.ClipBoard,
            SkbMenuMode.Emojicon,
            SkbMenuMode.JianFan,
            SkbMenuMode.SettingsMenu
        )
        // The first fixed-header build stored this transient five-tool order.
        if (barMenus.isEmpty() || barMenus.mapNotNull { SkbMenuMode.decodeOrNull(it.name) } == temporaryDefaultModes) {
            val defaultBarItems = defaultModes.mapIndexed { index, mode ->
                SkbFun(name = mode.name, isKeep = 1, position = index)
            }
            dao.deleteAllBarMenus()
            dao.insertAll(defaultBarItems)
            barMenus = defaultBarItems
        }
        val normalized = barMenus.asSequence()
            .mapNotNull { item ->
                val mode = SkbMenuMode.decodeOrNull(item.name)
                if (mode == null || menuSkbFunsPreset[mode] == null) null else mode
            }
            .distinct()
            .take(5)
            .toList()
        if (normalized.size != barMenus.size || normalized.withIndex().any { (index, mode) -> barMenus[index].name != mode.name || barMenus[index].position != index }) {
            dao.deleteAllBarMenus()
            dao.insertAll(normalized.mapIndexed { index, mode -> SkbFun(name = mode.name, isKeep = 1, position = index) })
        }
        return normalized.mapNotNull { mode -> menuSkbFunsPreset[mode] }
    }

    // Initialize title bar
    fun initMenuView() {
        barMenuGeneration++
        cachedBarMenus = null
        if(!::mCandidatesMenuContainer.isInitialized) {
            mCandidatesMenuContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mIvMenuSetting = ImageView(context).apply {
                setImageResource(R.drawable.ic_lime_toolbar_mark)
                contentDescription = context.getString(R.string.skb_item_custom)
                isClickable = true
                isEnabled = true
                setOnClickListener { if (::mCvListener.isInitialized) mCvListener.onClickMenu(SkbMenuMode.SettingsMenu) }
            }
            mRVContainerMenu = RecyclerView(context).apply {
                setItemAnimator(null)
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutManager = GridLayoutManager(context, 5)
            }
            mCandidatesMenuAdapter = CandidatesMenuAdapter(context)
            mCandidatesMenuAdapter.setOnItemClickListener { _: RecyclerView.Adapter<*>?, view: View?, position: Int ->
                val settingsContainer = KeyboardManager.instance.currentContainer as? SettingsContainer
                if (settingsContainer != null && settingsContainer.isCustomDragMode()) {
                    val item = mCandidatesMenuAdapter.items.getOrNull(position)
                    if (item != null) {
                        CoroutineScope(Dispatchers.Main.immediate).launch {
                            try {
                                AppDatabase.instance.skbFunDao().deleteBarMenu(item.skbMenuMode.name)
                                refreshBarMenus()
                                settingsContainer.refreshCustomPool()
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    val skbMenuMode = mCandidatesMenuAdapter.getMenuMode(position)
                    if (skbMenuMode != null) onClickMenu(skbMenuMode)
                }
            }
            mRVContainerMenu.setAdapter(mCandidatesMenuAdapter)
            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    val container = KeyboardManager.instance.currentContainer
                    return if (container is SettingsContainer && container.isCustomDragMode() && mCandidatesMenuAdapter.itemCount > 1) {
                        makeMovementFlags(ItemTouchHelper.START or ItemTouchHelper.END, 0)
                    } else {
                        0
                    }
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    oldHolder: RecyclerView.ViewHolder,
                    targetHolder: RecyclerView.ViewHolder
                ): Boolean {
                    val fromPos = oldHolder.bindingAdapterPosition
                    val toPos = targetHolder.bindingAdapterPosition
                    if (fromPos < 0 || toPos < 0 || fromPos >= mCandidatesMenuAdapter.itemCount || toPos >= mCandidatesMenuAdapter.itemCount) return false

                    mCandidatesMenuAdapter.swapItems(fromPos, toPos)
                    return true
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        DevicesUtils.tryVibrate(viewHolder?.itemView, HapticEvent.LONG_PRESS)
                        viewHolder?.itemView?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.alpha(0.85f)?.setDuration(150)?.start()
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.alpha(1.0f)?.setDuration(150)?.start()

                    // Persist the final list order to database
                    val finalItems = mCandidatesMenuAdapter.items
                    barMenuGeneration++
                    cachedBarMenus = finalItems
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        try {
                            finalItems.forEachIndexed { index, item ->
                                AppDatabase.instance.skbFunDao().insert(SkbFun(name = item.skbMenuMode.name, isKeep = 1, position = index))
                            }
                        } catch (_: Exception) {}
                    }
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = true

                override fun isLongPressDragEnabled() = true
            })
            itemTouchHelper.attachToRecyclerView(mRVContainerMenu)
            mMenuRightArrowBtn = ImageView(context).apply {
                setImageResource(R.drawable.ic_menu_arrow_down)
                contentDescription = context.getString(R.string.keyboard_iv_menu_close)
                isClickable = true
                isEnabled = true
                setOnClickListener { if (::mCvListener.isInitialized) mCvListener.onClickMenu(SkbMenuMode.CloseSKB) }
            }
            mCandidatesMenuContainer.addView(mIvMenuSetting)
            mCandidatesMenuContainer.addView(mRVContainerMenu)
            mCandidatesMenuContainer.addView(mMenuRightArrowBtn)
            this.addView(
                mCandidatesMenuContainer,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        val menuHeight = ImeEnvironment.heightForCandidatesArea
        val sideButtonWidth = dp(48)
        val sideButtonHorizontalPadding = dp(7)
        val sideButtonVerticalPadding = dp(9)
        mIvMenuSetting.layoutParams = LinearLayout.LayoutParams(sideButtonWidth, menuHeight)
        mMenuRightArrowBtn.layoutParams = LinearLayout.LayoutParams(sideButtonWidth, menuHeight)
        mIvMenuSetting.setPadding(sideButtonHorizontalPadding, sideButtonVerticalPadding, sideButtonHorizontalPadding, sideButtonVerticalPadding)
        mMenuRightArrowBtn.setPadding(sideButtonHorizontalPadding, sideButtonVerticalPadding, sideButtonHorizontalPadding, sideButtonVerticalPadding)
        mIvMenuSetting.drawable?.setTint(activeTheme.keyTextColor)
        mMenuRightArrowBtn.drawable?.setTint(activeTheme.keyTextColor)
        mRVContainerMenu.layoutParams = LinearLayout.LayoutParams(0, menuHeight, 1f)
        mCandidatesMenuAdapter.notifyChanged()  // Refresh menu items after expanding dropdown
    }

    private fun onClickMenu(skbMenuMode: SkbMenuMode) {
        if (::mCvListener.isInitialized) mCvListener.onClickMenu(skbMenuMode)
    }

    fun scheduleShowCandidates() {
        showCandidates()
    }

    fun refreshBarMenus() {
        barMenuGeneration++
        cachedBarMenus = null
        val container = KeyboardManager.instance.currentContainer
        mCandidatesMenuAdapter.isEditMode = (container as? SettingsContainer)?.isCustomDragMode() == true
        showCandidates()
    }

    private fun updateCandidateMoreButton(level: Int) {
        mRightArrowBtn.drawable.setLevel(level)
        mRightArrowBtn.contentDescription = context.getString(
            when (level) {
                1 -> R.string.candidate_header_collapse
                2 -> R.string.candidate_header_clear
                else -> R.string.candidate_header_expanded
            }
        )
    }

    fun clearComposingSynchronous() {
        lastFlowState = null
        mPreeditView.clearSynchronous()
        setSlot(resolveSlot())
        mCandidatesAdapter.setCandidates(emptyList(), 0)
        mCandidatesMenuAdapter.notifyChanged()
    }

    fun showCandidates(skipUnchanged: Boolean = false) {
        val engineState = EnginePipeline.stateFlow.value
        val candidates = DecodingInfo.candidates
        val container = KeyboardManager.instance.currentContainer
        val sessionId = EnginePipeline.currentSessionId
        val isAssociate = DecodingInfo.isAssociate
        val isPassword = InputModeSwitcher.isPassword
        val isPrivate = InputModeSwitcher.isPrivateOrSensitive
        // Only Flow notifications may skip rendering; explicit UI refreshes always run.
        // Reference checks avoid scanning candidate lists on the input hot path.
        if (skipUnchanged && lastFlowState === engineState &&
            lastFlowCandidates === candidates && lastFlowContainer === container &&
            lastFlowSessionId == sessionId && lastFlowAssociate == isAssociate &&
            lastFlowPassword == isPassword && lastFlowPrivate == isPrivate
        ) return
        lastFlowState = null
        val renderStartedAtNanos = InputPipelineTrace.mark()
        predictionDeletePopup?.dismiss()
        predictionDeletePopup = null
        configureCandidateLayout()
        val composing = if (InputModeSwitcher.isPassword) "" else DecodingInfo.composingStrForDisplay
        val isComposing = composing.isNotEmpty()
        val isEngineUnavailable = !engineState.engineReady || engineState.stripMode == org.bitfennec.lime.inputmethod.predict.CandidateStripMode.UNAVAILABLE
        if (isEngineUnavailable && isComposing) {
            mPreeditView.render(context.getString(R.string.rime_engine_init_failed))
            mPreeditView.updateVisibility(true, usesHandwritingCandidateLayout)
            mCandidatesAdapter.setCandidates(emptyList(), 0)
            showViewVisibility(mCandidatesDataContainer)
            return
        }
        mPreeditView.render(composing)
        mPreeditView.updateVisibility(isComposing, usesHandwritingCandidateLayout)
        mCandidatesMenuAdapter.isEditMode = (container as? SettingsContainer)?.isCustomDragMode() == true
        setSlot(resolveSlot())
        activeCandNo = 0
        if (mCandidatesAdapter.setCandidates(
                if (InputModeSwitcher.isPassword) emptyList() else DecodingInfo.candidates,
                activeCandNo,
            )
        ) {
            mRVCandidates.scrollToPosition(0)
        }
        updatePaginationCallback()
        if (skipUnchanged) {
            lastFlowState = engineState
            lastFlowCandidates = candidates
            lastFlowContainer = container
            lastFlowSessionId = sessionId
            lastFlowAssociate = isAssociate
            lastFlowPassword = isPassword
            lastFlowPrivate = isPrivate
        }
        val state = EnginePipeline.stateFlow.value
        InputPipelineTrace.candidateRendered(
            sessionId = state.sessionId,
            traceSequence = state.traceSequence,
            candidateCount = DecodingInfo.candidateSize,
            startedAtNanos = renderStartedAtNanos,
        )
    }

    private fun resolveSlot(): CandidatesBarSlot {
        val container = KeyboardManager.instance.currentContainer
        return CandidatesBarSlotResolver.resolve(
            isClipboardContainer = container is ClipBoardContainer,
            isExpandedContainer = container is CandidatesContainer,
            isTextEditContainer = container is TextEditContainer,
            isComposing = !InputModeSwitcher.isPassword &&
                (DecodingInfo.composingStrForDisplay.isNotEmpty() || !DecodingInfo.isEngineFinish),
            hasCandidates = !InputModeSwitcher.isPassword && !DecodingInfo.isCandidatesEmpty,
            isPrivateOrSensitive = InputModeSwitcher.isPrivateOrSensitive,
            isPassword = InputModeSwitcher.isPassword,
            hasClipboardSuggestion = mClipboardSuggestionContent != null,
            hasInlineSuggestions = mInlineSuggestionViews.isNotEmpty(),
        )
    }

    private fun setSlot(slot: CandidatesBarSlot) {
        when (slot) {
            CandidatesBarSlot.CANDIDATE_ROW -> {
                clearClipboardSuggestionSilently()
                mRVCandidates.visibility = VISIBLE
                updateCandidateMoreButton(
                    if (KeyboardManager.instance.currentContainer is CandidatesContainer) 1
                    else if (DecodingInfo.isAssociate) 2 else 0
                )
                showViewVisibility(mCandidatesDataContainer)
            }
            CandidatesBarSlot.SUGGESTION_PILL -> {
                updateCandidateMoreButton(0)
                mRVCandidates.visibility = VISIBLE
                showViewVisibility(mCandidatesSuggestionContainer)
            }
            CandidatesBarSlot.INLINE_AUTOFILL -> {
                updateCandidateMoreButton(0)
                mRVCandidates.visibility = VISIBLE
                showViewVisibility(mCandidatesInlineContainer)
            }
            CandidatesBarSlot.IDLE_TOOLBAR -> {
                updateCandidateMoreButton(0)
                mRVCandidates.visibility = VISIBLE
                val cached = cachedBarMenus
                if (cached != null) {
                    mCandidatesMenuAdapter.items = cached
                } else {
                    val defaultModes = listOf(
                        SkbMenuMode.SwitchKeyboard,
                        SkbMenuMode.JianFan,
                        SkbMenuMode.Emojicon,
                        SkbMenuMode.TextEdit,
                        SkbMenuMode.ClipBoard
                    )
                    mCandidatesMenuAdapter.items = defaultModes.mapNotNull { menuSkbFunsPreset[it] }
                    if (barMenuLoadJob?.isActive != true) {
                        val generation = barMenuGeneration
                        // Normalization also writes defaults; let it finish and discard stale results.
                        // Queue on Main so the Job is assigned before its body starts.
                        barMenuLoadJob = CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val items = getBarMenuItems()
                                if (generation == barMenuGeneration) {
                                    cachedBarMenus = items
                                    mCandidatesMenuAdapter.items = items
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Keep the displayed fallback; a later refresh can retry.
                            } finally {
                                barMenuLoadJob = null
                            }
                            if (generation != barMenuGeneration && cachedBarMenus == null &&
                                isAttachedToWindow && resolveSlot() == CandidatesBarSlot.IDLE_TOOLBAR
                            ) {
                                showCandidates()
                            }
                        }
                    }
                }
                showViewVisibility(mCandidatesMenuContainer)
            }
        }
    }

    /**
     * Updates highlighted candidate.
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (!DecodingInfo.isCandidatesEmpty) {
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(--activeCandNo <= 0) activeCandNo = 0
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(++activeCandNo > DecodingInfo.candidateSize) activeCandNo = DecodingInfo.candidateSize
                }
            }
            mCandidatesAdapter.activeCandidates(activeCandNo)
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * Obtains highlighted candidate.
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo -1 else 0
    }

    /**
     * Whether candidate selection action occurred.
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasure = MeasureSpec.makeMeasureSpec(ImeEnvironment.heightForCandidatesArea, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(ImeEnvironment.skbWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    private fun showViewVisibility(candidatesContainer: View) {
        val containers = listOfNotNull(
            if (::mCandidatesMenuContainer.isInitialized) mCandidatesMenuContainer else null,
            if (::mCandidatesDataContainer.isInitialized) mCandidatesDataContainer else null,
            if (::mCandidatesInlineContainer.isInitialized) mCandidatesInlineContainer else null,
            if (::mCandidatesSuggestionContainer.isInitialized) mCandidatesSuggestionContainer else null
        )
        for (view in containers) {
            if (view === candidatesContainer) {
                if (view.visibility != VISIBLE) {
                    view.alpha = 1f
                    view.visibility = VISIBLE
                }
            } else {
                if (view.visibility != GONE) {
                    view.visibility = GONE
                }
            }
        }
    }

    // Refresh theme styling
    fun updateTheme(textColor: Int) {
        initMenuView()
        initCandidateView()
        initSuggestionContainer()
        val currentContent = mClipboardSuggestionContent
        if (currentContent != null) {
            renderClipboardSuggestionView(currentContent)
        }
        val theme = activeTheme
        mPreeditView.updateTheme(theme)
        mRightArrowBtn.drawable.setTint(textColor)
        mIvMenuSetting.drawable?.setTint(textColor)
        mMenuRightArrowBtn.drawable?.setTint(textColor)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    fun showPreviewCandidates(previewCandidates: List<org.bitfennec.lime.core.CandidateListItem> = listOf(
        org.bitfennec.lime.core.CandidateListItem("liuqing", "留青"),
        org.bitfennec.lime.core.CandidateListItem("lime", "LIME"),
        org.bitfennec.lime.core.CandidateListItem("shurufa", "输入法"),
        org.bitfennec.lime.core.CandidateListItem("zhuti", "主题"),
        org.bitfennec.lime.core.CandidateListItem("yulan", "预览"),
        org.bitfennec.lime.core.CandidateListItem("waiguan", "外观")
    ), theme: Theme = activeTheme) {
        initMenuView()
        initCandidateView()
        mCandidatesAdapter.previewTheme = theme
        mRightArrowBtn.drawable.setTint(theme.keyTextColor)
        mPreeditView.updateVisibility(false, usesHandwritingCandidateLayout)
        mCandidatesAdapter.previewCandidates = previewCandidates
        showViewVisibility(mCandidatesDataContainer)
        mCandidatesAdapter.notifyChanged()
    }
}
