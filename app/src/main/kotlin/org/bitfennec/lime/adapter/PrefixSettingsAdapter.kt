package org.bitfennec.lime.adapter

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.database.entity.SideSymbol
import org.bitfennec.lime.utils.dp

class PrefixSettingsAdapter(private val mDatas: MutableList<SideSymbol>, type: String) : RecyclerView.Adapter<PrefixSettingsAdapter.PrefixSettingsHolder>() {
    private var mType = "pinyin"

    init {
        mType = type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrefixSettingsHolder {
        val ctx = Launcher.instance.context
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padV = dp(5)
            setPadding(0, padV, 0, padV)

            val etKey = EditText(ctx).apply {
                id = R.id.et_prefix_setting_key
                gravity = Gravity.CENTER
                setTextColor(activeTheme.keyTextColor)
            }
            val lpKey = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(20), 0, dp(20), 0)
            }
            addView(etKey, lpKey)

            val etValue = EditText(ctx).apply {
                id = R.id.et_prefix_setting_value
                gravity = Gravity.CENTER
                setTextColor(activeTheme.keyTextColor)
            }
            val lpValue = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
            addView(etValue, lpValue)

            val ivMenu = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_menu_menu)
                drawable?.setTint(activeTheme.keyTextColor)
            }
            val lpMenu = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER
            }
            addView(ivMenu, lpMenu)
        }
        return PrefixSettingsHolder(content)
    }

    override fun onBindViewHolder(holder: PrefixSettingsHolder, position: Int) {
        if (position < mDatas.size) {
            holder.etPrefixKey.setText(mDatas[position].symbolKey)
            holder.etPrefixValue.setText(mDatas[position].symbolValue)
        }
        holder.etPrefixKey.doOnTextChanged { s, _, _, _ ->
            val key = s.toString()
            val bindPos = holder.bindingAdapterPosition
            if (bindPos in mDatas.indices) {
                val data = mDatas[bindPos]
                data.symbolKey = key
            }
        }
        holder.etPrefixValue.doOnTextChanged { s, _, _, _ ->
            val value = s.toString()
            val bindPos = holder.bindingAdapterPosition
            if (bindPos in mDatas.indices) {
                val data = mDatas[bindPos]
                data.symbolValue = value
            }
        }
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class PrefixSettingsHolder(view: View) : RecyclerView.ViewHolder(view) {
        var etPrefixKey: EditText
        var etPrefixValue: EditText

        init {
            view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            etPrefixKey = view.findViewById(R.id.et_prefix_setting_key)
            etPrefixValue = view.findViewById(R.id.et_prefix_setting_value)
        }
    }
}