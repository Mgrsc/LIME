package org.bitfennec.lime.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.PrefixAdapter
import org.bitfennec.lime.data.theme.ThemeManager
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
import org.bitfennec.lime.keyboard.KeyboardSurface
import org.bitfennec.lime.keyboard.InputView
import org.bitfennec.lime.keyboard.TextKeyboard
import org.bitfennec.lime.utils.dp

/**
 * T9 keyboard container hosting TextKeyboard and pinyin sidebar.
 */
@SuppressLint("ViewConstructor")
open class T9TextContainer(
    context: Context?,
    inputView: InputView,
    private val skbValue: Int,
    private val keyboardSurface: KeyboardSurface,
) : InputBaseContainer(context, inputView) {
    private var mSideSymbolsPinyin: List<SideSymbol> = emptyList()
    // Sidebar symbols and candidate pinyin view
    private val mRVLeftPrefix : RecyclerView = inflate(getContext(), R.layout.view_rv_prefix, null) as RecyclerView
    private val mLlAddSymbol : LinearLayout = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val m = dp(20)
            setMargins(m, m, m, m)
        }
        gravity = Gravity.CENTER
    }

    init {
        val ivAddSymbol = ImageView(context).apply {
            val p = dp(5)
            setPadding(p, p, p, p)
            setImageResource(R.drawable.ic_menu_setting)
            drawable.setTint(ThemeManager.activeTheme.keyTextColor)
        }
        ivAddSymbol.setOnClickListener { _:View ->
            val arguments = Bundle()
            arguments.putInt("type", if(skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) 1 else 0)
            AppUtil.launchSettingsToPrefix(context!!, arguments)
        }
        mLlAddSymbol.addView(ivAddSymbol)
        mSideSymbolsPinyin = emptyList()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                mSideSymbolsPinyin = if (skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {
                    val dbSymbols = AppDatabase.instance.sideSymbolDao().getAllSideSymbolNumber()
                    if (dbSymbols.isNotEmpty()) dbSymbols else org.bitfennec.lime.data.keyboard.OpKey.DEFAULT_TOOL_BAR_KEYS.map {
                        SideSymbol(it.displayLabel, it.token, "number")
                    }
                } else {
                    AppDatabase.instance.sideSymbolDao().getAllSideSymbolPinyin()
                }
                if (mMajorView != null) {
                    updateKeyboardView()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Updates soft keyboard layout.
     */
    override fun updateSkbLayout() {
        val keyboard = attachTextKeyboard(keyboardSurface, skbValue)
        updateKeyboardView()
        keyboard.invalidate()
    }

    // Update sidebar symbols list
    private fun updateKeyboardView() {
        val softKeyboard = mMajorView!!.getSoftKeyboard()
        val softKeySymbolHolder = softKeyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL) ?: return
        val prefixLayoutParams = LayoutParams(softKeySymbolHolder.width(), LayoutParams.MATCH_PARENT)
        prefixLayoutParams.setMargins(
            softKeyboard.keyXMargin,
            softKeySymbolHolder.mTop + softKeyboard.keyYMargin,
            softKeyboard.keyXMargin,
            ImeEnvironment.skbHeight - softKeySymbolHolder.mBottom + softKeyboard.keyYMargin
        )
        val prefixLayoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        mRVLeftPrefix.layoutManager = prefixLayoutManager
        if (mRVLeftPrefix.parent != null) {
            val parent = mRVLeftPrefix.parent as ViewGroup
            parent.removeView(mRVLeftPrefix)
        }
        addView(mRVLeftPrefix, prefixLayoutParams)
        updateSymbolListView()
    }

    // Update sidebar symbols display
    fun updateSymbolListView() {
        var prefixs = DecodingInfo.prefixs
        val isPrefixs = prefixs.isNotEmpty()
        if (!isPrefixs) {
            prefixs = mSideSymbolsPinyin.map { it.symbolKey }.toTypedArray()
        }
        val softKeyboard = mMajorView?.getSoftKeyboard()
        val softKeySymbolHolder = softKeyboard?.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL)
        val availableHeight = if (softKeySymbolHolder != null) {
            softKeySymbolHolder.height() - 2 * softKeyboard.keyYMargin
        } else 0
        // [comment] yagni: hardcoded 4-slot partition per requirement to display 4 symbols per viewport; upgrade when dynamic visible row count is configurable
        val calculatedItemHeight = if (availableHeight > 0) availableHeight / 4 else 0

        val footer = if (!isPrefixs) mLlAddSymbol else null
        if ((mRVLeftPrefix.adapter as? PrefixAdapter)?.matchesContent(prefixs, footer, calculatedItemHeight) == true) return
        val adapter = PrefixAdapter(
            context,
            prefixs,
            footerView = footer,
            itemHeight = calculatedItemHeight,
            onItemClickListener = { position ->
                if (!isPrefixs) {
                    val symbol = mSideSymbolsPinyin.map { it.symbolValue }[position]
                    val softKey = SoftKey(label = symbol)
                    DevicesUtils.tryPlayKeyDown()
                    DevicesUtils.tryVibrate(this)
                    inputView.responseKeyEvent(softKey)
                } else {
                    inputView.selectPrefix(position)
                }
            }
        )
        mRVLeftPrefix.adapter = adapter
    }
}
