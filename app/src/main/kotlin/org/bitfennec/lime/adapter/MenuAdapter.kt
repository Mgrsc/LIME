package org.bitfennec.lime.adapter

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.entity.SkbFunItem
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.behavior.SkbMenuMode
import org.bitfennec.lime.environment.ImeEnvironment

import android.widget.LinearLayout
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.keyboard.container.SettingsContainer
import org.bitfennec.lime.utils.DevicesUtils

class MenuAdapter (context: Context?, val data: MutableList<SkbFunItem>) : RecyclerView.Adapter<MenuAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var mTheme: Theme
    private var background: GradientDrawable
    fun setOnItemClickListener(onItemClickListener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = onItemClickListener
    }

    var dragOverListener: DragOverListener? = null
    var barMenuNames: Set<String> = emptySet()
    init {
        mTheme = ThemeManager.activeTheme
        inflater = LayoutInflater.from(context)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ThemeManager.activeTheme.keyBackgroundColor)
        }
    }

    fun updateItems(items: List<SkbFunItem>, names: Set<String>) {
        val oldItems = data.toList()
        val newItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].skbMenuMode == newItems[newPos].skbMenuMode
            // Selection and shortcut membership are external state.
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = false
        })
        data.clear()
        data.addAll(newItems)
        barMenuNames = names
        diff.dispatchUpdatesTo(this)
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var cardLayout: LinearLayout? = itemView.findViewById(R.id.ll_menu_card)
        var entranceNameTextView: TextView? = itemView.findViewById(R.id.entrance_name)
        var entranceIconImageView: ImageView? = itemView.findViewById(R.id.entrance_image)
        var entranceOption: ImageView? = itemView.findViewById(R.id.entrance_option)
        init {
            entranceOption?.background = background
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.item_skb_menu_entrance, parent, false)
        return SymbolHolder(view)
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val item = data[position]
        val isSelected = isSettingsMenuSelect(item)
        holder.entranceNameTextView?.text = item.funName
        holder.entranceIconImageView?.setImageResource(item.funImgResource)

        val keyRadius = ThemeManager.prefs.keyRadius.getValue().toFloat().coerceAtLeast(DevicesUtils.dip2px(8).toFloat())
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = keyRadius
            if (isSelected) {
                val alphaAccent = (mTheme.accentKeyBackgroundColor and 0x00FFFFFF) or 0x24000000
                setColor(alphaAccent)
                setStroke(DevicesUtils.dip2px(1.5f), mTheme.accentKeyBackgroundColor)
            } else {
                setColor(mTheme.keyBackgroundColor)
            }
        }
        holder.cardLayout?.background = cardBg

        val color = if (isSelected) mTheme.accentKeyBackgroundColor else mTheme.keyTextColor
        holder.entranceNameTextView?.setTextColor(color)
        holder.entranceIconImageView?.drawable?.setTint(color)
        if (dragOverListener != null) {
            holder.entranceOption?.visibility = View.VISIBLE
            val isAdded = barMenuNames.contains(item.skbMenuMode.name)
            holder.entranceOption?.contentDescription = holder.itemView.context.getString(
                if (isAdded) R.string.menu_remove_shortcut else R.string.menu_add_shortcut,
                item.funName
            )
            if (isAdded) {
                holder.entranceOption?.setImageResource(R.drawable.ic_menu_minus)
            } else {
                holder.entranceOption?.setImageResource(R.drawable.ic_menu_plus)
            }
            val plusTint = if (isAdded) mTheme.keyTextColor else mTheme.accentKeyBackgroundColor
            holder.entranceOption?.drawable?.setTint(plusTint)
            holder.itemView.setOnClickListener {
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(holder.itemView)
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    dragOverListener?.onOptionClick(this, item, currentPos)
                }
            }
            holder.entranceOption?.setOnClickListener {
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(holder.itemView)
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    dragOverListener?.onOptionClick(this, item, currentPos)
                }
            }
        } else {
            holder.entranceOption?.visibility = View.GONE
            holder.itemView.setOnClickListener { v: View? ->
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(holder.itemView)
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    mOnItemClickListener?.onItemClick(this, v, currentPos)
                }
            }
        }
    }

    private fun isSettingsMenuSelect(data: SkbFunItem): Boolean {
        val rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        val result: Boolean = when (data.skbMenuMode) {
            // Setting Menu
            SkbMenuMode.DarkTheme -> ThemeManager.activeTheme.isDark
            SkbMenuMode.NumberRow -> AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
            SkbMenuMode.JianFan -> AppPrefs.getInstance().input.chineseFanTi.getValue()
            SkbMenuMode.LockEnglish -> AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            SkbMenuMode.SymbolShow -> ThemeManager.prefs.keyboardSymbol.getValue()
            SkbMenuMode.EmojiInput -> AppPrefs.getInstance().input.emojiInput.getValue()
            SkbMenuMode.OneHanded -> AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
            SkbMenuMode.FloatKeyboard -> ImeEnvironment.keyboardModeFloat
            SkbMenuMode.SwitchKeyboard -> {
                val container = KeyboardManager.instance.currentContainer as? SettingsContainer
                container != null && container.isShowingModeSelect()
            }
            SkbMenuMode.Feedback -> {
                val container = KeyboardManager.instance.currentContainer as? SettingsContainer
                container != null && container.isShowingFeedback()
            }
            // Keyboard Menu
            SkbMenuMode.PinyinT9 -> !InputModeSwitcher.isChineseHandWriting && rimeValue == CustomConstant.SCHEMA_ZH_T9
            SkbMenuMode.Pinyin26Jian -> !InputModeSwitcher.isChineseHandWriting && rimeValue == CustomConstant.SCHEMA_ZH_QWERTY
            SkbMenuMode.PinyinHandWriting -> InputModeSwitcher.isChineseHandWriting
            SkbMenuMode.Pinyin26Double -> !InputModeSwitcher.isChineseHandWriting && rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY
            SkbMenuMode.TextEdit -> InputModeSwitcher.isTextEditSkb
            else -> false
        }
        return result
    }

    interface DragOverListener {
        fun onOptionClick(parent: RecyclerView.Adapter<*>?, v: SkbFunItem, position: Int)
    }
}