package org.bitfennec.lime.candidate

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import org.bitfennec.lime.adapter.CandidatesBarAdapter
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.container.CandidatesContainer
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.inputmethod.EngineState
import org.bitfennec.lime.keyboard.container.BaseContainer
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.service.DecodingInfo
import org.bitfennec.lime.view.widget.layout.CustomLinearLayoutManager
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.view.PredictionDeletePopup
import org.bitfennec.lime.utils.dp

/**
 * Floating candidate bar container.
 */
class FloatCandidateBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {
    private var mFloatCandidateBarWidth: Int = 0

    private lateinit var mCvListener: CandidateViewListener // Candidate view listener
    private lateinit var mCandidatesDataContainer: LinearLayout // Candidate container
    private lateinit var mComposingView: TextView // Composing view displaying raw pinyin
    private lateinit var mRVCandidates: RecyclerView    // Candidate RecyclerView
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var candidatesData: LinearLayout // Candidate container
    private var activeCandNo:Int = 0
    private var predictionDeletePopup: PopupWindow? = null
    private val loadMoreTask = Runnable { checkAndLoadMoreCandidates(mRVCandidates) }
    private var lastFlowState: EngineState? = null
    private var lastFlowCandidates: List<CandidateListItem>? = null
    private var lastFlowContainer: BaseContainer? = null
    private var lastFlowSessionId = -1L
    private var lastFlowPassword = false

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        mFloatCandidateBarWidth = (if(ImeEnvironment.isLandscape)ImeEnvironment.screenHeight else ImeEnvironment.screenWidth) - dp(40)
        initCandidateView()
    }

    // Initialize candidate views
    private fun initCandidateView() {
        lastFlowState = null
        if(!::mCandidatesDataContainer.isInitialized) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            mComposingView = TextView(context).apply {
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(10), 0, dp(10), dp(1))
                val fm = paint.fontMetricsInt
                lineHeight = fm.descent - fm.ascent + dp(2)
            }
            candidatesData = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mRVCandidates = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickListener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
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
            mCandidatesDataContainer.addView(mComposingView)
            mCandidatesDataContainer.addView(candidatesData)
            this.addView(mCandidatesDataContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            (mRVCandidates.parent as ViewGroup).removeView(mRVCandidates)
        }
        val candidatesHeight = ImeEnvironment.hardwareCandidatesRowHeight
        mComposingView.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ImeEnvironment.heightForcomposing)
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, candidatesHeight)
        candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, candidatesHeight, 1f))
        mComposingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeEnvironment.composingTextSize)
        mCandidatesAdapter.notifyChanged()
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

    /**
     * Displays candidate list.
     */
    fun showCandidates(skipUnchanged: Boolean = false) {
        val engineState = EnginePipeline.stateFlow.value
        val candidates = DecodingInfo.candidates
        val container = KeyboardManager.instance.currentContainer
        val sessionId = EnginePipeline.currentSessionId
        val hideCandidates = InputModeSwitcher.isPassword
        // Only deduplicate Flow notifications; explicit refreshes always render.
        if (skipUnchanged && lastFlowState === engineState &&
            lastFlowCandidates === candidates && lastFlowContainer === container &&
            lastFlowSessionId == sessionId && lastFlowPassword == hideCandidates
        ) return
        lastFlowState = null
        predictionDeletePopup?.dismiss()
        predictionDeletePopup = null
        val composing = if (hideCandidates) "" else DecodingInfo.composingStrForDisplay
        val isComposing = composing.isNotEmpty()
        if (!engineState.engineReady) {
            mCandidatesAdapter.setCandidates(emptyList(), 0)
            if (isComposing) {
                mComposingView.text = context.getString(R.string.rime_engine_init_failed)
                this.visibility = VISIBLE
            } else {
                this.visibility = GONE
            }
            return
        }
        mComposingView.text = composing
        if (hideCandidates || candidates.isEmpty()) {
            this.visibility = GONE
        } else {
            this.visibility = VISIBLE
        }
        activeCandNo = 0
        if (mCandidatesAdapter.setCandidates(if (hideCandidates) emptyList() else candidates, activeCandNo)) {
            mRVCandidates.scrollToPosition(0)
        }
        updatePaginationCallback()
        if (skipUnchanged) {
            lastFlowState = engineState
            lastFlowCandidates = candidates
            lastFlowContainer = container
            lastFlowSessionId = sessionId
            lastFlowPassword = hideCandidates
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
            mCandidatesAdapter.setCandidates(DecodingInfo.candidates, activeCandNo)
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * Obtains highlighted candidate.
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo - 1 else 0
    }

    /**
     * Whether candidate selection action occurred.
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasure = MeasureSpec.makeMeasureSpec(ImeEnvironment.hardwareCandidatesAreaHeight, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(mFloatCandidateBarWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    // Refresh theme styling
    fun updateTheme(textColor: Int) {
        initCandidateView()
        mComposingView.setTextColor(textColor)
        val drawable = GradientDrawable()
        drawable.setShape(GradientDrawable.RECTANGLE)
        drawable.setColor(ThemeManager.activeTheme.keyboardColor)
        val cornerRadiusInPx = 20f
        drawable.setCornerRadius(cornerRadiusInPx)
        background = drawable
        mCandidatesAdapter.notifyChanged()
    }
}
