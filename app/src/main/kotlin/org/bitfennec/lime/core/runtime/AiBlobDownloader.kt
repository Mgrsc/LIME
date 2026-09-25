package org.bitfennec.lime.core.runtime

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Robust HTTP Range downloader for content-addressed AI artifacts.
 *
 * Enforces:
 * - D3: Stream SHA-256 computation, HTTP Range resumption, fsync durability.
 * - D4: Strict per-hop URL validation via [AiDownloadGuard].
 * - Probing: Concurrent Range: bytes=0-0 probe when mirrorIndex is 0 (Auto).
 */
object AiBlobDownloader {

    private const val TAG = "AiBlobDownloader"
    private const val USER_AGENT = "LIME/3.0.4 (Android; IME)"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_REDIRECTS = 5
    private const val PROBE_TIMEOUT_MS = 2000
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000

    enum class Stage {
        PROBING,
        CONNECTING
    }

    fun formatSpeed(speedBps: Long): String {
        val speedKb = speedBps / 1024
        return if (speedKb >= 1024) "%.1f MB/s".format(speedKb / 1024f) else "$speedKb KB/s"
    }

    /**
     * Downloads an artifact into [targetFile] via content-addressable verified staging.
     */
    suspend fun downloadBlob(
        artifact: AiPackageSpec.Artifact,
        targetFile: File,
        partialFile: File,
        allowedPrefixes: List<String> = artifact.pinnedUrls,
        mirrorIndex: Int = 0,
        modelRepoDirectory: String? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long, speedBps: Long) -> Unit)? = null,
        onStage: ((stage: Stage, detail: String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val partialParent = partialFile.parentFile
        if (partialParent != null && !partialParent.exists()) partialParent.mkdirs()

        // 1. If target file already exists and valid, skip download
        if (targetFile.isFile) {
            val len = targetFile.length()
            val sizeMatches = (artifact.expectedBytes <= 0L || len == artifact.expectedBytes) &&
                (artifact.minExpectedBytes <= 0L || len >= artifact.minExpectedBytes)
            if (sizeMatches && AiDownloadGuard.verifySha256(targetFile, artifact.sha256)) {
                if (artifact.kind == AiPackageSpec.ArtifactKind.ELF_SO && !AiRuntimeLoader.isElf16KbAligned(targetFile)) {
                    Log.w(TAG, "Existing ELF target not 16KB aligned: ${targetFile.name}")
                } else {
                    return@withContext true
                }
            }
        }

        // 2. Resolve prioritized candidate URLs
        val candidateUrls = resolveCandidateUrls(artifact.pinnedUrls, mirrorIndex, onStage)

        // 3. Iterate through candidates
        for (candidateUrl in candidateUrls) {
            if (!isActive) return@withContext false

            val host = runCatching { URL(candidateUrl).host }.getOrDefault("")
            onStage?.invoke(Stage.CONNECTING, host)

            val success = tryDownloadFromSource(
                sourceUrl = candidateUrl,
                artifact = artifact,
                targetFile = targetFile,
                partialFile = partialFile,
                allowedPrefixes = allowedPrefixes,
                modelRepoDirectory = modelRepoDirectory,
                onProgress = onProgress
            )
            if (success) {
                return@withContext true
            }
            Log.w(TAG, "Download attempt failed for $candidateUrl, falling back to next candidate")
        }

        // yagni: Failure reason not categorized as enum; upgrade when distinct UI handling for checksum vs network is needed
        false
    }

    private suspend fun tryDownloadFromSource(
        sourceUrl: String,
        artifact: AiPackageSpec.Artifact,
        targetFile: File,
        partialFile: File,
        allowedPrefixes: List<String>,
        modelRepoDirectory: String?,
        onProgress: ((bytesRead: Long, totalBytes: Long, speedBps: Long) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        var currentUrlStr = sourceUrl
        var redirectCount = 0

        try {
            var existingLength = if (partialFile.isFile) partialFile.length() else 0L
            val digest = MessageDigest.getInstance("SHA-256")

            // Pre-digest partial bytes if resuming
            if (existingLength > 0L) {
                val preDigestOk = runCatching {
                    FileInputStream(partialFile).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            digest.update(buffer, 0, read)
                        }
                    }
                    true
                }.getOrDefault(false)

                if (!preDigestOk) {
                    partialFile.delete()
                    existingLength = 0L
                    digest.reset()
                }
            }

            var rangeResetCount = 0
            while (redirectCount < MAX_REDIRECTS) {
                val parsedUrl = runCatching { URL(currentUrlStr) }.getOrNull() ?: return@withContext false
                if (!AiDownloadGuard.isAllowedArtifactUrl(
                        url = parsedUrl,
                        fileName = artifact.fileName,
                        sha256 = artifact.sha256,
                        allowedPrefixes = allowedPrefixes,
                        modelRepoDirectory = modelRepoDirectory
                    )) {
                    Log.e(TAG, "Download URL rejected by security guard: $currentUrlStr")
                    return@withContext false
                }

                conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (existingLength > 0L) {
                        setRequestProperty("Range", "bytes=$existingLength-")
                    }
                }

                val code = conn.responseCode
                if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrEmpty()) {
                        redirectCount++
                        if (redirectCount >= MAX_REDIRECTS) {
                            Log.e(TAG, "Download exceeded max redirects limit ($MAX_REDIRECTS) for $sourceUrl")
                            return@withContext false
                        }
                        currentUrlStr = runCatching { URL(parsedUrl, location).toString() }.getOrDefault(location)
                        continue
                    }
                }

                if (code == 416) {
                    // Range Not Satisfiable: partial file invalid or oversized, reset and restart
                    conn.disconnect()
                    if (++rangeResetCount > 1) {
                        Log.e(TAG, "Repeated 416 Range Not Satisfiable for $currentUrlStr, aborting")
                        return@withContext false
                    }
                    partialFile.delete()
                    existingLength = 0L
                    digest.reset()
                    continue
                }

                if (code !in 200..299) {
                    Log.e(TAG, "HTTP response error: $code for $currentUrlStr")
                    conn.disconnect()
                    return@withContext false
                }

                break
            }

            val finalConn = conn ?: return@withContext false
            val isPartialContent = finalConn.responseCode == 206
            if (!isPartialContent && existingLength > 0L) {
                // Server does not support Range or returned full content (200 OK)
                partialFile.delete()
                existingLength = 0L
                digest.reset()
            }

            val totalContentLength = if (artifact.expectedBytes > 0L) {
                artifact.expectedBytes
            } else {
                val cl = finalConn.contentLengthLong
                if (cl > 0L) existingLength + cl else 0L
            }

            var downloadedBytes = existingLength
            var lastProgressTime = System.currentTimeMillis()
            var lastDownloadedBytes = downloadedBytes

            finalConn.inputStream.use { input ->
                FileOutputStream(partialFile, isPartialContent).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!isActive) {
                            output.fd.sync()
                            return@withContext false
                        }

                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        val diff = now - lastProgressTime
                        if (diff >= 500) {
                            val speedBps = ((downloadedBytes - lastDownloadedBytes) * 1000L) / diff
                            onProgress?.invoke(downloadedBytes, totalContentLength, speedBps)
                            lastProgressTime = now
                            lastDownloadedBytes = downloadedBytes
                        }
                    }
                    output.fd.sync()
                }
            }

            // Size checks
            val finalLen = partialFile.length()
            if (artifact.expectedBytes > 0L && finalLen != artifact.expectedBytes) {
                Log.e(TAG, "Downloaded file size mismatch: $finalLen != expected ${artifact.expectedBytes}")
                partialFile.delete()
                return@withContext false
            }
            if (artifact.minExpectedBytes > 0L && finalLen < artifact.minExpectedBytes) {
                Log.e(TAG, "Downloaded file truncated: $finalLen < min expected ${artifact.minExpectedBytes}")
                partialFile.delete()
                return@withContext false
            }

            // Streaming SHA-256 verification
            val finalHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!finalHash.equals(artifact.sha256, ignoreCase = true)) {
                Log.e(TAG, "SHA-256 mismatch for ${artifact.fileName}: $finalHash != ${artifact.sha256}")
                partialFile.delete()
                return@withContext false
            }

            // ELF 16KB page alignment gate
            if (artifact.kind == AiPackageSpec.ArtifactKind.ELF_SO && !AiRuntimeLoader.isElf16KbAligned(partialFile)) {
                Log.e(TAG, "ELF not 16KB aligned: ${artifact.fileName}")
                partialFile.delete()
                return@withContext false
            }

            // Atomic rename into target location
            if (targetFile.exists()) targetFile.delete()
            if (!partialFile.renameTo(targetFile)) {
                partialFile.copyTo(targetFile, overwrite = true)
                partialFile.delete()
            }

            onProgress?.invoke(finalLen, finalLen, 0L)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception during download of ${artifact.fileName}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Prioritizes candidate URLs based on user preference index or concurrent latency probing.
     */
    suspend fun resolveCandidateUrls(
        pinnedUrls: List<String>,
        mirrorIndex: Int,
        onStage: ((stage: Stage, detail: String) -> Unit)? = null
    ): List<String> {
        if (pinnedUrls.isEmpty()) return emptyList()
        if (mirrorIndex < 0) return pinnedUrls
        if (mirrorIndex > 0) {
            val hasGitHub = pinnedUrls.any { it.contains("github.com", ignoreCase = true) }
            val hasModelScope = pinnedUrls.any { it.contains("modelscope", ignoreCase = true) }

            val prioritized = if (hasGitHub) {
                // Handwriting / Runtime endpoints (GitHub & ghproxy only)
                when (mirrorIndex) {
                    1 -> pinnedUrls.filter { !it.contains("ghproxy", ignoreCase = true) && it.contains("github.com", ignoreCase = true) }
                    2 -> pinnedUrls.filter { it.contains("ghproxy", ignoreCase = true) }
                    else -> emptyList()
                }
            } else if (hasModelScope) {
                // Voice endpoints (ModelScope, HF-Mirror, HuggingFace only)
                when (mirrorIndex) {
                    1 -> pinnedUrls.filter { it.contains("modelscope", ignoreCase = true) }
                    2 -> pinnedUrls.filter { it.contains("hf-mirror", ignoreCase = true) }
                    3 -> pinnedUrls.filter { it.contains("huggingface", ignoreCase = true) }
                    else -> emptyList()
                }
            } else {
                emptyList()
            }

            if (prioritized.isNotEmpty()) {
                val remaining = pinnedUrls.filterNot { prioritized.contains(it) }
                return prioritized + remaining
            }
        }

        // Auto mode (0): concurrent probe using Range: bytes=0-0 GET
        onStage?.invoke(Stage.PROBING, "")
        return probeFastestUrls(pinnedUrls)
    }

    private val hostLatencyCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /**
     * Probes candidate URLs with Range: bytes=0-0 GET and returns URLs sorted by response latency.
     * Caches latency per host for 5 minutes to avoid redundant network probing across multiple artifacts.
     */
    suspend fun probeFastestUrls(urls: List<String>): List<String> = coroutineScope {
        if (urls.size <= 1) return@coroutineScope urls

        val now = System.currentTimeMillis()
        val uncachedUrls = mutableListOf<String>()
        val knownLatencies = mutableMapOf<String, Long>()

        for (u in urls) {
            val host = runCatching { URL(u).host }.getOrDefault(u)
            val cached = hostLatencyCache[host]
            if (cached != null && now - cached.second < CACHE_TTL_MS) {
                knownLatencies[u] = cached.first
            } else {
                uncachedUrls.add(u)
            }
        }

        if (uncachedUrls.isNotEmpty()) {
            val deferredList = uncachedUrls.map { urlStr ->
                async(Dispatchers.IO) {
                    if (!isActive) return@async Pair(urlStr, Long.MAX_VALUE)
                    val startTime = System.currentTimeMillis()
                    val isOk = runCatching {
                        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            connectTimeout = PROBE_TIMEOUT_MS
                            readTimeout = PROBE_TIMEOUT_MS
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", USER_AGENT)
                            setRequestProperty("Range", "bytes=0-0")
                        }
                        val code = conn.responseCode
                        conn.disconnect()
                        code in 200..399
                    }.getOrDefault(false)

                    val latency = if (isOk) System.currentTimeMillis() - startTime else Long.MAX_VALUE
                    if (isOk) {
                        val host = runCatching { URL(urlStr).host }.getOrDefault(urlStr)
                        hostLatencyCache[host] = Pair(latency, System.currentTimeMillis())
                    }
                    Pair(urlStr, latency)
                }
            }

            val results = deferredList.awaitAll()
            results.forEach { knownLatencies[it.first] = it.second }
        }

        urls.sortedBy { knownLatencies[it] ?: Long.MAX_VALUE }
    }
}
