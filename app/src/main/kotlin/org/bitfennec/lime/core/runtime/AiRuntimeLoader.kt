package org.bitfennec.lime.core.runtime

import android.annotation.SuppressLint
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dynamic loader for native AI runtimes.
 * Responsibilities:
 * 1. Verifies 16KB (0x4000) ELF page alignment (prevents SIGBUS on Android 15+);
 * 2. Explicitly invokes System.load in dependency order (ORT runtime before JNI bridge);
 * 3. Provides atomic and idempotent state tracking.
 */
object AiRuntimeLoader {
    private const val TAG = "AiRuntimeLoader"
    private const val REQUIRED_PAGE_ALIGNMENT = 0x4000L // 16KB

    @Volatile
    var isOrtLoaded: Boolean = false
        internal set

    @Volatile
    var isOrtJniLoaded: Boolean = false
        internal set

    @Volatile
    var isSherpaLoaded: Boolean = false
        internal set

    internal fun resetForTesting() {
        isOrtLoaded = false
        isOrtJniLoaded = false
        isSherpaLoaded = false
    }

    // Packaging excludes these .so files from the APK lib dir; loadLibrary cannot open a filesDir path.
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun loadOrt(ortFile: File, jniSoFile: File? = null, requireJni: Boolean = false): Boolean {
        // Fast return if all requested dependencies are already loaded.
        // If voice loaded first without JNI and handwriting now requires JNI, continue to load JNI.
        if (isOrtLoaded && (!requireJni || isOrtJniLoaded)) {
            return true
        }

        if (!isOrtLoaded) {
            if (!ortFile.isFile || !ortFile.canRead()) {
                Log.e(TAG, "ORT SO file not accessible: ${ortFile.absolutePath}")
                return false
            }
            if (!isElf16KbAligned(ortFile)) {
                Log.e(TAG, "ORT SO file is NOT 16KB aligned: ${ortFile.absolutePath}")
                return false
            }

            val ortOk = runCatching {
                System.load(ortFile.absolutePath)
                isOrtLoaded = true
                ortFile.parentFile?.let { injectNativeLibraryDir(it) }
                Log.i(TAG, "Successfully loaded custom libonnxruntime.so from ${ortFile.absolutePath}")
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to dlopen custom libonnxruntime.so", error)
                false
            }
            if (!ortOk) return false
        }

        // Step 2: Load Java JNI bridge library (libonnxruntime4j_jni.so) in order.
        // Callers using OrtEnvironment require 4j_jni; returns false if missing or invalid.
        if (requireJni || jniSoFile != null) {
            if (isOrtJniLoaded) return true
            if (jniSoFile == null || !jniSoFile.isFile || !jniSoFile.canRead()) {
                Log.e(TAG, "ORT JNI SO file missing or not accessible: ${jniSoFile?.absolutePath}")
                return false
            }
            if (!isElf16KbAligned(jniSoFile)) {
                Log.e(TAG, "ORT JNI SO file is NOT 16KB aligned: ${jniSoFile.absolutePath}")
                return false
            }
            val jniOk = runCatching {
                System.load(jniSoFile.absolutePath)
                isOrtJniLoaded = true
                jniSoFile.parentFile?.let { injectNativeLibraryDir(it) }
                Log.i(TAG, "Successfully loaded libonnxruntime4j_jni.so from ${jniSoFile.absolutePath}")
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to dlopen libonnxruntime4j_jni.so", error)
                false
            }
            if (!jniOk) return false
        }

        return true
    }

    // Same constraint as loadOrt: the sherpa JNI library is a downloaded absolute path.
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun loadSherpaJni(sherpaJniFile: File, ortFile: File): Boolean {
        if (isSherpaLoaded) return true
        if (!isOrtLoaded) {
            if (!loadOrt(ortFile = ortFile, jniSoFile = null, requireJni = false)) {
                Log.e(TAG, "Cannot load sherpa-onnx-jni: dependency libonnxruntime.so failed to load")
                return false
            }
        }

        if (!sherpaJniFile.isFile || !sherpaJniFile.canRead()) {
            Log.e(TAG, "Sherpa JNI SO file not accessible: ${sherpaJniFile.absolutePath}")
            return false
        }
        if (!isElf16KbAligned(sherpaJniFile)) {
            Log.e(TAG, "Sherpa JNI SO file is NOT 16KB aligned: ${sherpaJniFile.absolutePath}")
            return false
        }

        return runCatching {
            System.load(sherpaJniFile.absolutePath)
            isSherpaLoaded = true
            sherpaJniFile.parentFile?.let { injectNativeLibraryDir(it) }
            Log.i(TAG, "Successfully loaded libsherpa-onnx-jni.so from ${sherpaJniFile.absolutePath}")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to dlopen libsherpa-onnx-jni.so", error)
            false
        }
    }

    private fun findField(instance: Any, name: String): java.lang.reflect.Field {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field $name not found in ${instance.javaClass}")
    }

    fun injectNativeLibraryDir(dir: File) {
        if (!dir.isDirectory) return
        runCatching {
            val classLoader = AiRuntimeLoader::class.java.classLoader ?: return
            val pathListField = findField(classLoader, "pathList")
            val pathList = pathListField.get(classLoader) ?: return

            // 1. nativeLibraryDirectories (List<File>)
            val dirsField = findField(pathList, "nativeLibraryDirectories")
            @Suppress("UNCHECKED_CAST")
            val dirs = dirsField.get(pathList) as? MutableList<File>
            if (dirs != null && !dirs.contains(dir)) {
                dirs.add(0, dir)
            }

            // 2. nativeLibraryPathElements
            val elementsField = findField(pathList, "nativeLibraryPathElements")
            val elements = elementsField.get(pathList) as? Array<*> ?: return
            val elementClass = elements.javaClass.componentType ?: return
            val constructor = elementClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == File::class.java
            } ?: elementClass.declaredConstructors.firstOrNull() ?: return
            constructor.isAccessible = true
            val newElement = constructor.newInstance(dir)
            val newArray = java.lang.reflect.Array.newInstance(elementClass, elements.size + 1)
            java.lang.reflect.Array.set(newArray, 0, newElement)
            System.arraycopy(elements, 0, newArray, 1, elements.size)
            elementsField.set(pathList, newArray)
            Log.i(TAG, "Successfully injected native library search dir: ${dir.absolutePath}")
        }.onFailure { error ->
            Log.w(TAG, "Could not inject native library search dir: ${error.message}")
        }
    }

    /**
     * Verifies whether PT_LOAD segments in ELF64 binary satisfy 16KB alignment (p_align >= 0x4000).
     */
    fun isElf16KbAligned(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val ident = ByteArray(16)
            raf.readFully(ident)
            // Verify ELF magic number
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
            ) {
                return@use false
            }
            // Verify 64-bit (ELFCLASS64 = 2) and little-endian (ELFDATA2LSB = 1)
            val is64Bit = ident[4] == 2.toByte()
            val isLittleEndian = ident[5] == 1.toByte()
            if (!is64Bit || !isLittleEndian) {
                return@use false
            }

            // Read remainder of ELF64 header
            val headerBytes = ByteArray(48) // 64 - 16
            raf.readFully(headerBytes)
            val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            // ELF64 header offsets
            // e_type(2), e_machine(2), e_version(4), e_entry(8), e_phoff(8)
            headerBuf.position(16) // Skip type, machine, version, entry
            val e_phoff = headerBuf.long

            // Skip e_shoff(8), e_flags(4), e_ehsize(2)
            headerBuf.position(headerBuf.position() + 14)
            val e_phentsize = headerBuf.short.toInt() and 0xFFFF
            val e_phnum = headerBuf.short.toInt() and 0xFFFF

            if (e_phnum <= 0 || e_phentsize < 56) {
                return@use false
            }

            // Iterate Program Headers
            val phEntryBytes = ByteArray(e_phentsize)
            for (i in 0 until e_phnum) {
                raf.seek(e_phoff + (i * e_phentsize))
                raf.readFully(phEntryBytes)
                val phBuf = ByteBuffer.wrap(phEntryBytes).order(ByteOrder.LITTLE_ENDIAN)

                val p_type = phBuf.int
                // PT_LOAD = 1
                if (p_type == 1) {
                    // ELF64 Program Header:
                    // p_type(4), p_flags(4), p_offset(8), p_vaddr(8), p_paddr(8), p_filesz(8), p_memsz(8), p_align(8)
                    phBuf.position(48)
                    val p_align = phBuf.long
                    if (p_align < REQUIRED_PAGE_ALIGNMENT) {
                        return@use false
                    }
                }
            }
            true
        }
    }.getOrDefault(false)
}
