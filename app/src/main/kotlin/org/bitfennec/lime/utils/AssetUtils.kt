package org.bitfennec.lime.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AssetUtils {

    private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming transfer buffer


    internal fun copyFileOrDirChecked(
        context: Context,
        parent: String,
        path: String,
        destParent: String,
        overwrite: Boolean,
        preserveExistingFiles: Set<String> = emptySet(),
    ): Boolean {
        return try {
            val assetManager = context.assets
            val assetPath = if (path.isEmpty()) parent else File(parent, path).path
            val assets = assetManager.list(assetPath)
            if (assets.isNullOrEmpty()) {
                copyFile(context, parent, path, destParent, overwrite, preserveExistingFiles)
            } else {
                val dir = File(destParent, path)
                if (!dir.exists() && !dir.mkdirs()) {
                    return false
                }
                if (!dir.isDirectory) {
                    return false
                }
                assets.all { asset ->
                    val subPath = if (path.isEmpty()) asset else File(path, asset).path
                    copyFileOrDirChecked(context, parent, subPath, destParent, overwrite, preserveExistingFiles)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun copyFile(
        context: Context,
        parentAssetPath: String,
        filename: String,
        destParent: String,
        overwrite: Boolean,
        preserveExistingFiles: Set<String> = emptySet(),
    ): Boolean {
        return try {
            val assetManager = context.assets
            val assetPath = if (filename.isEmpty()) parentAssetPath else File(parentAssetPath, filename).path
            val newFile = File(destParent, filename)
            val parentDir = newFile.parentFile
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                return false
            }
            if (parentDir != null && !parentDir.isDirectory) {
                return false
            }
            if (newFile.exists() && (preserveExistingFiles.contains(newFile.name) || !overwrite)) return true

            val tmpFile = File(newFile.parentFile, "${newFile.name}.tmp")
            try {
                if (tmpFile.exists() && !tmpFile.delete()) {
                    return false
                }
                assetManager.open(assetPath).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                try {
                    Files.move(
                        tmpFile.toPath(),
                        newFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(
                        tmpFile.toPath(),
                        newFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                true
            } finally {
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
