package org.bitfennec.lime.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.bitfennec.lime.R
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.manager.UserDataManager
import org.bitfennec.lime.utils.AppUtil
import org.bitfennec.lime.utils.TimeUtils
import org.bitfennec.lime.utils.importErrorDialog
import org.bitfennec.lime.utils.showErrorDialog
import org.bitfennec.lime.utils.queryFileName
import org.bitfennec.lime.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern card-style data and dictionary management page.
 * Provides backup export/import, Rime index redeployment, phrase reset, and factory reset.
 */
class OtherSettingsFragment : Fragment() {

    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var tvStorageStatsValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val name = cr.queryFileName(uri) ?: return@withContext
                        if (!name.endsWith(".zip")) {
                            ctx.importErrorDialog(R.string.exception_user_data_filename, name)
                            return@withContext
                        }
                        try {
                            val inputStream = cr.openInputStream(uri)!!
                            UserDataManager.import(inputStream).getOrThrow()
                            lifecycleScope.launch(NonCancellable + Dispatchers.Main) {
                                delay(400L)
                                AppUtil.exit()
                            }
                            withContext(Dispatchers.Main) {
                                AppUtil.showRestartNotification(ctx)
                                Toast.makeText(ctx, R.string.user_data_imported, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }

        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            UserDataManager.export(outputStream).getOrThrow()
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_data_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        tvStorageStatsValue = view.findViewById(R.id.tv_storage_stats_value)

        view.findViewById<View>(R.id.row_export_data)?.setOnClickListener {
            exportTimestamp = System.currentTimeMillis()
            exportLauncher.launch("lime_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
        }

        view.findViewById<View>(R.id.row_import_data)?.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.import_user_data_title)
                .setMessage(R.string.confirm_import_user_data)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    importLauncher.launch("application/zip")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.row_view_user_dict)?.setOnClickListener {
            UserDictViewerBottomSheet.show(parentFragmentManager)
        }

        view.findViewById<View>(R.id.row_redeploy_rime)?.setOnClickListener {
            lifecycleScope.withLoadingDialog(ctx) {
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    UserDataManager.redeployRime(ctx)
                }
                if (result.isSuccess) {
                    Toast.makeText(ctx, R.string.redeploy_rime_success, Toast.LENGTH_SHORT).show()
                    refreshStats()
                } else {
                    result.exceptionOrNull()?.let { ctx.showErrorDialog(it, R.string.redeploy_rime_title) }
                }
            }
        }

        view.findViewById<View>(R.id.row_clear_userdb)?.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.clear_userdb_confirm_title)
                .setMessage(R.string.clear_userdb_confirm_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.withLoadingDialog(ctx) {
                        val result = withContext(NonCancellable + Dispatchers.IO) {
                            UserDataManager.clearUserDictionary(ctx)
                        }
                        if (result.isSuccess) {
                            Toast.makeText(ctx, R.string.clear_userdb_success, Toast.LENGTH_SHORT).show()
                            refreshStats()
                        } else {
                            result.exceptionOrNull()?.let { ctx.showErrorDialog(it, R.string.clear_userdb_title) }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.row_reset_preferences)?.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.reset_preferences_confirm_title)
                .setMessage(R.string.reset_preferences_confirm_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.withLoadingDialog(ctx) {
                        val result = withContext(NonCancellable + Dispatchers.IO) {
                            UserDataManager.resetPreferences(ctx)
                        }
                        if (result.isSuccess) {
                            Toast.makeText(ctx, R.string.reset_preferences_success, Toast.LENGTH_SHORT).show()
                            refreshStats()
                        } else {
                            result.exceptionOrNull()?.let { ctx.showErrorDialog(it, R.string.reset_preferences_title) }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.row_clear_clipboard_data)?.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.clipboard_clear_confirm_title)
                .setMessage(R.string.clipboard_clear_confirm_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.instance.clipboardDao().deleteAllExceptKeep()
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                Toast.makeText(ctx, R.string.clipboard_clear_success, Toast.LENGTH_SHORT).show()
                                refreshStats()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.row_clear_used_symbols)?.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.clear_used_symbols_confirm_title)
                .setMessage(R.string.clear_used_symbols_confirm_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.instance.usedSymbolDao().deleteAll()
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                Toast.makeText(ctx, R.string.clear_used_symbols_success, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.row_factory_reset)?.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.factory_reset_confirm_title)
                .setMessage(R.string.factory_reset_confirm_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.withLoadingDialog(ctx) {
                        val result = withContext(NonCancellable + Dispatchers.IO) {
                            UserDataManager.factoryReset(ctx)
                        }
                        if (result.isSuccess) {
                            Toast.makeText(ctx, R.string.factory_reset_success, Toast.LENGTH_SHORT).show()
                            delay(600L)
                            AppUtil.exit()
                        } else {
                            result.exceptionOrNull()?.let { ctx.showErrorDialog(it, R.string.factory_reset_title) }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val clipCount = AppDatabase.instance.clipboardDao().getCount()
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    tvStorageStatsValue.text = resources.getQuantityString(R.plurals.other_settings_status_summary, clipCount, clipCount)
                }
            }
        }
    }
}
