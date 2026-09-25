package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import org.bitfennec.lime.view.widget.layout.CustomFlexboxLayoutManager
import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.CandidatesAdapter
import org.bitfennec.lime.adapter.PrefixAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.database.entity.SideSymbol
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.service.DecodingInfo
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.AppUtil
import org.bitfennec.lime.utils.DevicesUtils
import android.widget.PopupWindow
import org.bitfennec.lime.view.PredictionDeletePopup
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.utils.dp


/**
 * Candidate keyboard container (expandable dynamic-width flow panel).
 * Displays left pinyin sidebar in T9 mode (InputModeSwitcher.isChineseT9).
 */
@SuppressLint("ViewConstructor")
class CandidatesContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private var predictionDeletePopup: PopupWindow? = null
    private var deleteMenuRequestId = 0L
    private var mSideSymbolsPinyin: List<SideSymbol> = emptyList()
    private lateinit var mRVSymbolsView: RecyclerView
    private lateinit var mCandidatesAdapter: CandidatesAdapter
    private var mRVLeftPrefix = inflate(getContext(), R.layout.view_rv_prefix, null) as RecyclerView
    private var lastLoadedCandidatePageNo = Int.MIN_VALUE
    private var requestedNextCandidatePageNo: Int? = null
    private val mLlAddSymbol: LinearLayout = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        }
        gravity = Gravity.CENTER
    }

    init {
        initView(context)
        val ivAddSymbol = ImageView(context).apply {
            setPadding(dp(5))
            setImageResource(R.drawable.ic_menu_setting)
        }
        ivAddSymbol.setOnClickListener { _: View ->
            val arguments = Bundle()
            arguments.putInt("type", 0)
            AppUtil.launchSettingsToPrefix(context, arguments)
        }
        mLlAddSymbol.addView(ivAddSymbol)
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                mSideSymbolsPinyin = AppDatabase.instance.sideSymbolDao().getAllSideSymbolPinyin()
                if (mRVLeftPrefix.adapter != null && DecodingInfo.prefixs.isEmpty()) {
                    updatePrefixsView()
                }
            } catch (_: Exception) {}
        }
    }

    private fun initView(context: Context) {
        mRVSymbolsView = RecyclerView(context).apply {
            id = View.generateViewId()
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        }
        mCandidatesAdapter = CandidatesAdapter(context)
        mCandidatesAdapter.setOnItemClickListener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(this)
            inputView.chooseAndUpdate(position)
        }
        mCandidatesAdapter.setOnItemLongClickListener { _: RecyclerView.Adapter<*>?, view: View?, position: Int ->
            view?.let { showDeleteCandidateMenu(it, position) }
        }
        mRVSymbolsView.adapter = mCandidatesAdapter

        val flexManager = CustomFlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.STRETCH
        }
        mRVSymbolsView.layoutManager = flexManager
        mRVSymbolsView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) {
                    val lastVisible = flexManager.findLastVisibleItemPosition()
                    val totalCount = recyclerView.adapter?.itemCount ?: 0
                    if (totalCount > 0 && lastVisible >= totalCount - 6) {
                        requestMoreCandidates()
                    }
                }
            }
        })

        mRVLeftPrefix.id = View.generateViewId()
        mRVLeftPrefix.setLayoutManager(LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false))
        mRVLeftPrefix.visibility = GONE

        addView(mRVLeftPrefix, ConstraintLayout.LayoutParams((ImeEnvironment.skbWidth * 0.18).toInt(), 0).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
        })

        addView(mRVSymbolsView, ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToEnd = mRVLeftPrefix.id
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private var lastFirstCandidateText: String? = null

    private fun requestMoreCandidates() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
        val pageNo = DecodingInfo.candidatePageNo
        if (!DecodingInfo.hasNextCandidatePage || requestedNextCandidatePageNo == pageNo) return
        requestedNextCandidatePageNo = pageNo
        DecodingInfo.requestMoreExpandedCandidates()
    }

    private val fillViewportTask = Runnable {
        if ((mRVSymbolsView.adapter?.itemCount ?: 0) > 0 && !mRVSymbolsView.canScrollVertically(1)) {
            requestMoreCandidates()
        }
    }

    private fun fillCandidateViewportIfNeeded() {
        mRVSymbolsView.removeCallbacks(fillViewportTask)
        mRVSymbolsView.post(fillViewportTask)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE || !isShown) {
            deleteMenuRequestId++
            predictionDeletePopup?.dismiss()
            predictionDeletePopup = null
        }
        updatePaginationCallback()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            deleteMenuRequestId++
            predictionDeletePopup?.dismiss()
            predictionDeletePopup = null
        }
        updatePaginationCallback()
    }

    private fun updatePaginationCallback() {
        if (!::mRVSymbolsView.isInitialized) return
        mRVSymbolsView.removeCallbacks(fillViewportTask)
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            mRVSymbolsView.post(fillViewportTask)
        }
    }

    override fun onDetachedFromWindow() {
        deleteMenuRequestId++
        mRVSymbolsView.removeCallbacks(fillViewportTask)
        predictionDeletePopup?.dismiss()
        predictionDeletePopup = null
        super.onDetachedFromWindow()
    }

    /**
     * Displays candidate expansion container.
     */
    fun showCandidatesView() {
        if (DecodingInfo.isCandidatesEmpty) {
            lastFirstCandidateText = null
            mCandidatesAdapter.setCandidates(emptyList())
        } else {
            val candidates = DecodingInfo.candidates
            val pageNo = DecodingInfo.candidatePageNo
            if (pageNo != lastLoadedCandidatePageNo) {
                lastLoadedCandidatePageNo = pageNo
                requestedNextCandidatePageNo = null
            }
            val currentFirst = candidates.firstOrNull()?.text
            val isNewComposition = (currentFirst != lastFirstCandidateText)
            lastFirstCandidateText = currentFirst

            mCandidatesAdapter.setCandidates(candidates)
            if (isNewComposition) {
                mRVSymbolsView.scrollToPosition(0)
            }
            if (InputModeSwitcher.isChineseT9) {
                mRVLeftPrefix.visibility = VISIBLE
                updatePrefixsView()
            } else {
                mRVLeftPrefix.visibility = GONE
            }
            fillCandidateViewportIfNeeded()
        }
    }

    // Update left pinyin strip display
    private fun updatePrefixsView() {
        var prefixs = DecodingInfo.prefixs
        val isPrefixs = prefixs.isNotEmpty()
        if (!isPrefixs) {
            prefixs = mSideSymbolsPinyin.map { it.symbolKey }.toTypedArray()
        }
        val footer = if (!isPrefixs) mLlAddSymbol else null
        if ((mRVLeftPrefix.adapter as? PrefixAdapter)?.matchesContent(prefixs, footer) == true) return
        val adapter = PrefixAdapter(
            context,
            prefixs,
            footerView = footer,
            onItemClickListener = { position ->
                if (isPrefixs) {
                    inputView.selectPrefix(position)
                } else {
                    val softKey = SoftKey(label = mSideSymbolsPinyin.map { it.symbolValue }[position])
                    DevicesUtils.tryPlayKeyDown()
                    DevicesUtils.tryVibrate(this)
                    inputView.responseKeyEvent(softKey)
                }
            }
        )
        mRVLeftPrefix.adapter = adapter
    }

    private fun showDeleteCandidateMenu(anchor: View, position: Int) {
        val requestId = ++deleteMenuRequestId
        PredictionDeletePopup.showIfDeletable(
            anchor = anchor,
            position = position,
            activePopupRef = { predictionDeletePopup },
            onPopupCreated = { predictionDeletePopup = it },
            onDelete = {
                predictionDeletePopup = null
                if (DecodingInfo.canRemoveUserPrediction(position)) {
                    DecodingInfo.removeUserPrediction(position)
                } else {
                    DecodingInfo.deleteCandidate(position)
                }
            },
            isRequestValid = {
                requestId == deleteMenuRequestId &&
                    isAttachedToWindow &&
                    isShown &&
                    windowVisibility == VISIBLE
            },
        )
    }
}
