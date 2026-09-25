package org.bitfennec.lime.ui.fragment

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat
import org.bitfennec.lime.BuildConfig
import org.bitfennec.lime.R
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.utils.TimeUtils
import org.bitfennec.lime.utils.addCategory
import org.bitfennec.lime.utils.addPreference
import org.bitfennec.lime.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AboutFragment : PreferenceFragmentCompat() {
    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                        export(outputStream).getOrThrow()
                    }
                }
            }
    }

    fun export(dest: OutputStream) = runCatching {
        val outputDir = File(Launcher.instance.context.filesDir, "crash_logs")
        ZipOutputStream(dest.buffered()).use { zipStream ->
            writeFileTree(outputDir, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun writeFileTree(srcDir: File, dest: ZipOutputStream) {
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy) {
                findNavController().navigate(R.id.action_aboutFragment_to_privacyPolicyFragment)
            }
            addPreference(R.string.source_code, R.string.github_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, CustomConstant.LIME_IME_REPO.toUri()))
            }
            addPreference(R.string.license, "BSD-3-Clause license") {
                startActivity(Intent(Intent.ACTION_VIEW, CustomConstant.LICENSE_URL.toUri()))
            }
            addPreference(R.string.rime_engine_attribution, R.string.rime_engine_attribution_summary) {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/rime/librime".toUri()))
            }
            addPreference(R.string.voice_model_attribution, R.string.voice_model_attribution_summary) {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE".toUri()))
            }
            addPreference(R.string.handwriting_model_attribution, R.string.handwriting_model_attribution_summary) {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/PaddlePaddle/PaddleOCR".toUri()))
            }
            addPreference(R.string.rime_wanxiang_attribution, R.string.rime_wanxiang_attribution_summary) {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/amzxyz/rime-wanxiang".toUri()))
            }
            addCategory(R.string.app_version) {
                isIconSpaceReserved = false
                addPreference(R.string.version, BuildConfig.versionName){
                    val uri = "${CustomConstant.LIME_IME_REPO}/releases/latest".toUri()
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_git_hash, BuildConfig.AppCommitHead) {
                    val commit = BuildConfig.AppCommitHead.substringBefore('-')
                    val uri = "${CustomConstant.LIME_IME_REPO}/commit/${commit}".toUri()
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_time, BuildConfig.AppBuildTime)

            }

            addCategory(R.string.app_log) {
                isIconSpaceReserved = false
                addPreference(R.string.export_crash_log) {
                    lifecycleScope.launch {
                        exportTimestamp = System.currentTimeMillis()
                        exportLauncher.launch("lime_crash_log_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
                    }
                }
            }
        }


    }
}
