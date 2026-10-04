package org.bitfennec.lime.utils

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Returns true if this file is canonically confined within [dir] (or equals [dir] when [allowExact] is true).
 */
fun File.isConfinedTo(dir: File, allowExact: Boolean = false): Boolean {
    val targetPath = this.canonicalFile.path
    val dirPath = dir.canonicalFile.path.let {
        if (it.endsWith(File.separator)) it else it + File.separator
    }
    return targetPath.startsWith(dirPath) || (allowExact && targetPath == dirPath.removeSuffix(File.separator))
}

/**
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    var entry = nextEntry
    while (entry != null) {
        val target = File(destDir, entry.name)
        if (entry.isDirectory) {
            if (!target.isConfinedTo(destDir, allowExact = true)) {
                throw SecurityException("Zip directory is outside destination: ${entry.name}")
            }
            target.mkdirs()
        } else {
            if (!target.isConfinedTo(destDir)) {
                throw SecurityException("Zip entry is outside destination: ${entry.name}")
            }
            target.parentFile?.mkdirs()
            target.outputStream().use { copyTo(it) }
        }
        closeEntry()
        entry = nextEntry
    }
    return destDir.listFiles()?.toList() ?: emptyList()
}
