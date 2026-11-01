package org.bitfennec.lime.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

internal object AiRuntimeArchive {
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun extract(
        artifact: AiPackageSpec.Artifact,
        archiveFile: File,
        targetFile: File,
        partialFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val source = requireNotNull(artifact.archiveSource)
        require(source.entryName == "jni/arm64-v8a/${artifact.fileName}")
        require(artifact.expectedBytes > 0)
        try {
            currentCoroutineContext().ensureActive()
            if (archiveFile.length() != source.archive.expectedBytes ||
                !AiDownloadGuard.verifySha256(archiveFile, source.archive.sha256)
            ) return@withContext false
            targetFile.parentFile?.mkdirs()
            partialFile.parentFile?.mkdirs()
            ZipFile(archiveFile).use { archive ->
                val entry = archive.getEntry(source.entryName) ?: return@withContext false
                if (entry.isDirectory || entry.size != artifact.expectedBytes) return@withContext false
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > artifact.expectedBytes) return@withContext false
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (partialFile.length() != artifact.expectedBytes ||
                !AiDownloadGuard.verifySha256(partialFile, artifact.sha256) ||
                (artifact.kind == AiPackageSpec.ArtifactKind.ELF_SO && !AiRuntimeLoader.isElf16KbAligned(partialFile))
            ) return@withContext false
            partialFile.renameTo(targetFile)
        } catch (_: IOException) {
            false
        } finally {
            partialFile.delete()
        }
    }
}
