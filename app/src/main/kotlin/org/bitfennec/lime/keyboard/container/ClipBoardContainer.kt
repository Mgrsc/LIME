package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.ClipBoardAdapter
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.database.entity.Clipboard
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode
import org.bitfennec.lime.prefs.behavior.PopupMenuMode
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.service.ClipboardHelper
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.collectWhenStarted
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.view.widget.layout.CustomGridLayoutManager

/**
 * Clipboard history container (dual columns, pinned items first, context menu).
 */
@SuppressLint("ViewConstructor")
class ClipBoardContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {

    private val themedContext: Context = ContextThemeWrapper(inputView.context ?: context, R.style.Theme_AppTheme)
    private var mRootFrameLayout: FrameLayout? = null
    private var mRVSymbolsView: RecyclerView? = null
    private var mTVEmptyLabel: TextView? = null

    private var continuousPasteBtn: TextView? = null
    private var clearButton: TextView? = null
    private var clearConfirmPrompt: TextView? = null
    private var clearCancelBtn: TextView? = null
    private var clearConfirmBtn: TextView? = null
    private var actionsLayout: LinearLayout? = null
    private var clearConfirmationLayout: LinearLayout? = null
    private var clearInProgress = false

    private fun createChipBackground(ctx: Context, color: Int, radiusDp: Float): RippleDrawable {
        val radiusPx = ctx.dp(radiusDp).toFloat()
        val content = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radiusPx
        }
        val rippleColor = ColorStateList.valueOf(activeTheme.keyTextColor).withAlpha(36)
        return RippleDrawable(rippleColor, content, mask)
    }

    private var mAdapter: ClipBoardAdapter? = null
    private val mAllClipboardList: MutableList<Clipboard> = mutableListOf()

    init {
        initView(themedContext)
    }

    private fun initView(ctx: Context) {
        val theme = activeTheme
        mRootFrameLayout = FrameLayout(ctx).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        mRVSymbolsView = RecyclerView(ctx).apply {
            itemAnimator = null
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                        activeActionPopup?.dismiss()
                        activeActionPopup = null
                    }
                }
            })
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val position = viewHolder.bindingAdapterPosition
                val curList = mAdapter?.getDataList() ?: return 0
                if (position in curList.indices && curList[position].isKeep == 1) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val curList = mAdapter?.getDataList() ?: return
                if (position in curList.indices) {
                    val data = curList[position]
                    if (data.isKeep == 1) return
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        AppDatabase.instance.clipboardDao().deleteByContent(data.content)
                    }
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(mRVSymbolsView)

        mRootFrameLayout?.addView(mRVSymbolsView)

        // Empty state hint
        mTVEmptyLabel = TextView(ctx).apply {
            setText(R.string.clipboard_empty)
            gravity = Gravity.CENTER
            setTextColor(Color.argb(120, Color.red(theme.keyTextColor), Color.green(theme.keyTextColor), Color.blue(theme.keyTextColor)))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mRootFrameLayout?.addView(mTVEmptyLabel)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val pasteBtn = TextView(ctx).apply {
            setText(R.string.clipboard_continuous_paste)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
            compoundDrawablePadding = ctx.dp(4)
            minHeight = ctx.dp(28)
            setOnClickListener {
                ClipboardHelper.isLocked = !ClipboardHelper.isLocked
                updateLockState()
                inputView.updateCandidateBar()
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(this@ClipBoardContainer)
            }
        }
        continuousPasteBtn = pasteBtn
        updateLockState()

        val clearBtn = TextView(ctx).apply {
            setText(R.string.clipboard_clear_unpinned)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            val icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_menu_delete)?.mutate()
            val iconSize = ctx.dp(14)
            icon?.setBounds(0, 0, iconSize, iconSize)
            icon?.setTint(theme.keyTextColor)
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = ctx.dp(4)
            setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
            minHeight = ctx.dp(28)
            setTextColor(theme.keyTextColor)
            background = createChipBackground(ctx, ColorUtils.setAlphaComponent(theme.keyBackgroundColor, 180), 14f)
            isEnabled = false
            alpha = 0.35f
            setOnClickListener {
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(this@ClipBoardContainer)
                activeActionPopup?.dismiss()
                actionsLayout?.visibility = GONE
                clearConfirmationLayout?.visibility = VISIBLE
            }
        }
        clearButton = clearBtn

        actionsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                ctx.dp(34)
            )
            addView(pasteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val spacer = View(ctx)
            addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))
            addView(clearBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Use inline confirmation banner instead of Dialog/PopupWindow to avoid focus theft or IME window dismissal.
        clearConfirmationLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                ctx.dp(34)
            )
            val prompt = TextView(ctx).apply {
                setText(R.string.clipboard_clear_inline_confirm)
                setTextColor(theme.keyTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            clearConfirmPrompt = prompt
            addView(prompt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val cancelBtn = TextView(ctx).apply {
                setText(android.R.string.cancel)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setTextColor(theme.keyTextColor)
                setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
                minHeight = ctx.dp(28)
                background = createChipBackground(ctx, ColorUtils.setAlphaComponent(theme.keyBackgroundColor, 200), 14f)
                setOnClickListener {
                    DevicesUtils.tryPlayKeyDown()
                    clearConfirmationLayout?.visibility = GONE
                    actionsLayout?.visibility = VISIBLE
                }
            }
            clearCancelBtn = cancelBtn
            addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val confirmBtn = TextView(ctx).apply {
                setText(R.string.clipboard_clear_action)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                val icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_menu_delete)?.mutate()
                val iconSize = ctx.dp(13)
                icon?.setBounds(0, 0, iconSize, iconSize)
                icon?.setTint(Color.WHITE)
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = ctx.dp(3)
                setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
                minHeight = ctx.dp(28)
                background = createChipBackground(ctx, COLOR_DESTRUCTIVE, 14f)
                setOnClickListener {
                    DevicesUtils.tryPlayKeyDown()
                    DevicesUtils.tryVibrate(this@ClipBoardContainer)
                    clearConfirmationLayout?.visibility = GONE
                    actionsLayout?.visibility = VISIBLE
                    if (clearInProgress) return@setOnClickListener
                    clearInProgress = true
                    clearButton?.isEnabled = false
                    clearButton?.alpha = 0.35f
                    inputView.service.lifecycleScope.launch {
                        try {
                            AppDatabase.instance.clipboardDao().deleteAllExceptKeep()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Toast.makeText(ctx, R.string.operation_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            clearInProgress = false
                            val hasItems = mAllClipboardList.any { it.isKeep == 0 }
                            clearButton?.isEnabled = hasItems
                            clearButton?.alpha = if (hasItems) 1.0f else 0.35f
                        }
                    }
                }
            }
            clearConfirmBtn = confirmBtn
            val confirmLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(8)
            }
            addView(confirmBtn, confirmLp)
        }

        val headerBar = FrameLayout(ctx).apply {
            setPadding(ctx.dp(6), ctx.dp(3), ctx.dp(6), ctx.dp(3))
            addView(actionsLayout)
            addView(clearConfirmationLayout)
        }
        panel.addView(headerBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(mRootFrameLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        this.addView(panel)
    }

    private var clipboardCollectJob: Job? = null
    private var pendingPinnedItem: Clipboard? = null
    private var clipboardGeneration = 0
    private var activeActionPopup: PopupWindow? = null

    private fun canUpdateClipboard(): Boolean =
        mAdapter != null && isAttachedToWindow && isShown && windowVisibility == VISIBLE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateClipboardCollection()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateClipboardCollection()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateClipboardCollection()
    }

    override fun onDetachedFromWindow() {
        stopCollectingClipboard()
        super.onDetachedFromWindow()
    }

    private fun updateClipboardCollection() {
        if (canUpdateClipboard()) startCollectingClipboard() else stopCollectingClipboard()
    }

    private fun stopCollectingClipboard() {
        clearConfirmationLayout?.visibility = GONE
        actionsLayout?.visibility = VISIBLE
        clipboardGeneration++
        clipboardCollectJob?.cancel()
        clipboardCollectJob = null
        pendingPinnedItem = null
        activeActionPopup?.dismiss()
        activeActionPopup = null
    }

    private fun startCollectingClipboard() {
        if (!canUpdateClipboard() || clipboardCollectJob?.isActive == true) return
        val generation = clipboardGeneration
        clipboardCollectJob = inputView.service.collectWhenStarted(AppDatabase.instance.clipboardDao().getAllFlow()) { list ->
            if (!canUpdateClipboard() || generation != clipboardGeneration) return@collectWhenStarted
            mAllClipboardList.clear()
            mAllClipboardList.addAll(list)
            val hasUnpinned = !clearInProgress && list.any { it.isKeep == 0 }
            clearButton?.isEnabled = hasUnpinned
            clearButton?.alpha = if (hasUnpinned) 1.0f else 0.35f
            if (list.none { it.isKeep == 0 }) {
                clearConfirmationLayout?.visibility = GONE
                actionsLayout?.visibility = VISIBLE
            }
            mAdapter?.updateData(mAllClipboardList) {
                if (canUpdateClipboard() && generation == clipboardGeneration) {
                    (mRVSymbolsView?.layoutManager as? StaggeredGridLayoutManager)?.invalidateSpanAssignments()
                    // Keep the intent until a committed list contains the pinned version.
                    val pinnedItem = pendingPinnedItem
                    if (pinnedItem != null && list.any { it == pinnedItem }) {
                        pendingPinnedItem = null
                        mRVSymbolsView?.scrollToPosition(0)
                    }
                }
            }
            if (mAllClipboardList.isEmpty()) {
                mTVEmptyLabel?.visibility = VISIBLE
            } else {
                mTVEmptyLabel?.visibility = GONE
            }
        }
    }

    fun updateLockState() {
        val isLocked = ClipboardHelper.isLocked
        val theme = activeTheme
        continuousPasteBtn?.let { btn ->
            val icon = AppCompatResources.getDrawable(themedContext, R.drawable.icon_symbol_lock)?.mutate()
            val iconSize = themedContext.dp(14)
            icon?.setBounds(0, 0, iconSize, iconSize)
            if (isLocked) {
                btn.background = createChipBackground(themedContext, theme.accentKeyBackgroundColor, 14f)
                btn.setTextColor(theme.accentKeyTextColor)
                icon?.setTint(theme.accentKeyTextColor)
            } else {
                val normalBg = ColorUtils.setAlphaComponent(theme.keyBackgroundColor, 180)
                btn.background = createChipBackground(themedContext, normalBg, 14f)
                val textColor = ColorUtils.setAlphaComponent(theme.keyTextColor, 200)
                btn.setTextColor(textColor)
                icon?.setTint(textColor)
            }
            btn.setCompoundDrawablesRelative(icon, null, null, null)
            btn.stateDescription = themedContext.getString(
                if (isLocked) R.string.symbol_locked else R.string.symbol_unlocked
            )
        }
    }

    override fun updateSkbLayout() {
        super.updateSkbLayout()
        updateLockState()
        val theme = activeTheme
        clearButton?.let { btn ->
            val icon = AppCompatResources.getDrawable(themedContext, R.drawable.ic_menu_delete)?.mutate()
            val iconSize = themedContext.dp(14)
            icon?.setBounds(0, 0, iconSize, iconSize)
            icon?.setTint(theme.keyTextColor)
            btn.setCompoundDrawablesRelative(icon, null, null, null)
            val normalBg = ColorUtils.setAlphaComponent(theme.keyBackgroundColor, 180)
            btn.background = createChipBackground(themedContext, normalBg, 14f)
            btn.setTextColor(theme.keyTextColor)
        }
        clearConfirmPrompt?.setTextColor(theme.keyTextColor)
        clearCancelBtn?.let { btn ->
            btn.setTextColor(theme.keyTextColor)
            btn.background = createChipBackground(themedContext, ColorUtils.setAlphaComponent(theme.keyBackgroundColor, 200), 14f)
        }
        clearConfirmBtn?.background = createChipBackground(themedContext, COLOR_DESTRUCTIVE, 14f)
    }

    /**
     * Shows clipboard container.
     */
    fun showClipBoardView() {
        updateLockState()
        clearConfirmationLayout?.visibility = GONE
        actionsLayout?.visibility = VISIBLE
        activeActionPopup?.dismiss()
        activeActionPopup = null
        val isLandscape = ImeEnvironment.isLandscape
        val count = if (isLandscape) 4 else 2
        val layoutMode = AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()

        mAdapter?.setLayoutMode(layoutMode)

        val currentLm = mRVSymbolsView?.layoutManager
        val needNewLm = when (layoutMode) {
            ClipboardLayoutMode.ListView -> currentLm?.javaClass != LinearLayoutManager::class.java
            ClipboardLayoutMode.FlexboxView -> currentLm !is StaggeredGridLayoutManager || currentLm.spanCount != count
            ClipboardLayoutMode.GridView -> currentLm !is CustomGridLayoutManager || currentLm.spanCount != count
        }
        if (needNewLm) {
            val manager: RecyclerView.LayoutManager = when (layoutMode) {
                ClipboardLayoutMode.ListView -> LinearLayoutManager(themedContext, LinearLayoutManager.VERTICAL, false)
                ClipboardLayoutMode.FlexboxView -> StaggeredGridLayoutManager(count, StaggeredGridLayoutManager.VERTICAL)
                ClipboardLayoutMode.GridView -> CustomGridLayoutManager(themedContext, count)
            }
            mRVSymbolsView?.layoutManager = manager
        }

        if (mAdapter == null) {
            mAdapter = ClipBoardAdapter(themedContext).apply {
                onItemClickListener = { item, _ ->
                    inputView.responseLongKeyEvent(Pair(PopupMenuMode.Text, item.content))
                    if (!ClipboardHelper.isLocked) {
                        KeyboardManager.instance.switchKeyboard()
                    }
                }
                onItemLongClickListener = { item, _, view ->
                    showItemActionMenu(item, view)
                }
            }
            mRVSymbolsView?.adapter = mAdapter
        }
        updateClipboardCollection()
    }

    private fun showItemActionMenu(item: Clipboard, anchor: View) {
        if (!anchor.isAttachedToWindow || !isAttachedToWindow) return
        activeActionPopup?.dismiss()
        activeActionPopup = null
        try {
            val theme = activeTheme
            val textColor = if (theme.keyTextColor != 0) theme.keyTextColor else if (theme.isDark) Color.WHITE else Color.BLACK
            val destructiveColor = COLOR_DESTRUCTIVE
            val rippleColor = if (theme.keyPressHighlightColor != 0) theme.keyPressHighlightColor else Color.argb(40, 128, 128, 128)
            val dividerColor = ColorUtils.setAlphaComponent(textColor, 40)

            val popupBgColor = if (theme.popupBackgroundColor != 0) {
                theme.popupBackgroundColor
            } else if (theme.isDark) {
                0xff2a2a2a.toInt()
            } else {
                Color.WHITE
            }
            val strokeColor = if (theme.isDark) Color.argb(40, 255, 255, 255) else Color.argb(30, 0, 0, 0)
            val popupBackground = GradientDrawable().apply {
                setColor(popupBgColor)
                cornerRadius = themedContext.dp(16).toFloat()
                setStroke(themedContext.dp(1), strokeColor)
            }

            val capsule = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = popupBackground
                val padH = themedContext.dp(4)
                val padV = themedContext.dp(4)
                setPadding(padH, padV, padH, padV)
            }

            fun createActionButton(
                iconRes: Int,
                text: String,
                tintColor: Int,
                onClick: () -> Unit,
            ): View {
                val itemMask = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = themedContext.dp(12).toFloat()
                }
                val itemView = LinearLayout(themedContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(themedContext.dp(10), themedContext.dp(6), themedContext.dp(10), themedContext.dp(6))
                    background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, itemMask)
                    isClickable = true
                    isFocusable = false
                }
                val icon = ImageView(themedContext).apply {
                    setImageResource(iconRes)
                    drawable?.mutate()?.setTint(tintColor)
                    layoutParams = LinearLayout.LayoutParams(themedContext.dp(16), themedContext.dp(16)).apply {
                        marginEnd = themedContext.dp(6)
                    }
                }
                val label = TextView(themedContext).apply {
                    this.text = text
                    setTextColor(tintColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                }
                itemView.addView(icon)
                itemView.addView(label)
                itemView.setOnClickListener {
                    activeActionPopup?.dismiss()
                    activeActionPopup = null
                    onClick()
                }
                return itemView
            }

            fun createDivider(): View {
                return View(themedContext).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(themedContext.dp(1), themedContext.dp(14)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                }
            }

            val pinText = if (item.isKeep == 1) themedContext.getString(R.string.clipboard_unpin) else themedContext.getString(R.string.clipboard_pin)
            capsule.addView(createActionButton(R.drawable.ic_menu_pin, pinText, textColor) {
                val isPinning = item.isKeep == 0
                val updatedItem = item.copy(
                    isKeep = 1 - item.isKeep,
                    time = System.currentTimeMillis(),
                )
                pendingPinnedItem = updatedItem.takeIf { isPinning && canUpdateClipboard() }
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    var succeeded = false
                    try {
                        AppDatabase.instance.clipboardDao().update(updatedItem)
                        succeeded = true
                    } finally {
                        if (!succeeded && pendingPinnedItem === updatedItem) {
                            pendingPinnedItem = null
                        }
                    }
                }
            })

            capsule.addView(createDivider())

            capsule.addView(createActionButton(R.drawable.ic_edit_copy, themedContext.getString(R.string.clipboard_copy), textColor) {
                val clipboard = Launcher.instance.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("text", item.content))
            })

            capsule.addView(createDivider())

            capsule.addView(createActionButton(R.drawable.ic_menu_delete, themedContext.getString(R.string.clipboard_delete), destructiveColor) {
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    AppDatabase.instance.clipboardDao().deleteByContent(item.content)
                }
            })

            val popup = PopupWindow(
                capsule,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false,
            ).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                elevation = themedContext.dp(8).toFloat()
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                setOnDismissListener {
                    if (activeActionPopup === this) {
                        activeActionPopup = null
                    }
                }
            }
            activeActionPopup = popup

            capsule.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val popupWidth = capsule.measuredWidth
            val popupHeight = capsule.measuredHeight

            val anchorLocation = IntArray(2)
            anchor.getLocationInWindow(anchorLocation)
            val anchorX = anchorLocation[0]
            val anchorY = anchorLocation[1]

            val containerLocation = IntArray(2)
            getLocationInWindow(containerLocation)
            val containerTop = containerLocation[1]

            val screenWidth = themedContext.resources.displayMetrics.widthPixels
            val targetX = (anchorX + (anchor.width - popupWidth) / 2)
                .coerceIn(themedContext.dp(8), screenWidth - popupWidth - themedContext.dp(8))

            val spacing = themedContext.dp(6)
            val screenHeight = themedContext.resources.displayMetrics.heightPixels
            val targetY = if (anchorY - popupHeight - spacing >= containerTop) {
                anchorY - popupHeight - spacing
            } else {
                anchorY + anchor.height + spacing
            }.coerceIn(containerTop, screenHeight - popupHeight - themedContext.dp(8))

            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, targetX, targetY)
        } catch (_: Exception) {
            activeActionPopup = null
        }
    }

    fun getMenuMode(): SkbMenuMode {
        return SkbMenuMode.ClipBoard
    }

    companion object {
        private const val COLOR_DESTRUCTIVE = 0xffdf5a5a.toInt()
    }
}
