package org.bitfennec.lime.core.runtime

import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Security guard for AI runtime and model downloads.
 * Enforces strict URL whitelist validation and streaming SHA-256 integrity verification.
 *
 * Implements D4 strict security policy:
 * 1. Protocol must be HTTPS (port 443 or default), no userinfo, no path traversal (".." / "%" / "//").
 * 2. First hop must match pinned source URLs.
 * 3. Subsequent hops (redirects) must land on authorized CDN hosts with asset-bound path verification:
 *    - ModelScope LFS objects must strictly match /prod/lfs-objects/{sha[0:2]}/{sha[2:4]}/{sha[4:]}
 *    - HuggingFace resolve-cache must match model repo and fileName
 *    - Exact host matching for mirror sources (e.g. hf-mirror.com); redirects must land on authorized CDN domains
 *    - Authorized CDN fallback domains (e.g. *.aliyuncs.com, *.hf.co) are strictly gated by post-download streaming SHA-256
 * 4. Ultimate integrity is governed by SHA-256 and expected byte boundaries.
 */
object AiDownloadGuard {

    private val ALLOWED_DOMAINS = listOf(
        "aliyuncs.com",
        "hf.co",
        "github.com",
        "githubusercontent.com",
        "ghproxy.net",
    )

    /**
     * Checks artifact-specific URL against pinned prefixes, expected SHA-256, and target filename.
     * Strict validation for individual artifacts (Voice, Handwriting, and Runtime).
     */
    fun isAllowedArtifactUrl(
        url: URL,
        fileName: String,
        sha256: String,
        allowedPrefixes: List<String>,
        modelRepoDirectory: String? = null,
    ): Boolean {
        if (!isBasicHttpsValid(url)) return false

        val urlStr = url.toString()
        if (allowedPrefixes.any { urlStr.startsWith(it) && urlStr.endsWith(fileName) }) {
            return true
        }

        val host = url.host.lowercase()
        val path = url.path

        return when {
            host == "www.modelscope.cn" -> {
                (path.startsWith("/api/v1/models/") || path.startsWith("/models/")) &&
                    (path.endsWith("/$fileName") || (url.query?.contains("FilePath=$fileName") == true))
            }
            host.endsWith("modelscope.cn") -> {
                path == "/prod/lfs-objects/${sha256.take(2)}/${sha256.substring(2, 4)}/${sha256.drop(4)}"
            }
            host == "hf-mirror.com" -> {
                (path.startsWith("/csukuangfj/") || path.startsWith("/bitfennec/")) && path.endsWith("/$fileName")
            }
            host == "huggingface.co" -> {
                if (modelRepoDirectory != null && path == "/csukuangfj/$modelRepoDirectory/resolve/main/$fileName") {
                    true
                } else if (path.startsWith("/api/resolve-cache/models/")) {
                    val prefix = if (modelRepoDirectory != null) {
                        "/api/resolve-cache/models/csukuangfj/$modelRepoDirectory/"
                    } else {
                        "/api/resolve-cache/models/"
                    }
                    if (!path.startsWith(prefix)) return false
                    val segments = path.removePrefix(prefix).split('/')
                    segments.size == 2 &&
                        segments[0].matches(Regex("[0-9a-fA-F]{7,64}")) &&
                        segments[1] == fileName
                } else {
                    (path.startsWith("/csukuangfj/") || path.startsWith("/bitfennec/")) && path.endsWith("/$fileName")
                }
            }
            ALLOWED_DOMAINS.any { host == it || host.endsWith(".$it") } -> true
            else -> false
        }
    }

    private fun isBasicHttpsValid(url: URL): Boolean {
        if (url.protocol != "https" || url.userInfo != null || (url.port != -1 && url.port != 443)) return false
        val path = url.path
        if (path.contains("..") || path.contains('%')) {
            return false
        }
        if (path.contains("//")) {
            // Disallow consecutive slashes unless it is part of an embedded "https://" in ghproxy URLs
            val withoutSchemes = path.replace("https://", "").replace("http://", "")
            if (withoutSchemes.contains("//")) {
                return false
            }
        }
        return true
    }

    fun computeSha256(file: File): String? {
        if (!file.isFile || file.length() <= 0) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    fun verifySha256(file: File, expectedSha256: String): Boolean {
        val actualHash = computeSha256(file) ?: return false
        return actualHash.equals(expectedSha256, ignoreCase = true)
    }
}
