package org.bitfennec.lime.application

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import org.bitfennec.lime.R
import org.bitfennec.lime.core.Rime
import org.bitfennec.lime.data.emojicon.EmojiconData
import org.bitfennec.lime.data.emojicon.LimeEmojiCompat
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.data.theme.ThemeManager.prefs
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.inputmethod.predict.SystemPredictTable
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.InputFeedbacks
import org.bitfennec.lime.service.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitfennec.lime.inputmethod.ImeDispatchers
import org.bitfennec.lime.utils.versionCodeCompat
import java.io.File

class Launcher {
    lateinit var context: Context
        private set

    fun initData(context: Context) {
        this.context = context
        currentInit()
        onInitDataChildThread()
    }

    private fun currentInit() {
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        ClipboardHelper.init()
    }

    /**
     * Asynchronously initializes Rime engine and dictionary bundles on background thread.
     */
    private fun onInitDataChildThread() {
        CoroutineScope(ImeDispatchers.idleDispatcher).launch {
            try {
                AppDatabase.instance.sideSymbolDao().getAllSideSymbolPinyin()
            } catch (_: Exception) {}
            SystemPredictTable.initialize(context, enabled = true)
            // Populate Android's font cache before the first missing-glyph candidate is bound.
            context.resources.getFont(R.font.lime_candidate_fallback)
            context.resources.getFont(R.font.lime_candidate_extra)
            val assetsReady = ensureRimeAssets()
            if (assetsReady) {
                Rime.getInstance(false)
                // Prewarm converters after the bundled resources have been verified.
                Rime.ensureOpencc("s2t.json")
                if (AppPrefs.getInstance().input.emojiInput.getValue()) {
                    Rime.ensureOpencc("emoji.json")
                }
                LimeEmojiCompat.init(context)
                EmojiconData.prewarm()
                InputFeedbacks.warmUp()
                prewarmPageCache()
            } else {
                if (System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true) {
                    android.util.Log.e("Launcher", "Rime assets not ready; skipping prewarm")
                }
            }
            // Initialize keyboard theme
            val isFollowSystemDayNight = prefs.followSystemDayNightTheme.getValue()
            if (isFollowSystemDayNight) {
                withContext(Dispatchers.Main) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
        }
    }

    private fun prewarmPageCache() {
        try {
            val rimeDir = File(CustomConstant.RIME_DICT_PATH)
            val filesToWarm = buildList {
                add("build/pinyin.prism.bin")
                add("build/pinyin.table.bin")
                addAll(listOf(
                    "opencc/STPhrases.ocd2",
                    "opencc/STCharacters.ocd2",
                    "opencc/emoji.ocd2",
                    "opencc/others.ocd2",
                ))
            }
            val buffer = ByteArray(8192)
            for (relPath in filesToWarm) {
                val file = File(rimeDir, relPath)
                if (file.isFile) {
                    file.inputStream().use { input ->
                        while (input.read(buffer) != -1) {
                            // Sequential read to populate Linux Page Cache
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Checks dictionary integrity and app upgrade status.
     */
    @Synchronized
    fun ensureRimeAssets(): Boolean {
        return try {
            val packageInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            } catch (_: Exception) { null }

            val currentUpdateTime = packageInfo?.lastUpdateTime ?: 0L
            val currentVersionCode = packageInfo?.versionCodeCompat ?: 0L

            val bootstrapState = RimeAssetBootstrap.ensureActiveAssets(context)
            if (bootstrapState == RimeAssetBootstrap.State.FullReady) {
                AppPrefs.getInstance().internal.lastAppUpdateTime.setValue(currentUpdateTime)
                AppPrefs.getInstance().internal.lastAppVersionCode.setValue(currentVersionCode)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            val errorMsg = e.message?.let { org.bitfennec.lime.utils.StringUtils.escapeJson(it) } ?: ""
            if (System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true) {
                android.util.Log.e("Launcher", "{\"event\":\"ensure_rime_assets\",\"result\":\"failed\",\"error\":\"$errorMsg\"}")
            } else {
                System.err.println("{\"event\":\"ensure_rime_assets\",\"result\":\"failed\",\"error\":\"$errorMsg\"}")
            }
            false
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        val instance = Launcher()
    }
}
