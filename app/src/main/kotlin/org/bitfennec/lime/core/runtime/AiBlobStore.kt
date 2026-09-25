package org.bitfennec.lime.core.runtime

import android.content.Context
import java.io.File

/**
 * Content-addressable storage for AI artifacts.
 *
 * Layout:
 * filesDir/ai/
 *   blobs/
 *     <sha256> (or <sha256>/<fileName> for .so requirements)
 *     <sha256>.partial (in-progress range downloads)
 *   pkgs/
 *     <pkgId>/
 *       <version>/MANIFEST (artifactName -> sha256)
 *       current (atomic text pointing to active version)
 */
object AiBlobStore {

    private const val AI_DIR = "ai"
    private const val BLOBS_DIR = "blobs"
    private const val PKGS_DIR = "pkgs"
    const val MANIFEST_FILE = "MANIFEST"
    const val CURRENT_FILE = "current"

    fun getAiBaseDir(context: Context): File =
        File(context.filesDir, AI_DIR)

    fun getBlobsDir(context: Context): File =
        File(getAiBaseDir(context), BLOBS_DIR)

    fun getPkgsDir(context: Context): File =
        File(getAiBaseDir(context), PKGS_DIR)

    /**
     * Resolves the target file path for a content-addressed blob.
     * Native .so files are placed in blobs/<sha256>/<fileName> to satisfy OEM bionic linker .so suffix requirements.
     * Non-.so files are placed directly at blobs/<sha256>.
     */
    fun getBlobFile(blobsDir: File, sha256: String, fileName: String? = null): File {
        return if (fileName != null && fileName.endsWith(".so", ignoreCase = true)) {
            val shaDir = File(blobsDir, sha256)
            File(shaDir, fileName)
        } else {
            File(blobsDir, sha256)
        }
    }

    fun getBlobFile(context: Context, sha256: String, fileName: String? = null): File =
        getBlobFile(getBlobsDir(context), sha256, fileName)

    fun deletePackage(context: Context, packageId: String): Boolean {
        val pkgDir = File(getPkgsDir(context), packageId)
        return if (pkgDir.exists()) {
            pkgDir.deleteRecursively()
        } else {
            true
        }
    }

    fun deleteBlob(context: Context, sha256: String, fileName: String? = null): Boolean {
        val file = getBlobFile(context, sha256, fileName)
        val deleted = if (file.exists()) file.delete() else true
        val partial = getPartialFile(context, sha256)
        if (partial.exists()) partial.delete()
        if (fileName != null && fileName.endsWith(".so", ignoreCase = true)) {
            val shaDir = file.parentFile
            if (shaDir != null && shaDir.isDirectory && shaDir.list().isNullOrEmpty()) {
                shaDir.delete()
            }
        }
        return deleted
    }

    fun getPartialFile(blobsDir: File, sha256: String): File {
        return File(blobsDir, "$sha256.partial")
    }

    fun getPartialFile(context: Context, sha256: String): File =
        getPartialFile(getBlobsDir(context), sha256)

    fun hasBlob(
        blobsDir: File,
        sha256: String,
        expectedBytes: Long = 0L,
        minBytes: Long = 0L,
        fileName: String? = null
    ): Boolean {
        val file = getBlobFile(blobsDir, sha256, fileName)
        if (!file.isFile) return false
        val len = file.length()
        if (expectedBytes > 0L && len != expectedBytes) return false
        if (minBytes > 0L && len < minBytes) return false
        return true
    }

    fun hasBlob(
        context: Context,
        sha256: String,
        expectedBytes: Long = 0L,
        minBytes: Long = 0L,
        fileName: String? = null
    ): Boolean = hasBlob(getBlobsDir(context), sha256, expectedBytes, minBytes, fileName)

    fun getPackageDir(context: Context, packageId: String): File =
        File(getPkgsDir(context), packageId)

    fun getPackageVersionDir(context: Context, packageId: String, version: String): File =
        File(getPackageDir(context, packageId), version)

    fun getPackageCurrentFile(context: Context, packageId: String): File {
        return File(getPackageDir(context, packageId), CURRENT_FILE)
    }

    fun getActiveVersion(context: Context, packageId: String): String? {
        val currentFile = getPackageCurrentFile(context, packageId)
        if (!currentFile.isFile) return null
        return runCatching { currentFile.readText().trim() }.getOrNull()
    }
}
