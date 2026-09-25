package org.bitfennec.lime.application

import android.content.Context
import android.util.Log
import org.bitfennec.lime.utils.AssetUtils.copyFileOrDirChecked
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object RimeAssetBootstrap {
    enum class State {
        Preparing,
        FullReady,
        Failed,
    }

    internal const val BUNDLE_MARKER = ".lime_bundle"
    private const val LOG_TAG = "RimeAssetBootstrap"
    internal const val MANIFEST_ASSET = "rime-assets-manifest.json"
    private val REQUIRED_FILES = buildSet {
        for (schema in listOf("pinyin", "double_pinyin_flypy", "t9_pinyin", "english")) {
            add("$schema.schema.yaml")
            add("build/$schema.schema.yaml")
            add("build/$schema.prism.bin")
        }
        addAll(listOf("default.yaml", "build/default.yaml", "build/pinyin.table.bin",
            "build/english.table.bin", "build/pinyin.reverse.bin"))
        for (name in listOf("s2t.json", "STPhrases.ocd2", "STCharacters.ocd2",
            "emoji.json", "emoji.ocd2", "others.ocd2")) {
            add("opencc/$name")
        }
    }

    private val LEGACY_DIRECTORIES = listOf(
        "rime-full.staging",
        "rime-full-ready",
        "rime_hans_experiment",
        "rime_hans_experiment_seed",
        "rime_seed",
    )

    private val PRESERVED_USER_CONFIG_FILES = setOf(
        "user.yaml",
        "installation.yaml",
        "custom_phrase.txt",
    )

    @Volatile
    var state: State = State.Preparing
        internal set

    internal data class AssetFingerprint(val bytes: Long, val sha256: String)
    private var verifiedBundle: Pair<String, String>? = null
    private var verifiedFileTimes: Map<String, Long> = emptyMap()

    @Synchronized
    fun ensureActiveAssets(context: Context): State {
        return try {
            cleanupLegacyDirectories(context)
            val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
            val destination = File(CustomConstant.RIME_DICT_PATH)
            ensureBundle(destination, manifest) { installAssetBundle(context, "rime", destination) }
        } catch (e: Exception) {
            state = State.Failed
            logFailure("read_manifest", e.message)
            state
        }
    }

    // The same transaction is used by Android and isolated filesystem fault tests.
    @Synchronized
    internal fun ensureBundle(directory: File, manifestText: String, install: () -> Boolean): State {
        state = State.Preparing
        return try {
            val manifest = parseManifest(manifestText)
            val marker = "lime-sha256:" + sha256(manifestText.byteInputStream())
            val identity = directory.canonicalPath to marker
            val fileTimes = manifest.keys.associateWith { File(directory, it).lastModified() }
            val markerMatches = File(directory, BUNDLE_MARKER).let { it.isFile && it.readText() == marker }
            if (markerMatches && verifyFiles(directory, manifest, hash = verifiedBundle != identity || verifiedFileTimes != fileTimes)) {
                verifiedBundle = identity
                verifiedFileTimes = fileTimes
                state = State.FullReady
                return state
            }
            verifiedBundle = null
            invalidateBundleMarker(directory)
            check(install()) { "Asset copy failed" }
            check(verifyFiles(directory, manifest, hash = true)) { "Asset verification failed" }
            check(writeBundle(directory, marker)) { "Asset marker commit failed" }
            verifiedBundle = identity
            verifiedFileTimes = manifest.keys.associateWith { File(directory, it).lastModified() }
            state = State.FullReady
            state
        } catch (e: Exception) {
            verifiedBundle = null
            runCatching { invalidateBundleMarker(directory) }
            state = State.Failed
            logFailure("install_or_verify_bundle", e.message)
            state
        }
    }

    internal fun parseManifest(text: String): Map<String, AssetFingerprint> {
        val entries = Json.parseToJsonElement(text).jsonObject.getValue("system_assets").jsonObject
        require(entries.keys.containsAll(REQUIRED_FILES)) { "Incomplete asset manifest" }
        return entries.mapValues { (path, value) ->
            require(!path.startsWith('/') && '\\' !in path &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                File(path).name !in PRESERVED_USER_CONFIG_FILES) { "Invalid system asset path" }
            val entry = value.jsonObject
            val bytes = entry.getValue("bytes").jsonPrimitive.long
            val digest = entry.getValue("sha256").jsonPrimitive.content
            require(bytes > 0 && digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid asset fingerprint" }
            AssetFingerprint(bytes, digest)
        }
    }

    private fun verifyFiles(directory: File, manifest: Map<String, AssetFingerprint>, hash: Boolean): Boolean {
        val root = directory.canonicalFile.toPath()
        return manifest.all { (path, expected) ->
            val file = File(directory, path)
            file.canonicalFile.toPath().startsWith(root) && file.isFile && file.length() == expected.bytes &&
                (!hash || file.inputStream().use { sha256(it) } == expected.sha256)
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = input.read(buffer)
        while (count != -1) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun invalidateBundleMarker(directory: File) {
        for (name in listOf(BUNDLE_MARKER, "$BUNDLE_MARKER.tmp")) {
            val file = File(directory, name)
            check(!file.exists() || file.delete()) { "Cannot invalidate asset marker" }
        }
    }

    private fun cleanupLegacyDirectories(context: Context) {
        val filesDir = context.filesDir ?: return
        for (name in LEGACY_DIRECTORIES) {
            val dir = File(filesDir, name)
            if (dir.exists()) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    private fun installAssetBundle(context: Context, assetDirectory: String, destination: File): Boolean {
        if (!destination.exists() && !destination.mkdirs()) return false
        if (!destination.isDirectory) return false
        return copyFileOrDirChecked(
            context,
            assetDirectory,
            "",
            destination.absolutePath,
            overwrite = true,
            preserveExistingFiles = PRESERVED_USER_CONFIG_FILES,
        )
    }

    internal fun writeBundle(directory: File, markerText: String): Boolean = runCatching {
        if (!directory.exists() && !directory.mkdirs()) return false
        val marker = File(directory, BUNDLE_MARKER)
        val temporary = File(directory, "$BUNDLE_MARKER.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(markerText.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        true
    }.getOrElse { false }

    internal fun resetForTesting(initialState: State = State.Preparing) {
        state = initialState
        verifiedBundle = null
        verifiedFileTimes = emptyMap()
    }

    private fun logFailure(stage: String, message: String? = null) {
        val sanitizedMessage = message?.let { org.bitfennec.lime.utils.StringUtils.escapeJson(it) }
        val payload = if (!sanitizedMessage.isNullOrEmpty()) {
            "{\"event\":\"rime_asset_bootstrap\",\"result\":\"failed\",\"stage\":\"$stage\",\"error\":\"$sanitizedMessage\"}"
        } else {
            "{\"event\":\"rime_asset_bootstrap\",\"result\":\"failed\",\"stage\":\"$stage\"}"
        }
        if (System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true) {
            Log.e(LOG_TAG, payload)
        } else {
            System.err.println(payload)
        }
    }
}
