package org.bitfennec.lime.core

import android.content.Context
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.application.Launcher

class Rime(fullCheck: Boolean) {

    init {
        startup(Launcher.instance.context, fullCheck)
    }

    companion object {
        const val DELETABLE_NONE = 0
        const val DELETABLE_CUSTOM_WORD = 1
        const val DELETABLE_USER_TUNED = 2

        private var instance: Rime? = null
        private var started = false

        @JvmStatic
        @Synchronized
        fun getInstance(fullCheck: Boolean = false): Rime {
            if (instance == null) {
                val newInstance = Rime(fullCheck)
                if (started) {
                    instance = newInstance
                }
                return newInstance
            }
            return instance!!
        }

        init {
            try {
                System.loadLibrary("lime_engine")
            } catch (e: Throwable) {
                if (System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true) {
                    android.util.Log.w("Rime", "Failed to load lime_engine native library", e)
                } else {
                    System.err.println("Rime: lime_engine native library unavailable in host test environment: ${e.message}")
                }
            }
        }

        @Synchronized
        fun startup(context: Context, fullCheck: Boolean): Boolean {
            if (started) return true
            val assetsReady = Launcher.instance.ensureRimeAssets()
            if (!assetsReady) {
                if (System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true) {
                    android.util.Log.e("Rime", "Cannot startup Rime: assets not ready (Failed)")
                }
                instance = null
                started = false
                return false
            }
            val success = startupRime(context, CustomConstant.RIME_DICT_PATH, CustomConstant.RIME_DICT_PATH, fullCheck)
            if (success) {
                started = true
                setRimePageSize(15)
            } else {
                instance = null
                started = false
            }
            return success
        }

        @JvmStatic
        @Synchronized
        fun destroy() {
            exitRime()
            instance = null
            started = false
        }

        @JvmStatic
        fun processKeySnapshot(keycode: Int, mask: Int): RimeSnapshot? {
            if (keycode <= 0 || keycode == 0xffffff) return null
            return processRimeKeyAndSnapshot(keycode, mask)
        }

        @JvmStatic
        fun clearCompositionSnapshot(): RimeSnapshot? = clearRimeCompositionAndSnapshot()

        @JvmStatic
        fun selectCandidateOnPageSnapshot(
            expectedCurrentPageNo: Int,
            targetPageNo: Int,
            targetPageIndex: Int,
        ): RimeSnapshot? = selectRimeCandidateOnPageAndSnapshot(
            expectedCurrentPageNo,
            targetPageNo,
            targetPageIndex,
        )

        @JvmStatic
        fun deleteCandidateOnPageSnapshot(
            expectedCurrentPageNo: Int,
            targetPageNo: Int,
            targetPageIndex: Int,
        ): RimeSnapshot? = deleteRimeCandidateOnPageAndSnapshot(
            expectedCurrentPageNo,
            targetPageNo,
            targetPageIndex,
        )

        @JvmStatic
        fun moveCandidatePageSnapshot(expectedPageNo: Int, direction: Int): RimeSnapshot? =
            moveRimeCandidatePageAndSnapshot(expectedPageNo, direction)

        @JvmStatic
        fun snapshotState(): RimeSnapshot? = snapshotRimeState()

        @JvmStatic
        fun selectT9PrefixSnapshot(prefix: String): RimeSnapshot? =
            selectT9PrefixAndSnapshot(prefix)

        @JvmStatic
        external fun startupRime(context: Context, sharedDir: String, userDir: String, fullCheck: Boolean): Boolean

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun setRimePageSize(pageSize: Int)

        @JvmStatic
        private external fun processRimeKeyAndSnapshot(keycode: Int, mask: Int): RimeSnapshot?

        @JvmStatic
        private external fun clearRimeCompositionAndSnapshot(): RimeSnapshot?

        @JvmStatic
        fun setUserDictWritesEnabled(enabled: Boolean) {
            try {
                nativeSetUserDictWritesEnabled(enabled)
            } catch (_: UnsatisfiedLinkError) {
            }
        }

        @JvmStatic
        private external fun nativeSetUserDictWritesEnabled(enabled: Boolean)

        val isStarted: Boolean
            @Synchronized get() = started

        @JvmStatic
        external fun setRimeOption(option: String, value: Boolean)

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        @JvmStatic
        private external fun selectRimeCandidateOnPageAndSnapshot(
            expectedCurrentPageNo: Int,
            targetPageNo: Int,
            targetPageIndex: Int,
        ): RimeSnapshot?

        @JvmStatic
        private external fun deleteRimeCandidateOnPageAndSnapshot(
            expectedCurrentPageNo: Int,
            targetPageNo: Int,
            targetPageIndex: Int,
        ): RimeSnapshot?

        @JvmStatic
        external fun getCandidateDeletableType(
            targetPageNo: Int,
            targetPageIndex: Int,
        ): Int

        @JvmStatic
        private external fun moveRimeCandidatePageAndSnapshot(expectedPageNo: Int, direction: Int): RimeSnapshot?

        @JvmStatic
        private external fun snapshotRimeState(): RimeSnapshot?

        @JvmStatic
        private external fun selectT9PrefixAndSnapshot(prefix: String): RimeSnapshot?

        @JvmStatic
        external fun getRimeKeycodeByName(name: String): Int

        @JvmStatic
        private external fun prewarmOpencc(configPath: String)

        @JvmStatic
        external fun openccConvert(configPath: String, text: String): String

        @JvmStatic
        external fun exportUserDict(dictName: String, outputPath: String): Int

        @JvmStatic
        external fun importUserDict(dictName: String, inputPath: String): Int

        @JvmStatic
        external fun getUserDictList(): List<String>

        @JvmStatic
        external fun checkWordsInSystemDict(
            dictName: String,
            phrases: Array<String>,
            codes: Array<String>,
        ): BooleanArray?

        @JvmStatic
        fun ensureOpencc(configName: String) {
            try {
                val file = java.io.File(CustomConstant.RIME_DICT_PATH, "opencc/$configName")
                if (file.exists()) {
                    prewarmOpencc(file.absolutePath)
                }
            } catch (_: Throwable) {}
        }

        @JvmStatic
        fun convertS2T(text: String): String {
            if (text.isEmpty()) return text
            return try {
                val file = java.io.File(CustomConstant.RIME_DICT_PATH, "opencc/s2t.json")
                if (file.exists()) {
                    openccConvert(file.absolutePath, text)
                } else {
                    text
                }
            } catch (_: Throwable) {
                text
            }
        }

        /**
         * Queries active Rime session option first, falls back to AppPrefs if no session exists
         */
        @JvmStatic
        fun isTraditionalMode(): Boolean {
            if (started) {
                runCatching { getRimeOption("traditionalization") }.getOrNull()?.let { return it }
            }
            return runCatching { org.bitfennec.lime.prefs.AppPrefs.getInstance().input.chineseFanTi.getValue() }.getOrDefault(false)
        }
    }
}
