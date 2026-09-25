package org.bitfennec.lime.ui.fragment

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitfennec.lime.R
import org.bitfennec.lime.manager.UserDataManager
import org.bitfennec.lime.manager.UserDictEntry

class UserDictViewerBottomSheet : BottomSheetDialogFragment() {

    private var currentDictName: String = ""
    private var allEntries: MutableList<UserDictEntry> = mutableListOf()
    private lateinit var adapter: UserDictAdapter

    private var filterOnlyCustom: Boolean = false
    private lateinit var tabFilterAll: TextView
    private lateinit var tabFilterCustom: TextView

    private lateinit var tvDictChip: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var rvUserDict: RecyclerView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyDesc: TextView
    private lateinit var layoutError: LinearLayout
    private lateinit var btnRetry: Button

    override fun getTheme(): Int = R.style.ModernBottomSheetDialogTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDictName = UserDataManager.getActiveUserDictName()
    }

    private fun applyTransparentSheet(dialog: android.app.Dialog) {
        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { sheet ->
            sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            sheet.background = null
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as com.google.android.material.bottomsheet.BottomSheetDialog
        applyTransparentSheet(dialog)
        dialog.setOnShowListener {
            applyTransparentSheet(dialog)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_user_dict, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            applyTransparentSheet(d)
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                val displayMetrics = resources.displayMetrics
                sheet.layoutParams.height = (displayMetrics.heightPixels * 0.85).toInt()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvDictChip = view.findViewById(R.id.tv_dict_chip)
        tvTotalCount = view.findViewById(R.id.tv_total_count)
        etSearch = view.findViewById(R.id.et_search)
        btnClearSearch = view.findViewById(R.id.btn_clear_search)
        tabFilterAll = view.findViewById(R.id.tab_filter_all)
        tabFilterCustom = view.findViewById(R.id.tab_filter_custom)
        rvUserDict = view.findViewById(R.id.rv_user_dict)
        layoutLoading = view.findViewById(R.id.layout_loading)
        layoutEmpty = view.findViewById(R.id.layout_empty)
        tvEmptyTitle = view.findViewById(R.id.tv_empty_title)
        tvEmptyDesc = view.findViewById(R.id.tv_empty_desc)
        layoutError = view.findViewById(R.id.layout_error)
        btnRetry = view.findViewById(R.id.btn_retry)

        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            dismiss()
        }

        tvDictChip.text = currentDictName
        tvDictChip.setOnClickListener {
            showDictPickerDialog()
        }

        tabFilterAll.setOnClickListener {
            setFilterCustomOnly(false)
        }
        tabFilterCustom.setOnClickListener {
            setFilterCustomOnly(true)
        }

        adapter = UserDictAdapter { entry ->
            confirmDeleteEntry(entry)
        }
        rvUserDict.layoutManager = LinearLayoutManager(requireContext())
        rvUserDict.adapter = adapter

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                btnClearSearch.isVisible = query.isNotEmpty()
                applyFilter(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnRetry.setOnClickListener {
            loadData()
        }

        updateFilterTabsUi()
        updateTotalCountUi()
        loadData()
    }

    private fun showDictPickerDialog() {
        val dictList = UserDataManager.getUserDictList()
        if (dictList.size <= 1) return
        val items = dictList.toTypedArray()
        val checkedIndex = dictList.indexOf(currentDictName).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.user_dict_select_dict)
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val selected = items[which]
                if (selected != currentDictName) {
                    currentDictName = selected
                    tvDictChip.text = selected
                    loadData()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setFilterCustomOnly(onlyCustom: Boolean) {
        if (filterOnlyCustom == onlyCustom) return
        filterOnlyCustom = onlyCustom
        updateFilterTabsUi()
        updateTotalCountUi()
        applyFilter(etSearch.text?.toString()?.trim().orEmpty())
    }

    private fun updateFilterTabsUi() {
        val totalCount = allEntries.size
        val customCount = allEntries.count { it.isCustom }
        tabFilterAll.text = getString(R.string.user_dict_filter_all, totalCount)
        tabFilterCustom.text = getString(R.string.user_dict_filter_custom, customCount)

        val ctx = context ?: return
        val secondaryTextColor = ContextCompat.getColor(ctx, R.color.settings_secondary_text)

        if (!filterOnlyCustom) {
            tabFilterAll.setBackgroundResource(R.drawable.bg_user_dict_filter_active)
            tabFilterAll.setTextColor(Color.WHITE)
            tabFilterAll.setTypeface(null, Typeface.BOLD)

            tabFilterCustom.setBackgroundResource(R.drawable.bg_user_dict_filter_inactive)
            tabFilterCustom.setTextColor(secondaryTextColor)
            tabFilterCustom.setTypeface(null, Typeface.NORMAL)
        } else {
            tabFilterCustom.setBackgroundResource(R.drawable.bg_user_dict_filter_active)
            tabFilterCustom.setTextColor(Color.WHITE)
            tabFilterCustom.setTypeface(null, Typeface.BOLD)

            tabFilterAll.setBackgroundResource(R.drawable.bg_user_dict_filter_inactive)
            tabFilterAll.setTextColor(secondaryTextColor)
            tabFilterAll.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun updateTotalCountUi() {
        if (!isAdded) return
        if (filterOnlyCustom) {
            val customCount = allEntries.count { it.isCustom }
            tvTotalCount.text = resources.getQuantityString(R.plurals.user_dict_count_custom_format, customCount, customCount)
        } else {
            tvTotalCount.text = resources.getQuantityString(R.plurals.user_dict_count_format, allEntries.size, allEntries.size)
        }
    }

    private fun loadData() {
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val result = UserDataManager.loadUserDictEntries(ctx, currentDictName)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.fold(
                    onSuccess = { entries ->
                        allEntries = entries.toMutableList()
                        updateFilterTabsUi()
                        updateTotalCountUi()
                        applyFilter(etSearch.text?.toString()?.trim().orEmpty())
                    },
                    onFailure = {
                        showError()
                    }
                )
            }
        }
    }

    private fun applyFilter(query: String) {
        val baseList = if (filterOnlyCustom) {
            allEntries.filter { it.isCustom }
        } else {
            allEntries.toList()
        }
        val filtered = if (query.isEmpty()) {
            baseList
        } else {
            baseList.filter {
                it.phrase.contains(query, ignoreCase = true) ||
                it.code.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(filtered) {
            if (!isAdded) return@submitList
            if (filtered.isEmpty()) {
                val isSearch = query.isNotEmpty()
                showEmpty(isSearch, isCustomFilter = filterOnlyCustom)
            } else {
                showContent()
            }
        }
    }

    private fun confirmDeleteEntry(entry: UserDictEntry) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.user_dict_delete_confirm_title)
            .setMessage(getString(R.string.user_dict_delete_confirm_msg, entry.phrase, entry.code))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                performDeleteEntry(entry)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performDeleteEntry(entry: UserDictEntry) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = UserDataManager.deleteUserDictEntry(ctx, entry, currentDictName)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.fold(
                    onSuccess = {
                        allEntries.remove(entry)
                        updateFilterTabsUi()
                        updateTotalCountUi()
                        applyFilter(etSearch.text?.toString()?.trim().orEmpty())
                        Toast.makeText(
                            ctx,
                            getString(R.string.user_dict_delete_success, entry.phrase),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = {
                        Toast.makeText(ctx, R.string.user_dict_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun showLoading() {
        layoutLoading.isVisible = true
        layoutError.isVisible = false
        layoutEmpty.isVisible = false
        rvUserDict.isVisible = false
    }

    private fun showError() {
        layoutLoading.isVisible = false
        layoutError.isVisible = true
        layoutEmpty.isVisible = false
        rvUserDict.isVisible = false
    }

    private fun showEmpty(isSearch: Boolean, isCustomFilter: Boolean = false) {
        layoutLoading.isVisible = false
        layoutError.isVisible = false
        layoutEmpty.isVisible = true
        rvUserDict.isVisible = false

        if (isSearch) {
            tvEmptyTitle.setText(R.string.user_dict_search_empty)
            tvEmptyDesc.text = ""
        } else if (isCustomFilter) {
            tvEmptyTitle.setText(R.string.user_dict_empty_custom_title)
            tvEmptyDesc.setText(R.string.user_dict_empty_custom_desc)
        } else {
            tvEmptyTitle.setText(R.string.user_dict_empty_title)
            tvEmptyDesc.setText(R.string.user_dict_empty_desc)
        }
    }

    private fun showContent() {
        layoutLoading.isVisible = false
        layoutError.isVisible = false
        layoutEmpty.isVisible = false
        rvUserDict.isVisible = true
    }

    companion object {
        const val TAG = "UserDictViewerBottomSheet"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            UserDictViewerBottomSheet().show(fragmentManager, TAG)
        }
    }
}

private class UserDictAdapter(
    private val onDeleteClick: (UserDictEntry) -> Unit
) : ListAdapter<UserDictEntry, UserDictAdapter.ViewHolder>(UserDictDiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPhrase: TextView = view.findViewById(R.id.tv_phrase)
        val tvCode: TextView = view.findViewById(R.id.tv_code)
        val tvCommits: TextView = view.findViewById(R.id.tv_commits)
        val tvCustomTag: TextView = view.findViewById(R.id.tv_custom_tag)
        val btnDelete: ImageView = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_dict_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvPhrase.text = item.phrase
        holder.tvCode.text = item.code
        holder.tvCustomTag.isVisible = item.isCustom
        holder.tvCommits.text = holder.itemView.context.getString(R.string.user_dict_commits_format, item.commits)
        holder.btnDelete.setOnClickListener {
            onDeleteClick(item)
        }
    }
}

private object UserDictDiffCallback : DiffUtil.ItemCallback<UserDictEntry>() {
    override fun areItemsTheSame(oldItem: UserDictEntry, newItem: UserDictEntry): Boolean {
        return oldItem.phrase == newItem.phrase && oldItem.code == newItem.code
    }

    override fun areContentsTheSame(oldItem: UserDictEntry, newItem: UserDictEntry): Boolean {
        return oldItem == newItem
    }
}
