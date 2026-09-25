package org.bitfennec.lime.manager

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import org.bitfennec.lime.BuildConfig
import org.bitfennec.lime.R
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.theme.ThemeFilesManager
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.utils.errorRuntime
import org.bitfennec.lime.utils.extract
import org.bitfennec.lime.utils.withTempDir
import org.bitfennec.lime.utils.versionCodeCompat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.coroutines.runBlocking
import org.bitfennec.lime.core.Rime
import org.bitfennec.lime.inputmethod.EnginePipeline
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object UserDataManager {

    private val json = Json { prettyPrint = true }

    private fun requireWorkerThread(operation: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "$operation must run on a worker thread"
        }
    }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    private fun writeFileTree(srcDir: File, destPrefix: String, dest: ZipOutputStream) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    private val sharedPrefsDir by lazy { File(Launcher.instance.context.applicationInfo.dataDir, "shared_prefs") }
    private val dataBasesDir by lazy { File(Launcher.instance.context.applicationInfo.dataDir, "databases") }
    private val rimeDir by lazy { File(Launcher.instance.context.filesDir, "rime") }
    private val externalDir by lazy { Launcher.instance.context.getExternalFilesDir(null) }

    @OptIn(ExperimentalSerializationApi::class)
    @WorkerThread
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        requireWorkerThread("export")
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream)
            // databases
            writeFileTree(dataBasesDir, "databases", zipStream)
            // rime
            if (rimeDir.exists()) {
                writeFileTree(rimeDir, "rime", zipStream)
            }
            // external
            val extDir = externalDir
            if (extDir != null && extDir.exists()) {
                writeFileTree(extDir, "external", zipStream)
            }
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = Launcher.instance.context.packageManager.getPackageInfo(Launcher.instance.context.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                BuildConfig.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File) {
        if (source.exists() && source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
        }
    }

    @WorkerThread
    fun import(src: InputStream) = runCatching {
        requireWorkerThread("import")
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val metadataFile = zipStream.extract(tempDir).find { it.name == "metadata.json" } ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir)
                copyDir(File(tempDir, "databases"), dataBasesDir)
                val importRimeDir = File(tempDir, "rime")
                if (importRimeDir.exists()) {
                    val started = runBlocking {
                        EnginePipeline.executeExclusive {
                            Rime.destroy()
                            copyDir(importRimeDir, rimeDir)
                            Rime.startup(Launcher.instance.context, fullCheck = false)
                        }
                    }
                    if (!started) error("Failed to restart RIME engine after import")
                }
                val importExternalDir = File(tempDir, "external")
                val extDir = externalDir
                if (importExternalDir.exists() && extDir != null) {
                    copyDir(importExternalDir, extDir)
                }
                metadata
            }
        }
    }

    /**
     * 1. Resets all preferences to defaults (preserves user dictionary and clipboard).
     */
    @WorkerThread
    fun resetPreferences(context: Context) = runCatching {
        requireWorkerThread("resetPreferences")
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit { clear() }
        ImeEnvironment.initData()
        ThemeManager.refreshThemes()
    }

    /**
     * 2. Clears user-trained words and frequency stats (preserves base dictionary).
     */
    @WorkerThread
    fun clearUserDictionary(context: Context) = runCatching {
        requireWorkerThread("clearUserDictionary")
        val started = runBlocking {
            EnginePipeline.executeExclusive {
                org.bitfennec.lime.core.Rime.destroy()
                val rimeDir = File(org.bitfennec.lime.application.CustomConstant.RIME_DICT_PATH)
                if (rimeDir.exists()) {
                    rimeDir.listFiles { file ->
                        file.name.contains("userdb", ignoreCase = true) ||
                        file.name.endsWith(".reverse.bin")
                    }?.forEach { it.deleteRecursively() }
                }
                org.bitfennec.lime.inputmethod.predict.PredictEngine.user1Gram.clear()
                org.bitfennec.lime.inputmethod.predict.PredictEngine.user2Gram.clear()
                org.bitfennec.lime.inputmethod.predict.PredictEngine.resetContext()
                org.bitfennec.lime.core.Rime.startup(context, fullCheck = true)
            }
        }
        if (!started) error("Failed to restart RIME engine")
    }

    /**
     * 3. Redeploys Rime engine dictionary index.
     */
    @WorkerThread
    fun redeployRime(context: Context) = runCatching {
        requireWorkerThread("redeployRime")
        val started = runBlocking {
            EnginePipeline.executeExclusive {
                org.bitfennec.lime.core.Rime.destroy()
                org.bitfennec.lime.core.Rime.startup(context, fullCheck = true)
            }
        }
        if (!started) error("Failed to restart RIME engine")
    }

    /**
     * 4. Restores factory defaults (clears configs, dictionary cache, Room DB, wallpapers).
     */
    // KTX SharedPreferences.edit() discards Editor.commit()'s Boolean.
    @SuppressLint("UseKtx")
    @WorkerThread
    suspend fun factoryReset(context: Context) = runCatching {
        requireWorkerThread("factoryReset")
        // Reset preferences
        check(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()) {
            "Failed to persist preference reset"
        }

        // Clear and recreate Room database
        AppDatabase.instance.clearAllTables()
        AppDatabase.initDb()
        AppDatabase.initSkbFunsDb()

        // Clear custom themes directory
        val themeDir = ThemeFilesManager.getThemeDir()
        if (themeDir.exists()) {
            themeDir.listFiles()?.forEach { it.deleteRecursively() }
        }

        // Clear user dictionaries and generated reverse-lookup files
        val started = EnginePipeline.executeExclusive {
            org.bitfennec.lime.core.Rime.destroy()
            val rimeDir = File(org.bitfennec.lime.application.CustomConstant.RIME_DICT_PATH)
            if (rimeDir.exists()) {
                rimeDir.listFiles { file ->
                    file.name.contains("userdb", ignoreCase = true) ||
                    file.name.endsWith(".reverse.bin")
                }?.forEach { it.deleteRecursively() }
            }
            org.bitfennec.lime.core.Rime.startup(context, fullCheck = true)
        }
        if (!started) error("Failed to restart RIME engine")

        // Re-initialize dimensions and theme
        ImeEnvironment.initData()
        ThemeManager.refreshThemes()
    }

    fun parseUserDictLine(line: String): UserDictEntry? {
        if (line.isBlank() || line.startsWith("#")) return null
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val phrase = parts[0].trim()
        val code = parts[1].trim()
        if (phrase.isEmpty() || code.isEmpty()) return null
        val commits = if (parts.size >= 3) parts[2].trim().toIntOrNull() ?: 0 else 0
        if (commits <= 0) return null
        return UserDictEntry(phrase, code, commits)
    }

    /**
     * Loads user-trained dictionary entries from Rime userdb snapshot.
     */
    @WorkerThread
    suspend fun loadUserDictEntries(
        context: Context,
        dictName: String = getActiveUserDictName()
    ): Result<List<UserDictEntry>> = runCatching {
        requireWorkerThread("loadUserDictEntries")
        EnginePipeline.executeExclusive {
            val tempFile = File(context.cacheDir, "userdb_export_${dictName}_${System.currentTimeMillis()}.tsv")
            try {
                if (tempFile.exists()) tempFile.delete()
                val count = Rime.exportUserDict(dictName, tempFile.absolutePath)
                if (count < 0) error("Failed to export user dictionary snapshot for $dictName")
                val list = ArrayList<UserDictEntry>(count.coerceAtLeast(16))
                if (tempFile.exists()) {
                    tempFile.useLines { lines ->
                        for (line in lines) {
                            val entry = parseUserDictLine(line)
                            if (entry != null) {
                                list.add(entry)
                            }
                        }
                    }
                }
                list.sortByDescending { it.commits }
                val phrases = Array(list.size) { list[it].phrase }
                val codes = Array(list.size) { list[it].code }
                val isSystem = runCatching {
                    Rime.checkWordsInSystemDict(dictName, phrases, codes)
                }.getOrNull()
                // yagni: table lookup failure degrades to custom words; upgrade when per-schema table error telemetry exists
                if (isSystem != null && isSystem.size == list.size) {
                    list.mapIndexed { i, entry -> entry.copy(isCustom = !isSystem[i]) }
                } else {
                    val escapedDict = org.bitfennec.lime.utils.StringUtils.escapeJson(dictName)
                    val payload = "{\"event\":\"classify_user_dict\",\"result\":\"degraded\",\"dict\":\"$escapedDict\"}"
                    if (System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true) {
                        android.util.Log.w("UserDataManager", payload)
                    } else {
                        System.err.println(payload)
                    }
                    list.map { it.copy(isCustom = true) }
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    /**
     * Deletes a single user dictionary entry by importing a tombstone line (commits = -1).
     */
    @WorkerThread
    suspend fun deleteUserDictEntry(
        context: Context,
        entry: UserDictEntry,
        dictName: String = getActiveUserDictName()
    ): Result<Unit> = runCatching {
        requireWorkerThread("deleteUserDictEntry")
        EnginePipeline.executeExclusive {
            val tombstoneFile = File(context.cacheDir, "tombstone_${System.currentTimeMillis()}.tsv")
            try {
                tombstoneFile.writeText("${entry.phrase}\t${entry.code}\t-1\n")
                val result = Rime.importUserDict(dictName, tombstoneFile.absolutePath)
                if (result < 0) error("Failed to import deletion tombstone for ${entry.phrase}")
            } finally {
                if (tombstoneFile.exists()) tombstoneFile.delete()
            }
        }
    }

    /**
     * Resolves the current active dictionary name (e.g. pinyin).
     */
    fun getActiveUserDictName(): String {
        val schema = runCatching { Rime.getCurrentRimeSchema() }.getOrDefault(CustomConstant.SCHEMA_ZH_QWERTY)
        return when (schema) {
            CustomConstant.SCHEMA_ZH_QWERTY,
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY,
            CustomConstant.SCHEMA_ZH_T9 -> CustomConstant.SCHEMA_ZH_QWERTY
            else -> if (schema.isNotBlank()) schema else CustomConstant.SCHEMA_ZH_QWERTY
        }
    }

    /**
     * Returns list of available user dictionary names.
     */
    fun getUserDictList(): List<String> {
        val list = runCatching { Rime.getUserDictList() }.getOrNull()
        if (list.isNullOrEmpty()) {
            return listOf(getActiveUserDictName())
        }
        return list
    }
}

data class UserDictEntry(
    val phrase: String,
    val code: String,
    val commits: Int,
    val isCustom: Boolean = false
)

