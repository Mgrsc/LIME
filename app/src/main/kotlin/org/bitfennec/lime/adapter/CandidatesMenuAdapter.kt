package org.bitfennec.lime.adapter

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.updateLayoutParams
import org.bitfennec.lime.R
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.service.ClipboardHelper
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.entity.SkbFunItem
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.prefs.behavior.SymbolMode
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.container.ClipBoardContainer
import org.bitfennec.lime.keyboard.container.SettingsContainer
import org.bitfennec.lime.keyboard.container.SymbolContainer
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.utils.DevicesUtils.dip2px
import java.util.Collections

/**
 * Candidate menu adapter.
 */
class CandidatesMenuAdapter(val context: Context) : RecyclerView.Adapter<CandidatesMenuAdapter.ViewHolder>() {
    private var itemHeight = menuItemHeight()
    private var mMenuPadding = menuItemPadding()
    private val mItems: MutableList<SkbFunItem> = mutableListOf()

    private var selectedModes = emptySet<SkbMenuMode>()

    var items: List<SkbFunItem>
        get() = mItems.toList()
        set(value) {
            val oldItems = mItems.toList()
            val newItems = value.distinctBy { it.skbMenuMode }.take(5)
            val oldSelectedModes = selectedModes
            val newSelectedModes = newItems.filter { isSettingsMenuSelect(it) }.map { it.skbMenuMode }.toSet()
            if (oldItems.size == newItems.size && oldItems.indices.all { index ->
                    val old = oldItems[index]
                    val new = newItems[index]
                    old.skbMenuMode == new.skbMenuMode && old.funName == new.funName &&
                        old.funImgResource == new.funImgResource
                } && oldSelectedModes == newSelectedModes
            ) return
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    oldItems[oldPos].skbMenuMode == newItems[newPos].skbMenuMode
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val old = oldItems[oldPos]
                    val new = newItems[newPos]
                    return old.funName == new.funName && old.funImgResource == new.funImgResource &&
                        (old.skbMenuMode in oldSelectedModes) == (new.skbMenuMode in newSelectedModes)
                }
            })
            selectedModes = newSelectedModes
            mItems.clear()
            mItems.addAll(newItems)
            diff.dispatchUpdatesTo(this)
        }

    fun swapItems(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= mItems.size || toPosition >= mItems.size) return
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(mItems, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(mItems, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    private var mOnItemClickListener: OnRecyclerItemClickListener? = null

    fun setOnItemClickListener(onItemClickListener: OnRecyclerItemClickListener?) {
        this.mOnItemClickListener = onItemClickListener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_recyclerview_candidates_menu, parent, false))
    }

    override fun getItemCount(): Int {
        return mItems.size
    }

    var isEditMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val data = mItems[position]
        holder.entranceIconImageView?.updateLayoutParams {
            height = itemHeight
            width = itemHeight
        }
        holder.entranceIconImageView?.setPadding(mMenuPadding, mMenuPadding, mMenuPadding, mMenuPadding)
        holder.entranceIconImageView?.setImageResource(data.funImgResource)
        holder.itemView.contentDescription = if (isEditMode) {
            context.getString(R.string.menu_remove_shortcut, data.funName)
        } else {
            data.funName
        }
        val isSelect = isSettingsMenuSelect(data)
        holder.itemView.stateDescription = if (isSelect && !isEditMode) {
            context.getString(R.string.accessibility_candidate_selected)
        } else {
            null
        }
        if (!isSelect) {
            holder.entranceIconImageView?.drawable?.setTint(activeTheme.keyTextColor)
            holder.entranceIconImageView?.background = null
        } else {
            holder.entranceIconImageView?.drawable?.setTint(activeTheme.accentKeyBackgroundColor)
            val bg = GradientDrawable()
            bg.setColor(activeTheme.keyBackgroundColor)
            bg.shape = GradientDrawable.OVAL
            holder.entranceIconImageView?.background = bg
        }

        if (isEditMode) {
            holder.entranceBadgeImageView?.visibility = View.VISIBLE
            holder.entranceBadgeImageView?.setImageResource(R.drawable.ic_menu_minus)
            holder.entranceBadgeImageView?.drawable?.setTint(0xFFFF5252.toInt())
            val badgeBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activeTheme.keyBackgroundColor)
            }
            holder.entranceBadgeImageView?.background = badgeBg
        } else {
            holder.entranceBadgeImageView?.visibility = View.GONE
        }

        if (mOnItemClickListener != null) {
            holder.itemView.setOnClickListener { v: View? ->
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    mOnItemClickListener!!.onItemClick(this, v, currentPos)
                }
            }
        }
    }

    fun getMenuMode(position: Int): SkbMenuMode? = if (mItems.size > position && position >= 0) mItems[position].skbMenuMode else null

    private fun isSettingsMenuSelect(data: SkbFunItem): Boolean {
        val rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        val result: Boolean = when (data.skbMenuMode) {
            // Setting Menu
            SkbMenuMode.DarkTheme -> activeTheme.isDark
            SkbMenuMode.NumberRow -> AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
            SkbMenuMode.JianFan -> AppPrefs.getInstance().input.chineseFanTi.getValue()
            SkbMenuMode.LockEnglish -> AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            SkbMenuMode.SymbolShow -> ThemeManager.prefs.keyboardSymbol.getValue()
            SkbMenuMode.EmojiInput -> AppPrefs.getInstance().input.emojiInput.getValue()
            SkbMenuMode.OneHanded -> AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
            SkbMenuMode.FloatKeyboard -> ImeEnvironment.keyboardModeFloat
            SkbMenuMode.ClipBoard -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.getMenuMode() == SkbMenuMode.ClipBoard
            SkbMenuMode.Emojicon -> {
                val container = KeyboardManager.instance.currentContainer as? SymbolContainer
                container != null && (container.getMenuMode() == SymbolMode.Emojicon || container.getMenuMode() == SymbolMode.Emoticon)
            }
            SkbMenuMode.Emoticon -> {
                val container = KeyboardManager.instance.currentContainer as? SymbolContainer
                container != null && container.getMenuMode() == SymbolMode.Emoticon
            }
            SkbMenuMode.SwitchKeyboard -> {
                val container = KeyboardManager.instance.currentContainer as? SettingsContainer
                container != null && container.isShowingModeSelect()
            }
            // Keyboard Menu
            SkbMenuMode.PinyinT9 -> !InputModeSwitcher.isChineseHandWriting && rimeValue == CustomConstant.SCHEMA_ZH_T9
            SkbMenuMode.Pinyin26Jian -> !InputModeSwitcher.isChineseHandWriting && rimeValue == CustomConstant.SCHEMA_ZH_QWERTY
            SkbMenuMode.PinyinHandWriting -> InputModeSwitcher.isChineseHandWriting
            SkbMenuMode.Pinyin26Double -> !InputModeSwitcher.isChineseHandWriting && rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY
            SkbMenuMode.LockClipBoard -> ClipboardHelper.isLocked
            SkbMenuMode.TextEdit -> InputModeSwitcher.isTextEditSkb
            else -> false
        }
        return result
    }

    fun notifyChanged() {
        selectedModes = mItems.filter { isSettingsMenuSelect(it) }.map { it.skbMenuMode }.toSet()
        itemHeight = menuItemHeight()
        mMenuPadding = menuItemPadding()
        notifyItemRangeChanged(0, itemCount)
    }

    private fun menuItemHeight(): Int = dip2px(40f)

    private fun menuItemPadding(): Int = dip2px(3f)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var entranceIconImageView: ImageView? = view.findViewById(R.id.candidates_menu_item)
        var entranceBadgeImageView: ImageView? = view.findViewById(R.id.candidates_menu_item_badge)
    }
}
