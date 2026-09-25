package org.bitfennec.lime.ui.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.bitfennec.lime.R
import org.bitfennec.lime.adapter.PrefixSettingsAdapter
import org.bitfennec.lime.data.theme.ThemeManager.activeTheme
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.database.entity.SideSymbol
import org.bitfennec.lime.utils.DevicesUtils
import org.bitfennec.lime.utils.dp
import org.bitfennec.lime.keyboard.KeyboardManager
import java.util.Collections

class PrefixSettingsFragment(type: String) : Fragment() {
    private var mType = "pinyin"
    private var datas: MutableList<SideSymbol>
    private var mAdapter: PrefixSettingsAdapter

    init {
        mType = type
        datas = mutableListOf()
        mAdapter = PrefixSettingsAdapter(datas, mType)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = if (mType == "pinyin") {
                    AppDatabase.instance.sideSymbolDao().getAllSideSymbolPinyin()
                } else {
                    AppDatabase.instance.sideSymbolDao().getAllSideSymbolNumber()
                }
                val oldCount = datas.size
                datas.clear()
                if (oldCount > 0) mAdapter.notifyItemRangeRemoved(0, oldCount)
                datas.addAll(list)
                if (datas.isNotEmpty()) mAdapter.notifyItemRangeInserted(0, datas.size)
            } catch (_: Exception) {}
        }
        val menuProvider = object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.add_prefix_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.add_prefix_menu -> {
                        datas.add(SideSymbol("", "", type = mType))
                        mAdapter.notifyItemInserted(datas.lastIndex)
                        true
                    }
                    else -> false
                }
            }
        }
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        (activity as AppCompatActivity).supportActionBar?.setTitle(R.string.input_sidebar_symbols)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(20), 0, 0)
            }
            addView(TextView(ctx).apply {
                gravity = Gravity.CENTER
                text = getString(R.string.skb_prefix_show_tips)
                setTextColor(activeTheme.keyTextColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(20), 0, dp(20), 0)
            })

            addView(TextView(ctx).apply {
                gravity = Gravity.CENTER
                text = getString(R.string.skb_prefix_commit_tips)
                setTextColor(activeTheme.keyTextColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))

            addView(TextView(ctx).apply {
                gravity = Gravity.CENTER
                text = getString(R.string.skb_prefix_sort_tips)
                setTextColor(activeTheme.keyTextColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val mRVSymbolsView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = mAdapter
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.let { DevicesUtils.tryVibrate(it) }
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                srcHolder: RecyclerView.ViewHolder,
                targetHolder: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = srcHolder.bindingAdapterPosition
                val toPosition = targetHolder.bindingAdapterPosition
                if (fromPosition < 0 || fromPosition >= datas.size) return false
                if (toPosition < 0 || toPosition >= datas.size) return false
                Collections.swap(datas, fromPosition, toPosition)
                mAdapter.notifyItemMoved(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position in datas.indices) {
                    datas.removeAt(position)
                    mAdapter.notifyItemRemoved(position)
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(mRVSymbolsView)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(header)
            addView(mRVSymbolsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    override fun onPause() {
        super.onPause()
        val toSave = datas.filter { it.symbolKey.isNotBlank() }
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(NonCancellable) {
                try {
                    AppDatabase.instance.sideSymbolDao().deleteAll(mType)
                    AppDatabase.instance.sideSymbolDao().insertAll(toSave)
                } catch (_: Exception) {}
            }
        }
        KeyboardManager.instance.clearKeyboard()
    }
}