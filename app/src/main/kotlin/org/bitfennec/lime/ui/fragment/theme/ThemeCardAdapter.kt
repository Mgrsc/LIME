package org.bitfennec.lime.ui.fragment.theme

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.Theme

class ThemeCardAdapter(
    private var themes: List<Theme>,
    private var selectedThemeName: String,
    private val onThemeSelected: (Theme) -> Unit
) : RecyclerView.Adapter<ThemeCardAdapter.ThemeViewHolder>() {

    class ThemeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: FrameLayout = itemView.findViewById(R.id.theme_card_container)
        val ivBg: ImageView = itemView.findViewById(R.id.iv_theme_bg)
        val viewTopBar: View = itemView.findViewById(R.id.view_top_bar)
        val viewSpacebar: View = itemView.findViewById(R.id.view_spacebar)
        val viewAccentKey: View = itemView.findViewById(R.id.view_accent_key)
        val flCheckedBadge: FrameLayout = itemView.findViewById(R.id.fl_checked_badge)
        val tvName: TextView = itemView.findViewById(R.id.tv_theme_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_theme_card, parent, false)
        return ThemeViewHolder(view)
    }

    override fun getItemCount(): Int = themes.size

    override fun onBindViewHolder(holder: ThemeViewHolder, position: Int) {
        val theme = themes[position]
        val context = holder.itemView.context

        try {
            holder.ivBg.setImageDrawable(theme.backgroundDrawable(false))
        } catch (_: Throwable) {
            holder.ivBg.setBackgroundColor(theme.keyboardColor)
        }

        holder.viewTopBar.setBackgroundColor(theme.barColor)

        holder.viewSpacebar.backgroundTintList = ColorStateList.valueOf(theme.functionKeyBackgroundColor)
        holder.viewAccentKey.backgroundTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)

        val isSelected = theme.name == selectedThemeName
        holder.flCheckedBadge.visibility = if (isSelected) View.VISIBLE else View.GONE

        holder.tvName.text = getLocalizedThemeName(context, theme.name)

        holder.container.contentDescription = holder.tvName.text
        holder.container.isSelected = isSelected
        holder.container.isFocusable = true
        holder.container.setOnClickListener {
            if (selectedThemeName != theme.name) {
                setSelectedTheme(theme.name)
            }
            onThemeSelected(theme)
        }
    }

    fun updateThemes(newThemes: List<Theme>, selectedName: String? = null) {
        val oldThemes = themes
        val updatedThemes = newThemes.toList()
        val oldSelection = selectedThemeName
        val newSelection = selectedName ?: oldSelection
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldThemes.size
            override fun getNewListSize() = updatedThemes.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldThemes[oldPos].name == updatedThemes[newPos].name
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = oldThemes[oldPos]
                val new = updatedThemes[newPos]
                // Custom image files can change without their path changing.
                return old !is Theme.Custom && old == new &&
                    (old.name == oldSelection) == (new.name == newSelection)
            }
        })
        themes = updatedThemes
        selectedThemeName = newSelection
        diff.dispatchUpdatesTo(this)
    }

    fun setSelectedTheme(themeName: String) {
        if (selectedThemeName != themeName) {
            val previousIndex = themes.indexOfFirst { it.name == selectedThemeName }
            selectedThemeName = themeName
            val selectedIndex = themes.indexOfFirst { it.name == themeName }
            if (previousIndex >= 0) notifyItemChanged(previousIndex)
            if (selectedIndex >= 0) notifyItemChanged(selectedIndex)
        }
    }

    companion object {
        fun getLocalizedThemeName(context: android.content.Context, name: String): String {
            return when (name) {
                "MonetLight" -> context.getString(R.string.theme_name_monet_light)
                "MonetDark" -> context.getString(R.string.theme_name_monet_dark)
                "MaterialLight" -> context.getString(R.string.theme_name_material_light)
                "MaterialDark" -> context.getString(R.string.theme_name_material_dark)
                "PixelLight" -> context.getString(R.string.theme_name_pixel_light)
                "PixelDark" -> context.getString(R.string.theme_name_pixel_dark)
                "NordLight" -> context.getString(R.string.theme_name_nord_light)
                "NordDark" -> context.getString(R.string.theme_name_nord_dark)
                "AMOLEDBlack" -> context.getString(R.string.theme_name_amoled_black)
                "Monokai" -> context.getString(R.string.theme_name_monokai)
                else -> name
            }
        }
    }
}
