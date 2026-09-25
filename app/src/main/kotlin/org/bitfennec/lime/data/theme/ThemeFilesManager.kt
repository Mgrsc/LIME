package org.bitfennec.lime.data.theme

import org.bitfennec.lime.application.Launcher
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileFilter
import java.util.UUID

object ThemeFilesManager {

    private val dir = File(Launcher.instance.context.getExternalFilesDir(null), "theme").also { it.mkdirs() }

    private fun themeFile(theme: Theme.Custom) = File(dir, theme.name + ".json")

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val croppedImageFile = File(dir, "$themeName-cropped.png")
        val srcImageFile = File(dir, "$themeName-src")
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun saveThemeFiles(theme: Theme.Custom) {
        themeFile(theme).writeText(Json.encodeToString(CustomThemeSerializer, theme))
    }

    fun deleteThemeFiles(theme: Theme.Custom) {
        themeFile(theme).delete()
        theme.backgroundImage?.let {
            File(it.croppedFilePath).delete()
            File(it.srcFilePath).delete()
        }
    }

    fun getThemeDir(): File = dir

    fun listThemes(): MutableList<Theme.Custom> {
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() } // newest first
            .mapNotNull decode@{
                val (theme, migrated) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, it.readText())
                }.getOrElse {
                    return@decode null
                }
                var currentTheme = theme
                if (theme.backgroundImage != null) {
                    val bg = theme.backgroundImage
                    val croppedFile = File(bg.croppedFilePath).let { if (it.exists()) it else File(dir, it.name) }
                    val srcFile = File(bg.srcFilePath).let { if (it.exists()) it else File(dir, it.name) }
                    if (!croppedFile.exists() || !srcFile.exists()) {
                        return@decode null
                    }
                    if (croppedFile.absolutePath != bg.croppedFilePath || srcFile.absolutePath != bg.srcFilePath) {
                        val fixedBg = bg.copy(croppedFilePath = croppedFile.absolutePath, srcFilePath = srcFile.absolutePath)
                        currentTheme = theme.copy(backgroundImage = fixedBg)
                        saveThemeFiles(currentTheme)
                    }
                }
                // Update the saved file if migration happens
                if (migrated) {
                    saveThemeFiles(currentTheme)
                }
                return@decode currentTheme
            }.toMutableList()
    }

}
