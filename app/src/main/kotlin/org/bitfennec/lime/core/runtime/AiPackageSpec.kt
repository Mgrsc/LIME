package org.bitfennec.lime.core.runtime

import android.content.Context
import androidx.annotation.StringRes
import org.bitfennec.lime.R

/**
 * Unified AI Package and Artifact Specification.
 *
 * Single source of truth for all runtime binaries, neural models, and asset dictionaries.
 *
 * DAG topology:
 *   ort ──┬── ort-jni ── handwriting (onnx + dict + pinyin)
 *         └── sherpa-jni ── voice (model.int8.onnx + tokens.txt)
 *
 * Identity is defined strictly by SHA-256 and verified page alignment for ELF native libraries.
 */
object AiPackageSpec {

    enum class ArtifactKind {
        ELF_SO,
        MODEL,
        ASSET,
    }

    data class Artifact(
        val id: String,
        val fileName: String,
        val sha256: String,
        val expectedBytes: Long,
        val minExpectedBytes: Long,
        val kind: ArtifactKind,
        val pinnedUrls: List<String>,
        @StringRes val displayNameRes: Int,
    ) {
        fun getDisplayName(context: Context): String = try {
            context.getString(displayNameRes)
        } catch (_: Exception) {
            fileName
        }
    }

    data class Package(
        val id: String,
        val version: String,
        val dependencies: List<String>,
        val artifacts: List<Artifact>,
        @StringRes val displayNameRes: Int,
    ) {
        fun getDisplayName(context: Context): String = try {
            context.getString(displayNameRes)
        } catch (_: Exception) {
            id
        }
    }

    // Version definitions
    const val RUNTIME_VERSION = "1.29.0"
    const val SHERPA_VERSION = "1.13.7-ort1.29.0"
    const val HW_VERSION = "ppocrv6-v1.1"
    const val VOICE_VERSION = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

    // File names
    const val ORT_SO_NAME = "libonnxruntime.so"
    const val ORT_JNI_SO_NAME = "libonnxruntime4j_jni.so"
    const val SHERPA_JNI_SO_NAME = "libsherpa-onnx-jni.so"

    const val HW_MODEL_NAME = "ppocrv6_rec.onnx"
    const val HW_DICT_NAME = "ppocrv6_dict.txt"
    const val HW_PINYIN_NAME = "pinyin_dict.tsv"

    const val VOICE_MODEL_NAME = "model.int8.onnx"
    const val VOICE_TOKENS_NAME = "tokens.txt"

    // URL Prefixes
    private const val PREFIX_RUNTIME_GHPROXY_304 = "https://ghproxy.net/https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/"
    private const val PREFIX_RUNTIME_GH_304 = "https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/"
    private const val PREFIX_RUNTIME_GHPROXY_304_BITFENNEC = "https://ghproxy.net/https://github.com/bitfennec/LIME/releases/download/v3.0.4-runtime/"
    private const val PREFIX_RUNTIME_GH_304_BITFENNEC = "https://github.com/bitfennec/LIME/releases/download/v3.0.4-runtime/"

    val RUNTIME_PREFIXES = listOf(
        PREFIX_RUNTIME_GHPROXY_304,
        PREFIX_RUNTIME_GH_304,
        PREFIX_RUNTIME_GHPROXY_304_BITFENNEC,
        PREFIX_RUNTIME_GH_304_BITFENNEC,
        "https://ghproxy.net/https://github.com/Mgrsc/LIME/releases/download/v3.0.3-runtime/",
        "https://github.com/Mgrsc/LIME/releases/download/v3.0.3-runtime/",
    )

    private const val PREFIX_HW_GHPROXY_304 = "https://ghproxy.net/https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/"
    private const val PREFIX_HW_GH_304 = "https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/"
    private const val PREFIX_HW_GHPROXY_304_BITFENNEC = "https://ghproxy.net/https://github.com/bitfennec/LIME/releases/download/v3.0.4-runtime/"
    private const val PREFIX_HW_GH_304_BITFENNEC = "https://github.com/bitfennec/LIME/releases/download/v3.0.4-runtime/"

    val HANDWRITING_PREFIXES = listOf(
        PREFIX_HW_GHPROXY_304,
        PREFIX_HW_GH_304,
        PREFIX_HW_GHPROXY_304_BITFENNEC,
        PREFIX_HW_GH_304_BITFENNEC,
        "https://ghproxy.net/https://github.com/Mgrsc/LIME/releases/download/v3.0.3-runtime/",
        "https://github.com/Mgrsc/LIME/releases/download/v3.0.3-runtime/",
    )

    private const val PREFIX_VOICE_MS = "https://www.modelscope.cn/models/gomodels/sherpa/resolve/master/$VOICE_VERSION/"
    private const val PREFIX_VOICE_HF_MIRROR = "https://hf-mirror.com/csukuangfj/$VOICE_VERSION/resolve/main/"
    private const val PREFIX_VOICE_HF = "https://huggingface.co/csukuangfj/$VOICE_VERSION/resolve/main/"

    val VOICE_PREFIXES = listOf(
        PREFIX_VOICE_MS,
        PREFIX_VOICE_HF_MIRROR,
        PREFIX_VOICE_HF,
    )

    // Artifacts
    val ortArtifact = Artifact(
        id = "ort-so",
        fileName = ORT_SO_NAME,
        sha256 = "3a602b463d434d20fbf69cd1b8bdfd2f86a2cb67d08de97aec8f62476fa4ab87",
        expectedBytes = 32_120_992L,
        minExpectedBytes = 32_120_992L,
        kind = ArtifactKind.ELF_SO,
        pinnedUrls = RUNTIME_PREFIXES.map { "$it$ORT_SO_NAME" },
        displayNameRes = R.string.ai_artifact_ort_so,
    )

    val ortJniArtifact = Artifact(
        id = "ort-jni",
        fileName = ORT_JNI_SO_NAME,
        sha256 = "45673d7fba377fe0555892460766051b5993d65c9f65e528dc9d515c07c9f683",
        expectedBytes = 111_648L,
        minExpectedBytes = 111_648L,
        kind = ArtifactKind.ELF_SO,
        pinnedUrls = RUNTIME_PREFIXES.map { "$it$ORT_JNI_SO_NAME" },
        displayNameRes = R.string.ai_artifact_ort_jni,
    )

    val sherpaJniArtifact = Artifact(
        id = "sherpa-jni",
        fileName = SHERPA_JNI_SO_NAME,
        sha256 = "07fe4c91a64c8c71f461fada26de95caf32ee24799731670343b69eb19877f1a",
        expectedBytes = 3_442_400L,
        minExpectedBytes = 3_442_400L,
        kind = ArtifactKind.ELF_SO,
        pinnedUrls = RUNTIME_PREFIXES.map { "$it$SHERPA_JNI_SO_NAME" },
        displayNameRes = R.string.ai_artifact_sherpa_jni,
    )

    val hwModelArtifact = Artifact(
        id = "hw-model",
        fileName = HW_MODEL_NAME,
        sha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
        expectedBytes = 21_159_378L,
        minExpectedBytes = 21_159_378L,
        kind = ArtifactKind.MODEL,
        pinnedUrls = HANDWRITING_PREFIXES.map { "$it$HW_MODEL_NAME" },
        displayNameRes = R.string.ai_artifact_hw_model,
    )

    val hwDictArtifact = Artifact(
        id = "hw-dict",
        fileName = HW_DICT_NAME,
        sha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
        expectedBytes = 74_947L,
        minExpectedBytes = 74_947L,
        kind = ArtifactKind.ASSET,
        pinnedUrls = HANDWRITING_PREFIXES.map { "$it$HW_DICT_NAME" },
        displayNameRes = R.string.ai_artifact_hw_dict,
    )

    val hwPinyinArtifact = Artifact(
        id = "hw-pinyin",
        fileName = HW_PINYIN_NAME,
        sha256 = "92fec20460aecbedd3027ce614756077768632f56ee61e9a8be0d26cbefcef2d",
        expectedBytes = 171_989L,
        minExpectedBytes = 171_989L,
        kind = ArtifactKind.ASSET,
        pinnedUrls = HANDWRITING_PREFIXES.map { "$it$HW_PINYIN_NAME" },
        displayNameRes = R.string.ai_artifact_hw_pinyin,
    )

    val voiceModelArtifact = Artifact(
        id = "voice-model",
        fileName = VOICE_MODEL_NAME,
        sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        expectedBytes = 239_233_841L,
        minExpectedBytes = 239_233_841L,
        kind = ArtifactKind.MODEL,
        pinnedUrls = VOICE_PREFIXES.map { "$it$VOICE_MODEL_NAME" },
        displayNameRes = R.string.ai_artifact_voice_model,
    )

    val voiceTokensArtifact = Artifact(
        id = "voice-tokens",
        fileName = VOICE_TOKENS_NAME,
        sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
        expectedBytes = 315_894L,
        minExpectedBytes = 315_894L,
        kind = ArtifactKind.ASSET,
        pinnedUrls = VOICE_PREFIXES.map { "$it$VOICE_TOKENS_NAME" },
        displayNameRes = R.string.ai_artifact_voice_tokens,
    )

    // Packages
    val ortPackage = Package(
        id = "ort",
        version = RUNTIME_VERSION,
        dependencies = emptyList(),
        artifacts = listOf(ortArtifact),
        displayNameRes = R.string.ai_artifact_ort_so,
    )

    val ortJniPackage = Package(
        id = "ort-jni",
        version = RUNTIME_VERSION,
        dependencies = listOf("ort"),
        artifacts = listOf(ortJniArtifact),
        displayNameRes = R.string.ai_artifact_ort_jni,
    )

    val sherpaJniPackage = Package(
        id = "sherpa-jni",
        version = SHERPA_VERSION,
        dependencies = listOf("ort"),
        artifacts = listOf(sherpaJniArtifact),
        displayNameRes = R.string.ai_artifact_sherpa_jni,
    )

    val handwritingPackage = Package(
        id = "handwriting",
        version = HW_VERSION,
        dependencies = listOf("ort", "ort-jni"),
        artifacts = listOf(hwModelArtifact, hwDictArtifact, hwPinyinArtifact),
        displayNameRes = R.string.ai_download_notif_title_handwriting,
    )

    val voicePackage = Package(
        id = "voice",
        version = VOICE_VERSION,
        dependencies = listOf("ort", "sherpa-jni"),
        artifacts = listOf(voiceModelArtifact, voiceTokensArtifact),
        displayNameRes = R.string.ai_download_notif_title_voice,
    )

    val ALL_PACKAGES: Map<String, Package> = mapOf(
        ortPackage.id to ortPackage,
        ortJniPackage.id to ortJniPackage,
        sherpaJniPackage.id to sherpaJniPackage,
        handwritingPackage.id to handwritingPackage,
        voicePackage.id to voicePackage,
    )

    fun getPackage(id: String): Package? = ALL_PACKAGES[id]

    /**
     * Resolves topological order of dependencies (depth-first, dependencies first, deduplicated).
     */
    fun resolveDependencies(packageId: String): List<Package> {
        val result = mutableListOf<Package>()
        val visited = mutableSetOf<String>()

        fun dfs(id: String) {
            if (id in visited) return
            val pkg = ALL_PACKAGES[id] ?: return
            for (dep in pkg.dependencies) {
                dfs(dep)
            }
            visited.add(id)
            result.add(pkg)
        }

        dfs(packageId)
        return result
    }
}
