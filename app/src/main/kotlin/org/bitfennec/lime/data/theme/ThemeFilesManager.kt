package org.bitfennec.lime.data.theme

import org.bitfennec.lime.application.Launcher
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileFilter
import java.util.UUID

object ThemeFilesManager {

    private val dir = File(Launcher.instance.context.getExternalFilesDir(null), "theme").also { it.mkdirs() }
    internal val canonicalDir by lazy { dir.canonicalFile }

    private fun themeFile(theme: Theme.Custom): File {
        val safeName = File(theme.name).name
        require(safeName == theme.name && safeName.isNotBlank() && safeName != "." && safeName != "..") {
            "Invalid theme name: ${theme.name}"
        }
        val file = File(dir, "$safeName.json").canonicalFile
        require(file.parentFile == canonicalDir) {
            "Theme file escaped theme directory"
        }
        return file
    }

    private fun getConfinedFile(rawPath: String): File? {
        val safeName = File(rawPath).name
        if (safeName.isBlank() || safeName == "." || safeName == "..") return null
        val target = File(dir, safeName).canonicalFile
        return if (target.parentFile == canonicalDir) target else null
    }

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
        runCatching { themeFile(theme).delete() }
        theme.backgroundImage?.let { bg ->
            getConfinedFile(bg.croppedFilePath)?.delete()
            getConfinedFile(bg.srcFilePath)?.delete()
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
                val safeName = File(theme.name).name
                if (safeName != theme.name || safeName.isBlank() || safeName == "." || safeName == "..") {
                    return@decode null
                }
                var currentTheme = theme
                if (theme.backgroundImage != null) {
                    val bg = theme.backgroundImage
                    val croppedFile = getConfinedFile(bg.croppedFilePath)
                    val srcFile = getConfinedFile(bg.srcFilePath)
                    if (croppedFile == null || srcFile == null || !croppedFile.exists() || !srcFile.exists()) {
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
