package org.bitfennec.lime.core.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * High-level lifecycle, query, and loader facade for AI runtimes and models.
 * Delegates storage resolution and verification to [AiPackageInstaller] and [AiBlobStore].
 */
object AiModuleManager {

    private const val TAG = "AiModuleManager"
    @Volatile
    private var cachedOrtReady: Boolean? = null
    @Volatile
    private var cachedHwReady: Boolean? = null
    @Volatile
    private var cachedVoiceReady: Boolean? = null

    fun invalidateCache() {
        cachedOrtReady = null
        cachedHwReady = null
        cachedVoiceReady = null
    }

    fun getOrtFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.ortArtifact.sha256, AiPackageSpec.ortArtifact.fileName)

    fun getOrtJniFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.ortJniArtifact.sha256, AiPackageSpec.ortJniArtifact.fileName)

    fun getHandwritingModelFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.hwModelArtifact.sha256, AiPackageSpec.hwModelArtifact.fileName)

    fun getHandwritingKeysFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.hwDictArtifact.sha256, AiPackageSpec.hwDictArtifact.fileName)

    fun getHandwritingPinyinDictFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.hwPinyinArtifact.sha256, AiPackageSpec.hwPinyinArtifact.fileName)

    fun getVoiceJniFile(context: Context): File =
        AiBlobStore.getBlobFile(context, AiPackageSpec.sherpaJniArtifact.sha256, AiPackageSpec.sherpaJniArtifact.fileName)

    /**
     * Checks if shared ORT is ready.
     */
    fun isOrtReady(context: Context): Boolean {
        cachedOrtReady?.let { return it }
        val ready = AiPackageInstaller.isPackageReady(context, "ort")
        // Missing files can become ready before the download caller invalidates caches.
        if (ready) cachedOrtReady = true
        return ready
    }

    /**
     * Checks if Java JNI bridge library (libonnxruntime4j_jni.so) is ready.
     */
    fun isOrtJniReady(context: Context): Boolean =
        AiPackageInstaller.isPackageReady(context, "ort-jni")

    /**
     * Checks if handwriting module (ORT + PP-OCRv6 + dictionary) is fully ready.
     */
    fun isHandwritingReady(context: Context): Boolean {
        cachedHwReady?.let { return it }
        val ready = AiPackageInstaller.isPackageReady(context, "handwriting")
        // Missing files can become ready before the download caller invalidates caches.
        if (ready) cachedHwReady = true
        return ready
    }

    /**
     * Checks if voice recognition module (ORT + sherpa-jni + SenseVoice weights) is fully ready.
     */
    fun isVoiceReady(context: Context): Boolean {
        cachedVoiceReady?.let { return it }
        val ready = AiPackageInstaller.isPackageReady(context, "voice")
        if (ready) cachedVoiceReady = true
        return ready
    }

    /**
     * Prepares and loads underlying ORT runtime required for handwriting.
     */
    fun prepareHandwritingRuntime(context: Context): Boolean {
        if (!isOrtReady(context)) {
            Log.w(TAG, "Cannot prepare handwriting runtime: ORT is not ready")
            return false
        }
        val ortFile = getOrtFile(context)
        val jniFile = getOrtJniFile(context)
        return AiRuntimeLoader.loadOrt(ortFile = ortFile, jniSoFile = jniFile, requireJni = true)
    }

    /**
     * Prepares and sequentially loads underlying ORT and Sherpa JNI required for voice.
     */
    fun prepareVoiceRuntime(context: Context): Boolean {
        if (!isOrtReady(context)) {
            Log.w(TAG, "Cannot prepare voice runtime: ORT is not ready")
            return false
        }
        val ortFile = getOrtFile(context)
        val sherpaJniFile = getVoiceJniFile(context)
        if (!sherpaJniFile.isFile) {
            Log.w(TAG, "Cannot prepare voice runtime: libsherpa-onnx-jni.so not found")
            return false
        }
        return AiRuntimeLoader.loadSherpaJni(sherpaJniFile = sherpaJniFile, ortFile = ortFile)
    }
}
